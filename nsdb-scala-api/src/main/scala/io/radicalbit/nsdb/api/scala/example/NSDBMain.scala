package io.radicalbit.nsdb.api.scala.example

import io.radicalbit.nsdb.api.scala.NSDB
import io.radicalbit.nsdb.rpc.response.RPCInsertResult
import io.radicalbit.nsdb.rpc.responseSQL.SQLStatementResponse

import scala.concurrent._
import scala.concurrent.duration._

/**
  * Example App for writing a Bit.
  */
object NSDBMainWrite extends App {

  val nsdb = Await.result(NSDB.connect(host = "127.0.0.1", port = 7817)(ExecutionContext.global), 10.seconds)

  val series = nsdb
    .db("root")
    .namespace("registry")
    .bit("people")
    .value(Some(new java.math.BigDecimal("13")))
    .dimension("city", "Mouseton")
    .dimension("notimportant", None)
    .dimension("Someimportant", Some(2))
    .dimension("gender", "M")
    .dimension("bigDecimalLong", new java.math.BigDecimal("12"))
    .dimension("bigDecimalDouble", new java.math.BigDecimal("12.5"))
    .dimension("OptionBigDecimal", Some(new java.math.BigDecimal("15.5")))

  val res: Future[RPCInsertResult] = nsdb.write(series)

  println(Await.result(res, 10.seconds))
}

/**
  * Example App for executing a query.
  */
object NSDBMainRead extends App {

  val nsdb = Await.result(NSDB.connect(host = "127.0.0.1", port = 7817)(ExecutionContext.global), 10.seconds)

  val query = nsdb
    .db("root")
    .namespace("registry")
    .query("select * from people limit 1")

  val readRes: Future[SQLStatementResponse] = nsdb.execute(query)

  println(Await.result(readRes, 10.seconds))
}