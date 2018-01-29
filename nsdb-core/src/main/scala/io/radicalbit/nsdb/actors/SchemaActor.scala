package io.radicalbit.nsdb.actors

import akka.actor.{Actor, ActorLogging, Props}
import cats.data.Validated.{Invalid, Valid}
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._
import io.radicalbit.nsdb.index.{Schema, SchemaIndex}
import io.radicalbit.nsdb.model.SchemaField
import org.apache.lucene.index.IndexWriter

class SchemaActor(val basePath: String, val db: String, val namespace: String)
    extends Actor
    with SchemaSupport
    with ActorLogging {

  override def receive: Receive = {

    case GetSchema(_, _, metric) =>
      val schema = getSchema(metric)
      sender ! SchemaGot(db, namespace, metric, schema)

    case UpdateSchema(_, _, metric, newSchema) =>
      checkAndUpdateSchema(db, namespace, metric, newSchema)

    case UpdateSchemaFromRecord(_, _, metric, record) =>
      (Schema(metric, record), getSchema(metric)) match {
        case (Valid(newSchema), Some(oldSchema)) =>
          checkAndUpdateSchema(namespace = namespace, metric = metric, oldSchema = oldSchema, newSchema = newSchema)
        case (Valid(newSchema), None) =>
          updateSchema(newSchema)
          sender ! SchemaUpdated(db, namespace, metric, newSchema)
        case (Invalid(errs), _) => sender ! UpdateSchemaFailed(db, namespace, metric, errs.toList)
      }

    case DeleteSchema(_, _, metric) =>
      getSchema(metric) match {
        case Some(s) =>
          deleteSchema(s)
          sender ! SchemaDeleted(db, namespace, metric)
        case None => sender ! SchemaDeleted(db, namespace, metric)
      }
    case DeleteAllSchemas(_, _) =>
      deleteAllSchemas()
      sender ! AllSchemasDeleted(db, namespace)
  }

  private def getSchema(metric: String) = schemas.get(metric) orElse schemaIndex.getSchema(metric)

  private def checkAndUpdateSchema(db: String, namespace: String, metric: String, newSchema: Schema): Unit =
    getSchema(metric) match {
      case Some(oldSchema) =>
        checkAndUpdateSchema(namespace = namespace, metric = metric, oldSchema = oldSchema, newSchema = newSchema)
      case None =>
        updateSchema(newSchema)
        sender ! SchemaUpdated(db, namespace, metric, newSchema)
    }

  private def checkAndUpdateSchema(namespace: String, metric: String, oldSchema: Schema, newSchema: Schema): Unit =
    if (oldSchema == newSchema)
      sender ! SchemaUpdated(db, namespace, metric, newSchema)
    else
      SchemaIndex.getCompatibleSchema(oldSchema, newSchema) match {
        case Valid(fields) =>
          updateSchema(metric, fields)
          sender ! SchemaUpdated(db, namespace, metric, newSchema)
        case Invalid(list) => sender ! UpdateSchemaFailed(db, namespace, metric, list.toList)
      }

  private def updateSchema(metric: String, fields: Seq[SchemaField]): Unit =
    updateSchema(Schema(metric, fields))

  private def updateSchema(schema: Schema): Unit = {
    schemas += (schema.metric -> schema)
    implicit val writer: IndexWriter = schemaIndex.getWriter
    schemaIndex.update(schema.metric, schema)
    writer.close()
  }

  private def deleteSchema(schema: Schema): Unit = {
    schemas -= schema.metric
    implicit val writer: IndexWriter = schemaIndex.getWriter
    schemaIndex.delete(schema)
    writer.commit()
    writer.close()
  }

  private def deleteAllSchemas(): Unit = {
    schemas --= schemas.keys
    implicit val writer: IndexWriter = schemaIndex.getWriter
    schemaIndex.deleteAll()
    writer.close()
  }
}

object SchemaActor {

  def props(basePath: String, db: String, namespace: String): Props = Props(new SchemaActor(basePath, db, namespace))

}
