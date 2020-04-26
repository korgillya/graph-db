package sample.cqrs

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import sample.cqrs.Node.{AddRelation, CommandProperty, Confirmation}

import scala.concurrent.ExecutionContextExecutor

object EdgeCreator {
  sealed trait EdgeCommand
  case class CreateEdge(sourceNodeId: String, targetNodeId: String, edgeName: String, properties: List[CommandProperty], replyTo: ActorRef[EdgeCommandReply]) extends EdgeCommand
  case class NodeResponse(response: Confirmation) extends EdgeCommand

  sealed trait EdgeCommandReply
  case class EdgeCreated() extends EdgeCommandReply
  case class EdgeCreationFailed() extends EdgeCommandReply

  def EdgeCreationBehaviour(
                             sharding: ClusterSharding
                           ): Behavior[EdgeCommand] = Behaviors.setup[EdgeCommand] { context =>
    implicit val system: ActorSystem[Nothing] = context.system
    implicit val ex: ExecutionContextExecutor = context.executionContext

  val nodeEntityResponseMapper: ActorRef[Confirmation] =
    context.messageAdapter(rsp => NodeResponse(rsp))

  def waitForEdgeCreation(createEdge: CreateEdge,
                          sourceNodeResp: Boolean,
                          targetNodeResp: Boolean,
                          replyTo: ActorRef[EdgeCreator.EdgeCommandReply]): Behaviors.Receive[EdgeCommand] =
    Behaviors.receiveMessagePartial[EdgeCommand] { message: EdgeCommand =>
      message match {
        case NodeResponse(Node.Accepted(nodeId, _)) =>
          if((createEdge.sourceNodeId == nodeId && targetNodeResp) ||
            createEdge.targetNodeId == nodeId && sourceNodeResp) {
            replyTo ! EdgeCreated()
            Behaviors.stopped
          } else if(createEdge.sourceNodeId == nodeId) {
            waitForEdgeCreation(createEdge, true, false, replyTo)
          } else {
            waitForEdgeCreation(createEdge, false, true, replyTo)
          }
        case _ =>
          replyTo ! EdgeCreationFailed()
          Behaviors.stopped
      }
    }

  def initial(sharding: ClusterSharding) =
      Behaviors.receiveMessagePartial[EdgeCommand] { message: EdgeCommand =>
        message match {
          case cmd@CreateEdge(sourceNodeId, targetNodeId, edgeName, properties, replyTo) => {
            sharding.entityRefFor(Node.EntityKey, sourceNodeId)
              .tell(AddRelation(edgeName, "Source", targetNodeId, properties, nodeEntityResponseMapper))
            sharding.entityRefFor(Node.EntityKey, targetNodeId)
              .tell(AddRelation(edgeName, "Target", sourceNodeId, properties, nodeEntityResponseMapper))

            waitForEdgeCreation(cmd, false, false, replyTo)
          }
        }
      }


    initial(sharding)
  }
}
