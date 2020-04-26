package sample.cqrs

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}

import scala.concurrent.duration._

object Node {

  sealed trait RelationDirection {
    val nodeId: String
  }

  final case class Source(override val nodeId: String) extends RelationDirection

  final case class Target(override val nodeId: String) extends RelationDirection

  final case class Relation(name: String, direction: RelationDirection, properties: Set[Property])

  final case class Property(propertyType: String, propertyName: String, propertyValue: Option[String])

  final case class State(nodeId: String,
                         nodeType: String,
                         properties: Set[Property],
                         relations: List[Relation]) extends CborSerializable {
    def updateNode(nodeType: String, properties: Set[Property]): State = {
      copy(nodeType = nodeType, properties = properties)
    }

    def addProperty(prop: Property): State = {
      copy(properties = properties + prop)
    }

    def deleteProperty(propertyName: String): State = {
      copy(properties = properties.filterNot(_.propertyName == propertyName))
    }

    def addRelation(rel: Relation): State = {
      copy(relations = relations :+ rel)
    }

    def toSummary: NodeSummary =
      NodeSummary(nodeId, nodeType,
        properties.map(el => el.propertyName -> el.propertyValue.getOrElse("")).toMap,
        relations.map(el => (el.name, el.direction.getClass.getSimpleName,  el.direction.nodeId)))
  }

  object State {
    val empty = State(nodeId = "", nodeType = "", properties = Set.empty, relations = List.empty)
  }


  sealed trait Repliable {
    val replyTo: ActorRef[Confirmation]
  }

  sealed trait Command extends CborSerializable
  case class CommandRelation(name: String, direction: String, nodeId: String) extends CborSerializable
  case class CommandProperty(name: String, `type`: String, value: Option[String]) extends CborSerializable
  case class AddNode(nodeType: String, properties: List[CommandProperty], replyTo: ActorRef[Confirmation]) extends Command
  case class AddProperty(propType: String, propName: String, propValue: Option[String], replyTo: ActorRef[Confirmation]) extends Command
  case class DeleteProperty(propName: String, replyTo: ActorRef[Confirmation]) extends Command
  case class AddRelation(name: String, direction: String, directionNodeId: String, properties: List[CommandProperty], replyTo: ActorRef[Confirmation]) extends Command

  final case class NodeSummary(nodeId: String, nodeType: String, properties: Map[String, Any], relations: List[Tuple3[String, String, String]]) extends CborSerializable

  sealed trait Confirmation extends CborSerializable
  final case class Accepted(nodeId: String, summary: NodeSummary) extends Confirmation
  final case class Rejected(reason: String) extends Confirmation


  sealed trait Event extends CborSerializable {
    def nodeId: String
  }

  case class NodeUpdated(nodeId: String, nodeType: String, properties: List[CommandProperty]) extends Event
  case class RelationAdded(nodeId: String,
                           relationName: String,
                           relationDirection: String,
                           properties: List[CommandProperty],
                           relationNodeId: String) extends Event

  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Node")

  def init(system: ActorSystem[_], eventProcessorSettings: EventProcessorSettings): ActorRef[ShardingEnvelope[Command]] = {
    ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
      val n = math.abs(entityContext.entityId.hashCode % eventProcessorSettings.parallelism)
      val eventProcessorTag = eventProcessorSettings.tagPrefix + "-" + n
      Node(entityContext.entityId, Set(eventProcessorTag))
    }.withRole("write-model"))
  }

  def apply(nodeId: String, eventProcessorTags: Set[String]): Behavior[Command] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State](
        PersistenceId(EntityKey.name, nodeId),
        State.empty,
        (state, command) =>
          if (state.nodeType.isEmpty) nodeCreation(nodeId, state, command)
          else nodeModification(nodeId, state, command),
        (state, event) => handleEvent(state, event))
      .withTagger(_ => eventProcessorTags)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }

  private def nodeCreation(nodeId: String, state: State, command: Command): ReplyEffect[Event, State] =
    command match {
      case AddNode(nodeType, properties,replyTo) =>
        Effect
          .persist(NodeUpdated(nodeId, nodeType, properties))
          .thenReply(replyTo)(added => Accepted(nodeId, added.toSummary))
      case cmd: Repliable => //todo more specific errors
        Effect.reply(cmd.replyTo)(Rejected("Unavailable operation"))
    }

  private def nodeModification(nodeId: String, state: State, command: Command): ReplyEffect[Event, State] =
    command match {
      case AddNode(nodeType, properties, replyTo) =>
        Effect.reply(replyTo)(Accepted(nodeId, state.toSummary))
      case AddProperty(propType, propName, propValue, replyTo) =>
        if(state.nodeType.isEmpty) {
          Effect.reply(replyTo)(Rejected(s"node ${state.nodeId} doesn't exist"))
        } else {
          val props = state.properties.map(el => CommandProperty(el.propertyType, el.propertyName, el.propertyValue)).toList ++
          List(CommandProperty(propName, propType, propValue))
          Effect
          .persist(NodeUpdated(nodeId, state.nodeType, props))
            .thenReply(replyTo)(_ => Accepted(nodeId, state.toSummary))
        }
      case DeleteProperty(propName, replyTo) =>
        if(state.nodeType.isEmpty) {
          Effect.reply(replyTo)(Rejected(s"node ${state.nodeId} doesn't exist"))
        } else {
          val props = state.properties.map(el => CommandProperty(el.propertyType, el.propertyName, el.propertyValue)).toList
          Effect
            .persist(NodeUpdated(nodeId, state.nodeType, props.filterNot(_.name == propName)))
            .thenReply(replyTo)(_ => Accepted(nodeId, state.toSummary))
        }
      case AddRelation(name, direction, directionNodeId, properties, replyTo) =>
        Effect
          .persist(RelationAdded(nodeId, name, direction, properties, directionNodeId))
          .thenReply(replyTo)(_ => Accepted(nodeId, state.toSummary))
    }

  private def handleEvent(state: State, event: Event): State = {
    val direction = (dir: String, nodeId: String) => dir match {
      case "Source" => Source(nodeId)
      case "Target" => Target(nodeId)
    }
    event match {
      case NodeUpdated(_, nodeType, properties) =>
        state.updateNode(nodeType,
          properties.map(el => Property(el.`type`, el.name, el.value)).toSet)
      case RelationAdded(_, relationName, relationDirection, properties, directionNodeId) =>
        state.addRelation(Relation(relationName, direction(relationDirection, directionNodeId),
          properties.map(el => Property(el.`type`, el.name, el.value)).toSet))
    }
  }
}
