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

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.cluster.pubsub.{ DistributedPubSubMessage, DistributedPubSub }
import akka.cluster.sharding.ClusterSharding
import scala.concurrent.Await
import scala.concurrent.duration.Duration

object ReactiveFlowsApp {

  private val jvmArg = """-D(\S+)=(\S+)""".r

  def main(args: Array[String]): Unit = {
    for (jvmArg(name, value) <- args) System.setProperty(name, value)

    val system = ActorSystem("reactive-flows-system")
    Cluster(system).registerOnMemberUp {
      val mediator = DistributedPubSub(system).mediator.typed[DistributedPubSubMessage | Subscribe]
      Flow.startSharding(system, mediator, Settings(system).flowFacade.shardCount)
      ActorOf(
        ReactiveFlows.props(
          mediator,
          DistributedData(system).replicator.typed,
          ClusterSharding(system).shardRegion(className[Flow]).typed
        ),
        ReactiveFlows.Name
      )(system)
    }

    Await.ready(system.whenTerminated, Duration.Inf)
  }
}
