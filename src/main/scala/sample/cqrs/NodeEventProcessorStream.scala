package sample.cqrs

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.PersistenceId
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import com.datastax.oss.driver.api.core.cql.{BoundStatement, PreparedStatement}
import sample.cqrs.Node.{NodeUpdated, RelationAdded}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class NodeEventProcessorStream(
                                        system: ActorSystem[_],
                                        executionContext: ExecutionContext,
                                        eventProcessorId: String,

                                        tag: String)(implicit override val session: CassandraSession)
  extends EventProcessorStream[Node.Event](system, executionContext, eventProcessorId, tag) {

  val nodeInsertStatement: String = s"INSERT INTO akka_cqrs_sample.nodes(type, id, properties) VALUES (?, ?, ?)"

  val nodeInsertBinder: (NodeUpdated, PreparedStatement) => BoundStatement =
    (e: NodeUpdated, statement: PreparedStatement) => {
      statement.bind(
        e.nodeType,
        e.nodeId,
        if(e.properties.isEmpty) null else e.properties.map(el => el.name -> el.value.orNull).toMap.asJava
      )
    }

  val relationInsertStatement: String =
    s"INSERT INTO akka_cqrs_sample.relations(nodeId, relationName, relationType, otherNodeId, properties) VALUES (?, ?, ?, ?, ?)"

  val relationInsertBinder: (RelationAdded, PreparedStatement) => BoundStatement =
    (e: RelationAdded, statement: PreparedStatement) => {
      statement.bind(
        e.nodeId,
        e.relationName,
        e.relationDirection,
        e.relationNodeId,
        if(e.properties.isEmpty) null else e.properties.map(el => el.name -> el.value.orNull).toMap.asJava
      )
    }

  def processEvent(event: Node.Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done] = {
    log.info("EventProcessor({}) consumed {} from {} with seqNr {}", tag, event, persistenceId, sequenceNr)
    system.eventStream ! EventStream.Publish(event)

    event match {
      case updated: NodeUpdated =>
        val prepare = session.prepare(nodeInsertStatement)
        prepare.map { preparedStatement =>
            session
              .executeWrite(nodeInsertBinder(updated, preparedStatement))
        }.flatten
      case relationAdded: RelationAdded =>
        val prepare = session.prepare(relationInsertStatement)
        prepare.map { preparedStatement =>
          session
            .executeWrite(relationInsertBinder(relationAdded, preparedStatement))
        }.flatten
      case _ =>
        log.error("unsupported evt")
        Future.successful(Done)
    }

  }
}
