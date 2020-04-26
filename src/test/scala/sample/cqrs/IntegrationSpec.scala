package sample.cqrs


import java.io.File
import java.util.UUID

import akka.actor.ActorSystem
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.cluster.MemberStatus
import akka.cluster.typed.{Cluster, Join}
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, TestSuite}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object IntegrationSpec {
  val config: Config = ConfigFactory.parseString(s"""
      akka.cluster {
         seed-nodes = []
      }

      akka.persistence.cassandra {
        events-by-tag {
          eventual-consistency-delay = 200ms
        }

        query {
          refresh-interval = 500 ms
        }

        journal.keyspace-autocreate = on
        journal.tables-autocreate = on
        snapshot.keyspace-autocreate = on
        snapshot.tables-autocreate = on
      }
      datastax-java-driver {
        basic.contact-points = ["127.0.0.1:19042"]
        basic.load-balancing-policy.local-datacenter = "datacenter1"
      }

      event-processor {
        keep-alive-interval = 1 seconds
      }
      akka.loglevel = DEBUG
      akka.actor.testkit.typed.single-expect-default = 5s
      # For LoggingTestKit
      akka.actor.testkit.typed.filter-leeway = 5s
      akka.actor.testkit.typed.throw-on-shutdown-timeout = off
    """).withFallback(ConfigFactory.load())
}

class IntegrationSpec
  extends TestSuite
    with Matchers
    with BeforeAndAfterAll
    with AnyWordSpecLike
    with ScalaFutures
    with Eventually {

  implicit private val patience: PatienceConfig =
    PatienceConfig(3.seconds, Span(100, org.scalatest.time.Millis))

  private val databaseDirectory = new File("target/cassandra-IntegrationSpec")

  private def conf(role: String, port: Int, httpPort: Int): Config =
    ConfigFactory.parseString(
      s"""akka.cluster.roles = [$role]
          akka.remote.artery.canonical.port = $port
          graphdb.http.port = $httpPort
         """)

  // one TestKit (ActorSystem) per cluster node
  private val testKit1 =
    ActorTestKit("IntegrationSpec", conf("write-model", 2560, 8060).withFallback(IntegrationSpec.config))
  private val testKit2 =
    ActorTestKit("IntegrationSpec", conf("write-model", 2561, 8061).withFallback(IntegrationSpec.config))
  private val testKit3 =
    ActorTestKit("IntegrationSpec", conf("read-model", 2562, 8062).withFallback(IntegrationSpec.config))
  private val testKit4 =
    ActorTestKit("IntegrationSpec", conf("read-model", 2563, 8063).withFallback(IntegrationSpec.config))

  private val systems4 = List(testKit1.system, testKit2.system, testKit3.system, testKit4.system)

  private val classicSystem = ActorSystem("ClassicActorSystem")

  override protected def beforeAll(): Unit = {
    CassandraLauncher.start(
      databaseDirectory,
      CassandraLauncher.DefaultTestConfigResource,
      clean = true,
      port = 19042, // default is 9042, but use different for test
      CassandraLauncher.classpathForResources("logback-test.xml"))

    // avoid concurrent creation of keyspace and tables
    initializePersistence()
    val session = CassandraSessionRegistry(testKit1.system).sessionFor("alpakka.cassandra")

    Main.createTables(testKit1.system, session)

    super.beforeAll()
  }

  // FIXME use Akka's initializePlugins instead when released https://github.com/akka/akka/issues/28808
  private def initializePersistence(): Unit = {
    val persistenceId = PersistenceId.ofUniqueId(s"persistenceInit-${UUID.randomUUID()}")
    val ref = testKit1.spawn(
      EventSourcedBehavior[String, String, String](
        persistenceId,
        "",
        commandHandler = (_, _) => Effect.stop(),
        eventHandler = (_, _) => ""))
    ref ! "start"
    testKit1.createTestProbe().expectTerminated(ref, 20.seconds)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()

    testKit4.shutdownTestKit()
    testKit3.shutdownTestKit()
    testKit2.shutdownTestKit()
    testKit1.shutdownTestKit()

    CassandraLauncher.stop()
    FileUtils.deleteDirectory(databaseDirectory)
  }

  "GraphDb application" should {
    "init and join Cluster" in {
      testKit1.spawn[Nothing](Guardian(), "guardian")
      testKit2.spawn[Nothing](Guardian(), "guardian")
      testKit3.spawn[Nothing](Guardian(), "guardian")
      testKit4.spawn[Nothing](Guardian(), "guardian")

      systems4.foreach { sys =>
        Cluster(sys).manager ! Join(Cluster(testKit1.system).selfMember.address)
      }

      // let the nodes join and become Up
      eventually(PatienceConfiguration.Timeout(10.seconds)) {
        systems4.foreach { sys =>
          Cluster(sys).selfMember.status should ===(MemberStatus.Up)
        }
      }
    }
  }
}
