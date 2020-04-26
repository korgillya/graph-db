package sample.cqrs


import java.util.UUID

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import akka.util.Timeout
import sample.cqrs.EdgeCreator.CreateEdge
import sample.cqrs.GraphQueryActor._
import sample.cqrs.Node.CommandProperty
import sample.cqrs.NodeRoutes.{AddProperty, ApiGraphQuery, DeleteProperty}
import spray.json.{JsBoolean, JsNumber, JsString, JsValue, JsonFormat}

import scala.concurrent.Future

object NodeRoutes {

  final case class Prop(name: String, `type`: String, value: Option[String])

  final case class AddNode(nodeId: String, nodeType: String, properties: List[Prop])

  final case class AddRelation(sourceId: String, targetId: String, name: String, properties: List[Prop])

  final case class ApiGraphQuery(startNode: QueryNode, relation: Option[Relation], targetNode: Option[QueryNode])

  final case class AddProperty(nodeId: String, propertyType: String, propertyName: String, propertyValue: String)

  final case class DeleteProperty(nodeId: String, propertyName: String)

}

class NodeRoutes(session: CassandraSession)(implicit context: ActorContext[Nothing], system: ActorSystem[_]) {

  implicit private val timeout: Timeout =
    Timeout.create(system.settings.config.getDuration("graphdb.askTimeout"))
  private val sharding: ClusterSharding = ClusterSharding(system)

  import JsonFormats._
  import NodeRoutes._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.server.Directives._

  val nodes: Route = pathPrefix("graphdb") {
    pathPrefix("node") {
      post {
        entity(as[AddNode]) { data =>
          val entityRef = sharding.entityRefFor(Node.EntityKey, data.nodeId)
          val reply: Future[Node.Confirmation] =
            entityRef.ask(Node.AddNode(data.nodeType, data.properties.map(el => CommandProperty(el.name, el.`type`, el.value)), _))
          onSuccess(reply) {
            case Node.Accepted(_, summary) =>
              complete(StatusCodes.OK -> summary)
            case Node.Rejected(reason) =>
              complete(StatusCodes.BadRequest, reason)
          }
        }
      } ~ pathPrefix("attribute") {
        post {
          entity(as[AddProperty]) { data =>
            val entityRef = sharding.entityRefFor(Node.EntityKey, data.nodeId)
            val reply: Future[Node.Confirmation] =
              entityRef.ask(Node.AddProperty(data.propertyType, data.propertyName, Some(data.propertyValue), _))
            onSuccess(reply) {
              case Node.Accepted(_, summary) =>
                complete(StatusCodes.OK -> summary)
              case Node.Rejected(reason) =>
                complete(StatusCodes.BadRequest, reason)
            }
          }
        } ~ delete {
          entity(as[DeleteProperty]) { data =>
            val entityRef = sharding.entityRefFor(Node.EntityKey, data.nodeId)
            val reply: Future[Node.Confirmation] =
              entityRef.ask(Node.DeleteProperty(data.propertyName, _))
            onSuccess(reply) {
              case Node.Accepted(_, summary) =>
                complete(StatusCodes.OK -> summary)
              case Node.Rejected(reason) =>
                complete(StatusCodes.BadRequest, reason)
            }
          }
        }
      }
    } ~ pathPrefix("edge") {
      post {
        entity(as[AddRelation]) { data =>

          val edgeCreationBehaviour =
            Behaviors
              .supervise(EdgeCreator.EdgeCreationBehaviour(sharding))
              .onFailure[IllegalStateException](SupervisorStrategy.resume)

          val edgeCreator: ActorRef[EdgeCreator.EdgeCommand] =
            context.spawn(edgeCreationBehaviour, data.sourceId + data.targetId + data.name)
          val reply: Future[EdgeCreator.EdgeCommandReply] =
            edgeCreator.ask(CreateEdge(data.sourceId, data.targetId, data.name,
              data.properties.map(el => CommandProperty(el.name, el.`type`, el.value)), _))
          onSuccess(reply) {
            case EdgeCreator.EdgeCreated() =>
              complete(StatusCodes.OK)
            case EdgeCreator.EdgeCreationFailed() =>
              complete(StatusCodes.BadRequest)
          }
        }
      }
    } ~ pathPrefix("match") {
      post {
        entity(as[ApiGraphQuery]) { data =>
          if ((data.relation.isDefined && data.targetNode.isEmpty) ||
            (data.relation.isEmpty && data.targetNode.isDefined)) {
            complete(StatusCodes.BadRequest -> "wrong request")
          } else {
            val queryBehaviour =
              Behaviors
                .supervise(GraphQueryActor.GraphQueryBehaviour()(session))
                .onFailure[IllegalStateException](SupervisorStrategy.resume)
            val queryActor: ActorRef[GraphQuery] =
              context.spawn(queryBehaviour, UUID.randomUUID().toString)
            val query: Future[GraphReply] =
              queryActor.ask(PatternGraphQuery(data.startNode, data.relation, data.targetNode, _))
            onSuccess(query) {
              case reply: GraphNodesReply => complete(StatusCodes.OK -> reply)
              case fail: FailNodesReply => complete(StatusCodes.BadRequest -> fail)
            }
          }
        }
      }
    }
  }

}

object JsonFormats {

  import spray.json.RootJsonFormat
  // import the default encoders for primitive types (Int, String, Lists etc)
  import spray.json.DefaultJsonProtocol._

  implicit object AnyJsonFormat extends JsonFormat[Any] {

    def write(x: Any) =
      try {
        x match {
          case n: Int => JsNumber(n)
          case s: String => JsString(s)
          case c: Char => JsString(c.toString)
          case b: Boolean => JsBoolean(b)
          case _ => JsString("null")
        }
      } catch {
        case _: NullPointerException => JsString("null")
      }

    def read(value: JsValue) = ???
  }

  implicit val propFormat: RootJsonFormat[NodeRoutes.Prop] = jsonFormat3(NodeRoutes.Prop)
  implicit val addNodeFormat: RootJsonFormat[NodeRoutes.AddNode] = jsonFormat3(NodeRoutes.AddNode)
  implicit val addRelationFormat: RootJsonFormat[NodeRoutes.AddRelation] = jsonFormat4(NodeRoutes.AddRelation)
  implicit val summaryFormat: RootJsonFormat[Node.NodeSummary] = jsonFormat4(Node.NodeSummary)

  implicit val nodeReplyFormat: RootJsonFormat[GraphNodesReply] = jsonFormat1(GraphNodesReply)
  implicit val failReplyFormat: RootJsonFormat[FailNodesReply] = jsonFormat1(FailNodesReply)

  implicit val queryNodeFormat: JsonFormat[QueryNode] = jsonFormat3(QueryNode)
  implicit val relationFormat: JsonFormat[Relation] = jsonFormat1(Relation)
  implicit val apiGraphQueryFormat: RootJsonFormat[ApiGraphQuery] = jsonFormat3(ApiGraphQuery)

  implicit val addPropertyFormat: RootJsonFormat[AddProperty] = jsonFormat4(AddProperty)
  implicit val deletePropertyFormat: RootJsonFormat[DeleteProperty] = jsonFormat2(DeleteProperty)
}
