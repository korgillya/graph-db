package sample.cqrs

import java.util.UUID

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSource}
import akka.stream.scaladsl.Sink
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import sample.cqrs.Node.NodeSummary

import scala.concurrent.ExecutionContextExecutor
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

object GraphQueryHandler {

  sealed trait Query

  case class FromNodeMatch(`type`: String, id: String, propertiesMatch: Map[String, String], needReturn: Boolean)

  case class ToNodeMatch(`type`: String, propertiesMatch: Map[String, String], needReturn: Boolean)

  case class RelationMatch(name: String, relationDirection: String)

  case class NodeRequest(fromNodeQuery: FromNodeMatch, relationQuery: Option[RelationMatch], toNodeQuery: Option[ToNodeMatch])

  case class PatternQuery(pattern: List[NodeRequest], replyTo: ActorRef[QueryReply]) extends Query


  case class NodeInfo(nodeType: String, nodeId: String, properties: Map[String, String]) extends Query

  case class RelationInfo(nodeId: String, relationName: String, relationType: String, otherNodeId: String, properties: Map[String, String]) extends Query

  case class RelationsInfo(relations: Seq[RelationInfo]) extends Query

  case class NodeInfoFailed(error: String) extends Query

  case class RelationInfoFailed(error: String) extends Query

  case class DownNodeSuccessReply(nodeReply: NodeReply) extends Query

  case class DownNodeFailReply(failReply: FailReply) extends Query


  sealed trait QueryReply

  case class NodeReply(matches: List[NodeSummary]) extends QueryReply

  case class FailReply(reason: String) extends QueryReply

  def GraphQueryBehaviour(queryResultAccumulator: List[NodeSummary])(implicit session: CassandraSession): Behavior[Query] = Behaviors.setup[Query] { context =>
    implicit val system: ActorSystem[Nothing] = context.system
    implicit val ex: ExecutionContextExecutor = context.executionContext


    val queryMapper: ActorRef[QueryReply] =
      context.messageAdapter {
        case nodeRep: NodeReply => DownNodeSuccessReply(nodeRep)
        case failRep: FailReply => DownNodeFailReply(failRep)
      }

    def waitQueryResult(needCount: Int, counter: Int, errorAccumulator: Set[String], responseAccumulator: List[NodeSummary],
                        replyTo: ActorRef[QueryReply]): Behavior[Query] =
      Behaviors.receiveMessagePartial {
        case DownNodeSuccessReply(matches) =>
          if ((counter + 1) == needCount) {
            replyTo ! NodeReply((queryResultAccumulator ++ responseAccumulator ++ matches.matches).distinct)
            Behaviors.stopped
          } else {
            waitQueryResult(needCount, counter + 1, errorAccumulator, responseAccumulator ++ matches.matches, replyTo)
          }
        case DownNodeFailReply(failReply) =>
          if ((counter + 1) == needCount) {
            replyTo ! FailReply((errorAccumulator + failReply.reason).mkString("\n"))
            Behaviors.stopped
          } else waitQueryResult(needCount, counter + 1, errorAccumulator + failReply.reason, responseAccumulator, replyTo)

      }

    def spawnChild(relationQuery: RelationMatch, relations: RelationsInfo, toNodeQuery: ToNodeMatch, matchedNode: Option[NodeReply], replyTo: ActorRef[QueryReply]) = {
      val directions = if (relationQuery.relationDirection == "-") List("Source", "Target") else List(relationQuery.relationDirection)
      val relationRes = relations.relations.filter(rel => directions.contains(rel.relationType) &&
        rel.relationName == relationQuery.name)

      relationRes.foreach(rel => {
        val queryBehaviour =
          Behaviors
            .supervise(GraphQueryHandler.GraphQueryBehaviour(queryResultAccumulator ++ matchedNode.map(_.matches).getOrElse(List.empty) )(session))
            .onFailure[IllegalStateException](SupervisorStrategy.resume)
        val queryActor: ActorRef[Query] =
          context.spawn(queryBehaviour, UUID.randomUUID().toString)

        val fromNodeMatch = FromNodeMatch(toNodeQuery.`type`, rel.otherNodeId, toNodeQuery.propertiesMatch, toNodeQuery.needReturn)
        val modifiedRequest = NodeRequest(fromNodeMatch, None, None)
        queryActor.tell(PatternQuery(List(modifiedRequest), queryMapper))
      })

      if(relationRes.nonEmpty){
        waitQueryResult(relationRes.size, 0, Set.empty, List.empty, replyTo)
      } else {
        replyTo ! NodeReply(List.empty)
        Behaviors.stopped[Query]
      }

    }

    def relationsInfo(query: PatternQuery, node: NodeInfo, replyTo: ActorRef[QueryReply]): Behavior[Query] =
      Behaviors.receiveMessagePartial {
        case relations: RelationsInfo =>

          //todo unsafe, fix it
          val currentQuery = query.pattern.head

          currentQuery match {
            case nodeRequest@NodeRequest(fromNodeQuery: FromNodeMatch, Some(relationQuery: RelationMatch), Some(toNodeQuery: ToNodeMatch)) =>

              val nonExistentProps = currentQuery.fromNodeQuery.propertiesMatch.filter(el => !node.properties.contains(el._1))
              if (nonExistentProps.nonEmpty) {
                replyTo ! FailReply(s"next props are not found: [${nonExistentProps.keys.mkString(",")}] for type ${node.nodeType}")
                Behaviors.stopped
              } else {
                val notMatched = currentQuery.fromNodeQuery.propertiesMatch.filter(q => node.properties(q._1) != q._2)
                if(notMatched.nonEmpty) {
                  replyTo ! NodeReply(List.empty)
                  Behaviors.stopped
                } else if(!fromNodeQuery.needReturn){
                  spawnChild(relationQuery, relations, toNodeQuery, None, replyTo)
                } else {
                  val matched = NodeReply(List(NodeSummary(node.nodeId, node.nodeType, node.properties,
                    relations.relations.map(el => (el.relationName, el.relationType, el.otherNodeId)).toList)))
                  spawnChild(relationQuery, relations, toNodeQuery, Some(matched), replyTo)
                }
              }


            case NodeRequest(fromNodeQuery: FromNodeMatch, None, None) =>
              val nonExistentProps = currentQuery.fromNodeQuery.propertiesMatch.filter(el => !node.properties.contains(el._1))
              val reply = if (nonExistentProps.nonEmpty) {
                FailReply(s"next props are not found: [${nonExistentProps.keys.mkString(",")}] for type ${node.nodeType}")
              } else {
                val notMatched = currentQuery.fromNodeQuery.propertiesMatch.filter(q => node.properties(q._1) != q._2)
                if (notMatched.nonEmpty) {
                  NodeReply(List.empty)
                } else if(!fromNodeQuery.needReturn){
                  NodeReply(queryResultAccumulator)
                } else {
                  NodeReply(queryResultAccumulator ++ List(NodeSummary(node.nodeId, node.nodeType, node.properties,
                    relations.relations.map(el => (el.relationName, el.relationType, el.otherNodeId)).toList)))
                }
              }
              replyTo ! reply
              Behaviors.stopped
          }
      }

    def nodeInfo(query: PatternQuery, replyTo: ActorRef[QueryReply]): Behavior[Query] =
      Behaviors.receiveMessagePartial {
        case node: NodeInfo =>

          val stmt = SimpleStatement.newInstance(s"SELECT * FROM akka_cqrs_sample.relations WHERE nodeId = '${node.nodeId}'")
          val relations = CassandraSource(stmt)
            .map(el => RelationInfo(el.getString("nodeId"),
              el.getString("relationName"),
              el.getString("relationType"),
              el.getString("otherNodeId"),
              el.getMap("properties",
                classOf[String], classOf[String]).asScala.toMap))
            .runWith(Sink.seq)

          context.pipeToSelf(relations) {
            case Success(x) =>
              RelationsInfo(x)
            case Failure(e) =>
              NodeInfoFailed("not found")
          }

          relationsInfo(query, node, replyTo)
      }

    def waitForQuery: Behaviors.Receive[Query] =
      Behaviors.receiveMessagePartial[Query] {
        case query@PatternQuery(_, replyTo) =>
          val currNode = query.pattern.head.fromNodeQuery
          val stmt = SimpleStatement.newInstance(s"SELECT * FROM akka_cqrs_sample.nodes WHERE type = '${currNode.`type`}' AND id = '${currNode.id}'")
          val nodes = CassandraSource(stmt)
            .map(el => NodeInfo(el.getString("type"),
              el.getString("id"),
              el.getMap("properties",
                classOf[String], classOf[String]).asScala.toMap))
            .runWith(Sink.seq)

          context.pipeToSelf(nodes) {
            case Success(x) =>
              x.headOption.map(n => NodeInfo(n.nodeType, n.nodeId, n.properties)).getOrElse(NodeInfoFailed("not found"))
            case Failure(e) =>
              NodeInfoFailed("not found")
          }
          nodeInfo(query, replyTo)
      }

    waitForQuery
  }
}
