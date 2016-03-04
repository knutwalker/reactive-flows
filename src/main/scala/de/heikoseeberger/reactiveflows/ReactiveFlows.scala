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

import akka.actor.{ ActorLogging, SupervisorStrategy, Terminated }
import akka.cluster.ddata.Replicator.{ Update, Subscribe }
import akka.cluster.ddata.LWWMap
import akka.cluster.pubsub.DistributedPubSubMediator.{ Subscribe ⇒ PubSubscribe }
import akka.cluster.pubsub.DistributedPubSubMessage

import de.heikoseeberger.reactiveflows.FlowFacade.{ FlowCommand, FlowDescriptor }
import de.heikoseeberger.reactiveflows.HttpService.Stop

object ReactiveFlows {

  // $COVERAGE-OFF$
  final val Name = "reactive-flows"

  def props(mediator: ActorRef[DistributedPubSubMessage | PubSubscribe], replicator: ActorRef[Subscribe[LWWMap[FlowDescriptor]] | Update[LWWMap[FlowDescriptor]]], flowShardRegion: ActorRef[(String, Flow.MessageCommand)]): Props[Null] =
    PropsFor(new ReactiveFlows(mediator, replicator, flowShardRegion)).narrow
  // $COVERAGE-ON$
}

class ReactiveFlows(
    mediator: ActorRef[DistributedPubSubMessage | PubSubscribe],
    replicator: ActorRef[Subscribe[LWWMap[FlowDescriptor]] | Update[LWWMap[FlowDescriptor]]],
    flowShardRegion: ActorRef[(String, Flow.MessageCommand)]
) extends TypedActor.Of[Terminated] with ActorLogging with ActorSettings {

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  private val flowFacade = context.typedWatch(createFlowFacade())

  context.watch(createHttpService().untyped)
  log.info("Up and running")

  def typedReceive = {
    case Terminated(actor) => onTerminated(actor)
  }

  protected def createFlowFacade(): ActorRef[FlowCommand] = ActorOf(
    FlowFacade.props(mediator, replicator, flowShardRegion),
    FlowFacade.Name
  )

  // $COVERAGE-OFF$
  protected def createHttpService(): ActorRef[Null] = {
    import settings.httpService._
    ActorOf(
      HttpService.props(address, port, flowFacade, flowFacadeTimeout, mediator, eventBufferSize),
      HttpService.Name
    )
  }
  // $COVERAGE-ON$

  // $COVERAGE-OFF$
  protected def onTerminated(actor: UntypedActorRef): Unit = {
    log.error("Terminating the system because {} terminated!", actor)
    context.system.terminate()
  }
  // $COVERAGE-ON$
}
