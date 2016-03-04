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
import de.knutwalker.union._

import akka.actor.ActorSystem
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.cluster.pubsub.{ DistributedPubSubMessage, DistributedPubSubMediator }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }
import akka.persistence.PersistentActor
import java.time.LocalDateTime

object Flow {

  sealed trait MessageEvent
  sealed trait MessageCommand

  case class GetMessages(replyTo: ActorRef[Vector[Message]]) extends MessageCommand
  case class Message(text: String, time: LocalDateTime)

  case class AddMessage(text: String)(val replyTo: ActorRef[MessageAdded]) extends MessageCommand
  case class MessageAdded(flowName: String, message: Message) extends MessageEvent

  case object Stop extends MessageCommand

  // $COVERAGE-OFF$
  def startSharding(system: ActorSystem, mediator: ActorRef[DistributedPubSubMessage | Subscribe], shardCount: Int): Unit = ClusterSharding(system).start(
    className[Flow],
    untypedProps(mediator),
    ClusterShardingSettings(system),
    { case (name: String, payload) => (name, payload) },
    { case (name: String, _) => (name.hashCode % shardCount).toString }
  )
  // $COVERAGE-ON$

  def props(mediator: ActorRef[DistributedPubSubMessage | Subscribe]): Props[MessageCommand | MessageEvent] =
    PropsFor(new Flow(mediator))

  def untypedProps(mediator: ActorRef[DistributedPubSubMessage | Subscribe]): UntypedProps =
    props(mediator).untyped
}

class Flow(mediator: ActorRef[DistributedPubSubMessage | Subscribe]) extends TypedActor.Of[Flow.MessageCommand | Flow.MessageEvent] with PersistentActor {
  import Flow._

  private val name = self.path.name

  private var messages = Vector.empty[Flow.Message]

  override def persistenceId = s"flow-$name"

  override val receiveCommand = Union.total[MessageCommand] {
    case GetMessages(sender)  => sender ! messages
    case m @ AddMessage(text) => addMessage(text, m.replyTo)
    case Stop                 => context.stop(self)
  }

  override val receiveRecover = Union.total[MessageEvent] {
    case MessageAdded(_, message) => messages +:= message
  }

  def typedReceive: TypedReceive = receiveCommand
  override def receive: Receive = receiveCommand

  private def addMessage(text: String, sender: ActorRef[MessageAdded]) = persist(MessageAdded(name, Flow.Message(text, LocalDateTime.now()))) { messageAdded =>
    receiveRecover(messageAdded)
    mediator ! DistributedPubSubMediator.Publish(className[MessageEvent], messageAdded)
    sender ! messageAdded
  }
}
