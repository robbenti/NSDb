package io.radicalbit.nsdb.cluster.actor

import akka.actor.Props
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Count
import akka.cluster.{Cluster, MemberStatus}
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import io.radicalbit.nsdb.cluster.coordinator.MetadataCoordinator.commands._
import io.radicalbit.nsdb.cluster.coordinator.MetadataCoordinator.events._
import io.radicalbit.nsdb.cluster.index.{Location, MetricInfo}
import io.radicalbit.rtsae.STMultiNodeSpec

import scala.concurrent.duration._

object MetadataSpec extends MultiNodeConfig {
  val node1 = role("node-1")
  val node2 = role("node-2")

  commonConfig(ConfigFactory.parseString("""
    |akka.loglevel = ERROR
    |akka.actor{
    | provider = "cluster"
    | control-aware-dispatcher {
    |     mailbox-type = "akka.dispatch.UnboundedControlAwareMailbox"
    |   }
    |}
    |akka.log-dead-letters-during-shutdown = off
    |nsdb{
    |
    |  read-coordinator.timeout = 10 seconds
    |  namespace-schema.timeout = 10 seconds
    |  namespace-data.timeout = 10 seconds
    |  publisher.timeout = 10 seconds
    |  publisher.scheduler.interval = 5 seconds
    |  write.scheduler.interval = 15 seconds
    |
    |  sharding {
    |    interval = 1d
    |  }
    |
    |  read {
    |    parallelism {
    |      initial-size = 1
    |      lower-bound= 1
    |      upper-bound = 1
    |    }
    |  }
    |
    |  index.base-path = "target/test_index/MetadataTest"
    |  write-coordinator.timeout = 5 seconds
    |  metadata-coordinator.timeout = 5 seconds
    |  commit-log {
    |    enabled = false
    |  }
    |}
    """.stripMargin))

}

class MetadataSpecMultiJvmNode1 extends MetadataSpec

class MetadataSpecMultiJvmNode2 extends MetadataSpec

class MetadataSpec extends MultiNodeSpec(MetadataSpec) with STMultiNodeSpec with ImplicitSender {

  import MetadataSpec._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)

  val mediator = DistributedPubSub(system).mediator

  val guardian = system.actorOf(Props[DatabaseActorsGuardian], "guardian")

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Metadata system" must {

    "join cluster" in within(10.seconds) {
      join(node1, node1)
      join(node2, node1)
      awaitAssert {
        mediator ! Count
        expectMsg(2)
      }
      enterBarrier("joined")
    }

    "add location from different nodes" in within(10.seconds) {

      val addresses = cluster.state.members.filter(_.status == MemberStatus.Up).map(_.address)

      runOn(node1) {
        val selfMember = cluster.selfMember
        val nodeName   = s"${selfMember.address.host.getOrElse("noHost")}_${selfMember.address.port.getOrElse(2552)}"

        val metadataCoordinator = system.actorSelection(s"user/guardian_$nodeName/metadata-coordinator_$nodeName")

        awaitAssert {
          metadataCoordinator ! AddLocation("db", "namespace", Location("metric", "node-1", 0, 1))
          expectMsg(LocationAdded("db", "namespace", Location("metric", "node-1", 0, 1)))
        }
      }

      awaitAssert {
        addresses.foreach(a => {
          val metadataActor =
            system.actorSelection(s"user/metadata_${a.host.getOrElse("noHost")}_${a.port.getOrElse(2552)}")
          metadataActor ! GetLocations("db", "namespace", "metric")
          expectMsg(LocationsGot("db", "namespace", "metric", Seq(Location("metric", "node-1", 0, 1))))
        })
      }

      enterBarrier("after-add-locations")
    }

    "add metric info from different nodes" in within(10.seconds) {

      val addresses  = cluster.state.members.filter(_.status == MemberStatus.Up).map(_.address)
      val metricInfo = MetricInfo("metric", 100)

      runOn(node1) {
        val selfMember = cluster.selfMember
        val nodeName   = s"${selfMember.address.host.getOrElse("noHost")}_${selfMember.address.port.getOrElse(2552)}"

        val metadataCoordinator = system.actorSelection(s"user/guardian_$nodeName/metadata-coordinator_$nodeName")

        awaitAssert {
          metadataCoordinator ! PutMetricInfo("db", "namespace", metricInfo)
          expectMsg(MetricInfoPut("db", "namespace", metricInfo))
        }
      }

      awaitAssert {
        addresses.foreach(a => {
          val metadataActor =
            system.actorSelection(s"user/metadata_${a.host.getOrElse("noHost")}_${a.port.getOrElse(2552)}")
          metadataActor ! GetMetricInfo("db", "namespace", "metric")
          expectMsg(MetricInfoGot("db", "namespace", Some(metricInfo)))
        })
      }

      enterBarrier("after-add-metrics-info")
    }
  }
}
