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

import akka.actor.{ Actor, ExtendedActorSystem, Extension, ExtensionKey }
import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS }

object Settings extends ExtensionKey[Settings]

class Settings(system: ExtendedActorSystem) extends Extension {

  object flowFacade {
    // $COVERAGE-OFF$
    val shardCount: Int = reactiveFlows.getInt("flow-facade.shard-count")
    // $COVERAGE-ON$
  }

  object httpService {
    val address: String = reactiveFlows.getString("http-service.address")
    val eventBufferSize: Int = reactiveFlows.getInt("http-service.event-buffer-size")
    val flowFacadeTimeout: FiniteDuration = getDuration("http-service.flow-facade-timeout")
    val port: Int = reactiveFlows.getInt("http-service.port")
    val selfTimeout: FiniteDuration = getDuration("http-service.self-timeout")
  }

  private val reactiveFlows = system.settings.config.getConfig("reactive-flows")

  private def getDuration(key: String) = FiniteDuration(reactiveFlows.getDuration(key, MILLISECONDS), MILLISECONDS)
}

trait ActorSettings { this: Actor =>
  val settings: Settings = Settings(context.system)
}
