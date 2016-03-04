/*
 * Copyright 2015 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.heikoseeberger.reactiveflows

import de.knutwalker.akka.typed._
import de.knutwalker.union.|

import akka.cluster.Cluster
import akka.cluster.ddata.{ LWWMap, LWWMapKey, Replicator }
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.cluster.pubsub.{ DistributedPubSubMessage, DistributedPubSubMediator }
import de.heikoseeberger.reactiveflows.FlowFacade.FlowDescriptor

import java.net.URLEncoder

object FlowFacade {

  sealed trait FlowEvent
  sealed trait FlowCommand
  sealed trait FlowReply

  case class GetFlows(replyTo: ActorRef[Set[FlowDescriptor]]) extends FlowCommand
  case class FlowDescriptor(name: String, label: String)

  case class AddFlow(label: String)(val replyTo: ActorRef[FlowReply]) extends FlowCommand
  case class FlowAdded(flowDescriptor: FlowDescriptor) extends FlowEvent with FlowReply
  case class FlowExists(label: String) extends FlowReply

  case class RemoveFlow(name: String)(val replyTo: ActorRef[FlowReply]) extends FlowCommand
  case class FlowRemoved(name: String) extends FlowEvent with FlowReply
  case class FlowUnknown(name: String) extends FlowReply

  case class GetMessages(flowName: String)(val replyTo: ActorRef[FlowReply]) extends FlowCommand
  case class FoundMessages(messages: Vector[Flow.Message]) extends FlowReply
  case class AddMessage(flowName: String, text: String)(val replyTo: ActorRef[FlowReply]) extends FlowCommand
  case class MessageAdded(flowName: String, message: Flow.Message) extends FlowReply

  // $COVERAGE-OFF$
  final val Name = "flow-facade"
  // $COVERAGE-ON$

  private[reactiveflows] val flowReplicatorKey = LWWMapKey[FlowDescriptor]("flows")

  private val updateFlowData = Replicator.Update(flowReplicatorKey, LWWMap.empty[FlowDescriptor], Replicator.WriteLocal) _

  def props(mediator: ActorRef[DistributedPubSubMessage | Subscribe], replicator: ActorRef[Replicator.Subscribe[LWWMap[FlowDescriptor]] | Replicator.Update[LWWMap[FlowDescriptor]]], flowShardRegion: ActorRef[(String, Flow.MessageCommand)]): Props[FlowCommand] =
    PropsFor(new FlowFacade(mediator, replicator, flowShardRegion)).only[FlowCommand]

  private def labelToName(label: String) = URLEncoder.encode(label.toLowerCase, "UTF-8")
}

class FlowFacade(
    mediator: ActorRef[DistributedPubSubMessage | Subscribe],
    replicator: ActorRef[Replicator.Subscribe[LWWMap[FlowDescriptor]] | Replicator.Update[LWWMap[FlowDescriptor]]],
    flowShardRegion: ActorRef[(String, Flow.MessageCommand)]
) extends TypedActor.Of[FlowFacade.FlowCommand | Replicator.Changed[LWWMap[FlowDescriptor]]] {
  import FlowFacade._

  private implicit val cluster = Cluster(context.system)

  private var flowsByName = Map.empty[String, FlowDescriptor]

  replicator ! Replicator.Subscribe(flowReplicatorKey, self)

  def typedReceive: TypedReceive = TotalUnion {
    case GetFlows(sender)                                  => sender ! flowsByName.valuesIterator.to[Set]
    case m @ AddFlow(label)                                => addFlow(label, m.replyTo)
    case m @ RemoveFlow(name)                              => removeFlow(name, m.replyTo)
    case m @ GetMessages(flowName)                         => getMessages(flowName, m.replyTo)
    case m @ AddMessage(flowName, text)                    => addMessage(flowName, text, m.replyTo)
    case changed @ Replicator.Changed(`flowReplicatorKey`) => flowsByName = changed.get(flowReplicatorKey).entries
  }

  protected def forwardToFlow(name: String)(message: Flow.MessageCommand): Unit =
    flowShardRegion.forward(name -> message)

  private def addFlow(label: String, sender: ActorRef[FlowReply]) = withUnknownFlow(label, sender) { name =>
    val flowDescriptor = FlowDescriptor(name, label)
    flowsByName += name -> flowDescriptor
    replicator ! updateFlowData(_ + (name -> flowDescriptor))
    val flowAdded = FlowAdded(flowDescriptor)
    mediator ! DistributedPubSubMediator.Publish(className[FlowEvent], flowAdded)
    sender ! flowAdded
  }

  private def removeFlow(name: String, sender: ActorRef[FlowReply]) = withExistingFlow(name, sender) { name =>
    flowsByName -= name
    replicator ! updateFlowData(_ - name)
    forwardToFlow(name)(Flow.Stop)
    val flowRemoved = FlowRemoved(name)
    mediator ! DistributedPubSubMediator.Publish(className[FlowEvent], flowRemoved)
    sender ! flowRemoved
  }

  private def getMessages(flowName: String, sender: ActorRef[FlowReply]) = withExistingFlow(flowName, sender) { name =>
    val adapter = sender.narrow[FoundMessages].contramap(FoundMessages)
    forwardToFlow(name)(Flow.GetMessages(adapter))
  }

  private def addMessage(flowName: String, text: String, sender: ActorRef[FlowReply]) = withExistingFlow(flowName, sender) { name =>
    val adapter = sender.narrow[MessageAdded].contramap((m: Flow.MessageAdded) ⇒ MessageAdded(m.flowName, m.message))
    forwardToFlow(name)(Flow.AddMessage(text)(adapter))
  }

  private def withUnknownFlow(label: String, sender: ActorRef[FlowReply])(f: String => Unit) = {
    val name = labelToName(label)
    if (!flowsByName.contains(name)) f(name) else sender ! FlowExists(label)
  }

  private def withExistingFlow(name: String, sender: ActorRef[FlowReply])(f: String => Unit) =
    if (flowsByName.contains(name)) f(name) else sender ! FlowUnknown(name)
}
