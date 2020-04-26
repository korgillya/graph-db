package sample.cqrs

import java.util.UUID

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSource}
import akka.stream.scaladsl.Sink
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import sample.cqrs.GraphQueryHandler._
import sample.cqrs.Node.NodeSummary

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

object GraphQueryActor {
  sealed trait GraphQuery

  case class NodesInformation(nodes: Set[String]) extends GraphQuery
  case class NodesInformationFailed(error: String) extends GraphQuery
  case class NodesReply(matches: List[NodeSummary]) extends GraphQuery
  case class FailQueryReply(reason: String) extends GraphQuery


  final case class QueryNode(nodeType: String, properties: Map[String, String], needReturn: Boolean)
  case class Relation(name: String)
  case class PatternGraphQuery(startNode: QueryNode, relation: Option[Relation], targetNode: Option[QueryNode], replyTo: ActorRef[GraphReply]) extends GraphQuery

  sealed trait GraphReply
  case class GraphNodesReply(matches: List[NodeSummary]) extends GraphReply
  case class FailNodesReply(reason: String) extends GraphReply

  def GraphQueryBehaviour()(implicit session: CassandraSession): Behavior[GraphQuery] =  Behaviors.setup[GraphQuery] { context =>
    implicit val system: ActorSystem[Nothing] = context.system
    implicit val ex: ExecutionContextExecutor = context.executionContext

    val queryMapper: ActorRef[QueryReply] =
      context.messageAdapter(rsp => {
        rsp match {
          case NodeReply(matches) => NodesReply(matches)
          case GraphQueryHandler.FailReply(reason) => FailQueryReply(reason)
        }
      })

    def waitQueryResult(nodes: Set[String], errorAccumulator: Set[String], responseAccumulator: List[NodeSummary],responsesCounter: Int,
                        replyTo: ActorRef[GraphReply]): Behavior[GraphQuery] =
      Behaviors.receiveMessagePartial {
        case NodesReply(matches) =>
          if((responsesCounter + 1) == nodes.size) {
            replyTo ! GraphNodesReply(responseAccumulator ++ matches)
            Behaviors.stopped
          } else waitQueryResult(nodes, errorAccumulator, responseAccumulator ++ matches, responsesCounter + 1, replyTo)
        case FailQueryReply(reason) =>
          if((responsesCounter + 1) == nodes.size) {
            replyTo ! FailNodesReply((errorAccumulator + reason).mkString("\n"))
            Behaviors.stopped
          } else waitQueryResult(nodes, errorAccumulator + reason, responseAccumulator, responsesCounter + 1, replyTo)

      }

    def nodesInfo(graphQuery: PatternGraphQuery, replyTo: ActorRef[GraphReply]): Behavior[GraphQuery] =
      Behaviors.receiveMessagePartial {
        case NodesInformation(nodes) =>
          nodes.foreach { nodeId =>
            val fromNodeMatch = FromNodeMatch(graphQuery.startNode.nodeType, nodeId, graphQuery.startNode.properties, graphQuery.startNode.needReturn)
            val relationMatch = graphQuery.relation.map(rel => RelationMatch(rel.name, "-"))
            val toNodeMatch = graphQuery.targetNode.map(tar => ToNodeMatch(tar.nodeType, tar.properties, tar.needReturn))
            val nodeRequest = NodeRequest(fromNodeMatch, relationMatch, toNodeMatch)

            val queryBehaviour =
              Behaviors
                .supervise(GraphQueryHandler.GraphQueryBehaviour(List.empty)(session))
                .onFailure[IllegalStateException](SupervisorStrategy.resume)
            val queryActor: ActorRef[Query] =
              context.spawn(queryBehaviour, UUID.randomUUID().toString)

            queryActor.tell(PatternQuery(List(nodeRequest), queryMapper))

          }

          waitQueryResult(nodes, Set.empty, List.empty, 0, replyTo)

        case NodesInformationFailed(e) =>
          replyTo ! FailNodesReply(e)
          Behaviors.stopped
      }

    def waitForQuery: Behaviors.Receive[GraphQuery] =
      Behaviors.receiveMessagePartial[GraphQuery] { message: GraphQuery =>
        message match {
          case query@PatternGraphQuery(startNode, relation, targetNode, replyTo) =>

            val stmt = SimpleStatement.newInstance(s"SELECT * FROM akka_cqrs_sample.nodes WHERE type = '${startNode.nodeType}'")
            val nodes = CassandraSource(stmt)
              .map(_.getString("id"))
              .runWith(Sink.seq)

            context.pipeToSelf(nodes) {
              case Success(x) =>
                NodesInformation(x.toSet)
              case Failure(e) =>
                NodesInformationFailed(e.getMessage)
            }

            nodesInfo(query, replyTo)
        }
      }

    waitForQuery
  }
}
