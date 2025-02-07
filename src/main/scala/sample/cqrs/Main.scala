package sample.cqrs

import java.io.File
import java.util.concurrent.CountDownLatch

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.typed.Cluster
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object Main {

  def main(args: Array[String]): Unit = {

    args.headOption match {

      case Some(portString) if portString.matches("""\d+""") =>
        val port = portString.toInt
        val httpPort = ("80" + portString.takeRight(2)).toInt
        startNode(port, httpPort)

      case Some("cassandra") =>
        startCassandraDatabase()
        println("Started Cassandra, press Ctrl + C to kill")
        new CountDownLatch(1).await()

      case None =>
        throw new IllegalArgumentException("port number, or cassandra required argument")
    }
  }

  def startNode(port: Int, httpPort: Int): Unit = {
    val system = ActorSystem[Nothing](Guardian(), "GraphDb", config(port, httpPort))
    implicit val session = CassandraSessionRegistry(system).sessionFor("alpakka.cassandra")

    if (Cluster(system).selfMember.hasRole("read-model")) {
      createTables(system, session)

      val settings = EventProcessorSettings(system)
      EventProcessor.init(
        system,
        settings,
        tag => new NodeEventProcessorStream(system, system.executionContext, settings.id, tag))
    }
  }

  def config(port: Int, httpPort: Int): Config =
    ConfigFactory.parseString(s"""
      akka.remote.artery.canonical.port = $port
      graphdb.http.port = $httpPort
       """).withFallback(ConfigFactory.load())

  /**
   * To make the sample easier to run we kickstart a Cassandra instance to
   * act as the journal. Cassandra is a great choice of backend for Akka Persistence but
   * in a real application a pre-existing Cassandra cluster should be used.
   */
  def startCassandraDatabase(): Unit = {
    val databaseDirectory = new File("target/cassandra-db")
    CassandraLauncher.start(databaseDirectory, CassandraLauncher.DefaultTestConfigResource, clean = false, port = 9042)
  }

  def createTables(system: ActorSystem[_], session: CassandraSession): Unit = {

    // TODO use real replication strategy in real application
    val keyspaceStmt = """
      CREATE KEYSPACE IF NOT EXISTS akka_cqrs_sample
      WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }
      """

    val offsetTableStmt =
      """
      CREATE TABLE IF NOT EXISTS akka_cqrs_sample.offsetStore (
        eventProcessorId text,
        tag text,
        timeUuidOffset timeuuid,
        PRIMARY KEY (eventProcessorId, tag)
      )
      """

    val nodesTable =
      """
      CREATE TABLE IF NOT EXISTS akka_cqrs_sample.nodes (
          type text,
          id text,
          properties map<text, text>,
          PRIMARY KEY (type, id)
      )
        """

    val relationsTable =
      """
      CREATE TABLE IF NOT EXISTS akka_cqrs_sample.relations (
          nodeId text,
          relationName text,
          relationType text,
          otherNodeId text,
          properties map<text, text>,
          PRIMARY KEY (nodeId, relationName, relationType, otherNodeId)
      )
        """

    // ok to block here, main thread
    Await.ready(session.executeDDL(keyspaceStmt), 30.seconds)
    Await.ready(session.executeDDL(offsetTableStmt), 30.seconds)
    Await.ready(session.executeDDL(nodesTable), 30.seconds)
    Await.ready(session.executeDDL(relationsTable), 30.seconds)
  }

}

object Guardian {
  def apply(): Behavior[Nothing] = {
    Behaviors.setup[Nothing] { context =>
      val system = context.system
      val settings = EventProcessorSettings(system)
      val httpPort = context.system.settings.config.getInt("graphdb.http.port")

      Node.init(system, settings)

      val session: CassandraSession = CassandraSessionRegistry(system).sessionFor("alpakka.cassandra")

      val routes = new NodeRoutes(session)(context, context.system)
      new GraphDBServer(routes.nodes, httpPort, context.system).start()

      Behaviors.empty
    }
  }
}
