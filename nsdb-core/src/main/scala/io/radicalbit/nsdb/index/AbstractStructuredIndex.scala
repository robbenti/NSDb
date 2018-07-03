/*
 * Copyright 2018 Radicalbit S.r.l.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.radicalbit.nsdb.index

import io.radicalbit.nsdb.common.JSerializable
import io.radicalbit.nsdb.common.protocol.{Bit, DimensionFieldType, FieldClassType, TagFieldType}
import io.radicalbit.nsdb.index.lucene.{AllGroupsAggregationCollector, Index}
import io.radicalbit.nsdb.model.Schema
import io.radicalbit.nsdb.statement.StatementParser.SimpleField
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document._
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.{IndexSearcher, MatchAllDocsQuery, Query, Sort}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
  * Generic Time series index based on entries of class [[Bit]].
  */
abstract class AbstractStructuredIndex extends Index[Bit] with TypeSupport {

  override def _keyField: String = "timestamp"

  override def write(data: Bit)(implicit writer: IndexWriter): Try[Long] = {
    val doc       = new Document
    val allFields = validateRecord(data)
    allFields match {
      case Success(fields) =>
        fields.foreach(f => {
          doc.add(f)
        })
        Try(writer.addDocument(doc))
      case Failure(t) => Failure(t)
    }
  }

  override def validateRecord(bit: Bit): Try[Seq[Field]] =
    validateSchemaTypeSupport(bit)
      .map(se => se.flatMap(elem => elem.indexType.indexField(elem.name, elem.value)))

  override def delete(data: Bit)(implicit writer: IndexWriter): Try[Long] = {
    Try {
      val query  = LongPoint.newExactQuery(_keyField, data.timestamp)
      val result = writer.deleteDocuments(query)
      writer.forceMergeDeletes(true)
      result
    }
  }

  def toRecord(schema: Schema, document: Document, fields: Seq[SimpleField]): Bit = {

    def extractFields(schema: Schema, document: Document, fields: Seq[SimpleField], fieldClassType: FieldClassType) = {
      document.getFields.asScala
        .filterNot {
          case f =>
            schema.fields
              .filter(fieldSchema => fieldSchema.name == f.name && fieldSchema.fieldClassType == fieldClassType)
              .headOption
              .map { _ =>
                f.name() == _keyField || f.name() == _valueField || f.name() == _countField || (fields.nonEmpty &&
                !fields.exists {
                  case sf => (sf.name == f.name() || sf.name.trim == "*") && !sf.count
                })
              }
              .getOrElse(true)
        }
        .map {
          case f if f.numericValue() != null => f.name() -> f.numericValue()
          case f                             => f.name() -> f.stringValue()
        }
    }

    val dimensions: Map[String, JSerializable] = extractFields(schema, document, fields, DimensionFieldType).toMap
    val tags: Map[String, JSerializable]       = extractFields(schema, document, fields, TagFieldType).toMap

    val aggregated: Map[String, JSerializable] =
      fields.filter(_.count).map(_.toString -> document.getField("_count").numericValue()).toMap

    val value = document.getField(_valueField).numericValue()
    Bit(
      timestamp = document.getField(_keyField).numericValue().longValue(),
      value = value,
      dimensions = dimensions,
      tags = tags ++ aggregated
    )
  }

  private[index] def rawQuery(query: Query, limit: Int, sort: Option[Sort])(
      implicit searcher: IndexSearcher): Seq[Document] = {
    executeQuery(searcher, query, limit, sort)(identity)
  }

  private[index] def rawQuery[VT, S](query: Query,
                                     collector: AllGroupsAggregationCollector[VT, S],
                                     limit: Option[Int],
                                     sort: Option[Sort]): Seq[Document] = {
    this.getSearcher.search(query, collector)

    val sortedGroupMap = sort
      .flatMap(_.getSort.headOption)
      .map(s => collector.getOrderedMap(s))
      .getOrElse(collector.getGroupMap)
      .toSeq

    val limitedGroupMap = limit.map(sortedGroupMap.take).getOrElse(sortedGroupMap)

    limitedGroupMap.map {
      case (g, v) =>
        val doc = new Document
        doc.add(collector.indexField(g, collector.groupField))
        doc.add(collector.indexField(v, collector.aggField))
        doc.add(new LongPoint(_keyField, 0))
        doc
    }
  }

  /**
    * Executes a simple [[Query]] using the given schema.
    * @param schema the [[Schema]] to be used.
    * @param query the [[Query]] to be executed.
    * @param fields sequence of fields that must be included in the result.
    * @param limit results limit.
    * @param sort optional lucene [[Sort]].
    * @param f function to obtain an element B from an element T.
    * @tparam B return type.
    * @return the query results as a list of entries.
    */
  def query[B](schema: Schema, query: Query, fields: Seq[SimpleField], limit: Int, sort: Option[Sort])(
      f: Bit => B): Seq[B] = {
    if (fields.nonEmpty && fields.forall(_.count)) {
      executeCountQuery(this.getSearcher, query, limit) { doc =>
        f(toRecord(schema, doc, fields))
      }
    } else
      executeQuery(this.getSearcher, query, limit, sort) { doc =>
        f(toRecord(schema: Schema, doc, fields))
      }
  }

  /**
    * Executes an aggregated query.
    * @param query the [[Query]] to be executed.
    * @param collector the subclass of [[AllGroupsAggregationCollector]]
    * @param limit results limit.
    * @param sort optional lucene [[Sort]].
    * @return the query results as a list of entries.
    */
  def query(schema: Schema,
            query: Query,
            collector: AllGroupsAggregationCollector[_, _],
            limit: Option[Int],
            sort: Option[Sort]): Seq[Bit] = {
    rawQuery(query, collector, limit, sort).map(d => toRecord(schema, d, Seq.empty))
  }

  /**
    * Returns all the entries where `field` = `value`
    * @param field the field name to use to filter data.
    * @param value the value to check the field with.
    * @param fields sequence of fields that must be included in the result.
    * @param limit results limit.
    * @param sort optional lucene [[Sort]].
    * @param f function to obtain an element B from an element T.
    * @tparam B return type.
    * @return the manipulated Seq.
    */
  def query[B](schema: Schema,
               field: String,
               value: String,
               fields: Seq[SimpleField],
               limit: Int,
               sort: Option[Sort] = None)(f: Bit => B): Seq[B] = {
    val parser = new QueryParser(field, new StandardAnalyzer())
    val q      = parser.parse(value)

    query(schema, q, fields, limit, sort)(f)
  }

  /**
    * Returns all the entries.
    * @return all the entries.
    */
  def all(schema: Schema): Seq[Bit] = {
    Try { query(schema, new MatchAllDocsQuery(), Seq.empty, Int.MaxValue, None)(identity) } match {
      case Success(docs: Seq[Bit]) => docs
      case Failure(_)              => Seq.empty
    }
  }

  /**
    * Returns all entries applying the defined callback function
    *
    * @param f the callback function
    * @tparam B return type of f
    * @return all entries
    */
  def all[B](schema: Schema, f: Bit => B): Seq[B] = {
    Try { query(schema, new MatchAllDocsQuery(), Seq.empty, Int.MaxValue, None)(f) } match {
      case Success(docs: Seq[B]) => docs
      case Failure(_)            => Seq.empty
    }
  }

  /**
    * Executes a simple count [[Query]] using the given schema.
    * @return the query results as a list of entries.
    */
  def getCount(): Int =
    executeCountQuery(this.getSearcher, new MatchAllDocsQuery(), Int.MaxValue) { doc =>
      doc.getField(_countField).numericValue().intValue()
    }.headOption.getOrElse(0)
}