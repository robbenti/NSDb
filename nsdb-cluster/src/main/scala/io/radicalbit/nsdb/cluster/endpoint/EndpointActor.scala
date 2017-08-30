package io.radicalbit.nsdb.cluster.endpoint

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.client.ClusterClientReceptionist
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import io.radicalbit.nsdb.common.protocol.{ExecuteSQLStatement, Bit, BitOut, SQLStatementExecuted}
import io.radicalbit.nsdb.common.statement.{
  DeleteSQLStatement,
  DropSQLStatement,
  InsertSQLStatement,
  SelectSQLStatement
}
import io.radicalbit.nsdb.coordinator.ReadCoordinator
import io.radicalbit.nsdb.coordinator.ReadCoordinator.{SelectStatementExecuted, SelectStatementFailed}
import io.radicalbit.nsdb.coordinator.WriteCoordinator._

import scala.concurrent.Future
import scala.concurrent.duration._

object EndpointActor {

  def props(readCoordinator: ActorRef, writeCoordinator: ActorRef) =
    Props(new EndpointActor(readCoordinator = readCoordinator, writeCoordinator = writeCoordinator))

}

class EndpointActor(readCoordinator: ActorRef, writeCoordinator: ActorRef) extends Actor with ActorLogging {

  implicit val timeout: Timeout = 1 second
  import context.dispatcher

  ClusterClientReceptionist(context.system).registerService(self)

  def receive = {

    case ExecuteSQLStatement(statement: SelectSQLStatement) =>
      (readCoordinator ? ReadCoordinator.ExecuteStatement(statement))
        .map {
          case SelectStatementExecuted(values: Seq[BitOut]) =>
            SQLStatementExecuted(values)
          case SelectStatementFailed(reason) =>
            throw new RuntimeException(s"Cannot execute the given select statement. The reason is $reason.")
        }
        .pipeTo(sender())

    case ExecuteSQLStatement(statement: InsertSQLStatement) =>
      val result = InsertSQLStatement
        .unapply(statement)
        .map {
          case (namespace, metric, ts, dimensions, value) =>
            val timestamp = ts getOrElse System.currentTimeMillis
            (writeCoordinator ? MapInput(timestamp, namespace, metric, Bit(timestamp, dimensions.fields, value)))
              .mapTo[InputMapped]
        }
        .getOrElse(Future(throw new RuntimeException("The insert SQL statement is invalid.")))
      result.map(_ => SQLStatementExecuted(res = Seq.empty)).pipeTo(sender())
    case ExecuteSQLStatement(statement: DeleteSQLStatement) =>
      (writeCoordinator ? ExecuteDeleteStatement(statement.namespace, statement))
        .mapTo[DeleteStatementExecuted]
        .map(_ => SQLStatementExecuted(res = Seq.empty))
        .pipeTo(sender())
    case ExecuteSQLStatement(statement: DropSQLStatement) =>
      (writeCoordinator ? DropMetric(statement.namespace, statement.metric))
        .mapTo[DeleteStatementExecuted]
        .map(_ => SQLStatementExecuted(res = Seq.empty))
        .pipeTo(sender())
  }
}
