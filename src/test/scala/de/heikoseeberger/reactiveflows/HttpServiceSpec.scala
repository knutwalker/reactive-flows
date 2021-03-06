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

import akka.actor.{ ActorRef, Status }
import akka.cluster.pubsub.DistributedPubSubMediator
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.RouteTest
import akka.http.scaladsl.testkit.TestFrameworkInterface.Scalatest
import akka.stream.scaladsl.Source
import akka.testkit.{ EventFilter, TestActor, TestProbe }
import de.heikoseeberger.akkahttpcirce.CirceSupport
import de.heikoseeberger.akkasse.{ EventStreamUnmarshalling, ServerSentEvent }
import io.circe.jawn
import java.time.LocalDateTime
import org.scalatest.{ Matchers, WordSpec }
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class HttpServiceSpec extends WordSpec with Matchers with RouteTest with Scalatest with RequestBuilding {
  import CirceSupport._
  import EventStreamUnmarshalling._
  import HttpService._
  import io.circe.generic.auto._
  import io.circe.java8._

  val settings = Settings(system)
  import settings.httpService._

  "The HttpService route" should {
    "send itself a Stop upon a 'DELETE /' and respond with OK" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()

      val request = Delete()
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.OK
      }

      httpService.expectMsg(Stop)
    }

    "respond with OK upon a 'GET /'" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()

      val request = Get()
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.OK
      }
    }

    "respond with OK upon a 'GET /index.html'" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()

      val request = Get("/index.html")
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.OK
      }
    }

    "ask the FlowFacade GetFlows and respond with OK with the proper payload upon a 'GET /flows'" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      val akkaFlow = FlowFacade.FlowDescriptor("akka", "Akka")
      val angularJsFlow = FlowFacade.FlowDescriptor("angularjs", "AngularJS")
      val flows = Vector(akkaFlow, angularJsFlow)
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.GetFlows =>
            sender ! flows
            TestActor.NoAutoPilot
        }
      })

      val request = Get("/flows")
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.OK
        responseAs[Seq[FlowFacade.FlowDescriptor]] shouldBe flows
      }
    }

    "ask the FlowFacade AddFlow and respond with Created with the proper payload upon a 'POST /flows' for an unknown Flow" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      val flowAdded = FlowFacade.FlowAdded(FlowFacade.FlowDescriptor("akka", "Akka"))
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.AddFlow("Akka") =>
            sender ! flowAdded
            TestActor.NoAutoPilot
        }
      })

      val request = Post("/flows", AddFlowRequest("Akka"))
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.Created
        responseAs[FlowFacade.FlowAdded] shouldBe flowAdded
      }
    }

    "ask the FlowFacade AddFlow and respond with Conflict with the proper payload upon a 'POST /flows' for an existing Flow" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      val flowExists = FlowFacade.FlowExists("Akka")
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.AddFlow("Akka") =>
            sender ! flowExists
            TestActor.NoAutoPilot
        }
      })

      val request = Post("/flows", AddFlowRequest("Akka"))
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.Conflict
        responseAs[FlowFacade.FlowExists] shouldBe flowExists
      }
    }

    "ask the FlowFacade RemoveFlow and respond with NoContent with the proper payload upon a 'DELETE /flows/akka' for an existing Flow" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.RemoveFlow("akka") =>
            sender ! FlowFacade.FlowRemoved("akka")
            TestActor.NoAutoPilot
        }
      })

      val request = Delete("/flows/akka")
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.NoContent
      }
    }

    "ask the FlowFacade RemoveFlow and respond with NotFound with the proper payload upon a 'DELETE /flows/unknown' for an unknown Flow" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.RemoveFlow("unknown") =>
            sender ! FlowFacade.FlowUnknown("unknown")
            TestActor.NoAutoPilot
        }
      })

      val request = Delete("/flows/unknown")
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.NotFound
      }
    }

    "ask the FlowFacade GetMessages and respond with OK with the proper payload upon a 'GET /flows/akka/messages' for an existing Flow" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      val messages = Vector(Flow.Message("Akka rules!", now()))
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.GetMessages("akka") =>
            sender ! messages
            TestActor.NoAutoPilot
        }
      })

      val request = Get("/flows/akka/messages")
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.OK
        responseAs[Seq[Flow.Message]] shouldBe messages
      }
    }

    "ask the FlowFacade GetMessages and respond with NotFound with the proper payload upon a 'GET /flows/unknown/messages' for an unknown Flow" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.GetMessages("unknown") =>
            sender ! FlowFacade.FlowUnknown("unknown")
            TestActor.NoAutoPilot
        }
      })

      val request = Get("/flows/unknown/messages")
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.NotFound
      }
    }

    "ask the FlowFacade AddMessage and respond with Created with the proper payload upon a 'POST /flows/akka/messages' for an existing Flow" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      val messageAdded = Flow.MessageAdded("akka", Flow.Message("Akka rocks!", now()))
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.AddMessage("akka", "Akka rocks!") =>
            sender ! messageAdded
            TestActor.NoAutoPilot
        }
      })

      val request = Post("/flows/akka/messages", AddMessageRequest("Akka rocks!"))
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.Created
        responseAs[Flow.MessageAdded] shouldBe messageAdded
      }
    }

    "ask the FlowFacade AddMessage and respond with NotFound with the proper payload upon a 'POST /flows/akka/messages' for an unknown Flow" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      val flowUnknown = FlowFacade.FlowUnknown("unknown")
      flowFacade.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case FlowFacade.AddMessage("unknown", "Akka rocks!") =>
            sender ! flowUnknown
            TestActor.NoAutoPilot
        }
      })

      val request = Post("/flows/unknown/messages", AddMessageRequest("Akka rocks!"))
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.NotFound
        responseAs[FlowFacade.FlowUnknown] shouldBe flowUnknown
      }
    }

    "respond with OK and an SSE stream upon a GET for '/flow-events'" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      val akkaFlow = FlowFacade.FlowDescriptor("akka", "Akka")
      val angularJsFlow = FlowFacade.FlowDescriptor("angularjs", "AngularJS")
      mediator.setAutoPilot(new TestActor.AutoPilot {
        val flowEventTopic = className[FlowFacade.FlowEvent]
        def run(sender: ActorRef, msg: Any) = {
          msg match {
            case DistributedPubSubMediator.Subscribe(`flowEventTopic`, _, source) =>
              source ! FlowFacade.FlowAdded(akkaFlow)
              source ! FlowFacade.FlowAdded(angularJsFlow)
              source ! Status.Success(None)
              TestActor.NoAutoPilot
          }
        }
      })

      val request = Get("/flow-events")
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.OK
        val result = Await.result(
          responseAs[Source[ServerSentEvent, Any]]
            .collect {
              case ServerSentEvent(data, Some(eventType), _, _) =>
                (jawn.decode[FlowFacade.FlowDescriptor](data).valueOr(error => throw error), eventType)
            }
            .runFold(Vector.empty[(FlowFacade.FlowDescriptor, String)])(_ :+ _),
          1.second
        )
        result shouldBe Vector(
          (akkaFlow, "added"),
          (angularJsFlow, "added")
        )
      }
    }

    "respond with OK and an SSE stream upon a GET for '/message-events'" in {
      val httpService = TestProbe()
      val flowFacade = TestProbe()
      val mediator = TestProbe()
      val akkaMessageAdded = Flow.MessageAdded("akka", Flow.Message("Akka rocks!", now()))
      val angularJsMessageAdded = Flow.MessageAdded("angularjs", Flow.Message("AngularJS is quite nice", now()))
      mediator.setAutoPilot(new TestActor.AutoPilot {
        val messageEventTopic = className[Flow.MessageEvent]
        def run(sender: ActorRef, msg: Any) = {
          msg match {
            case DistributedPubSubMediator.Subscribe(`messageEventTopic`, _, source) =>
              source ! akkaMessageAdded
              source ! angularJsMessageAdded
              source ! Status.Success(None)
              TestActor.NoAutoPilot
          }
        }
      })

      val request = Get("/message-events")
      request ~> route(httpService.ref, flowFacade.ref, flowFacadeTimeout, mediator.ref, 99) ~> check {
        response.status shouldBe StatusCodes.OK
        val result = Await.result(
          responseAs[Source[ServerSentEvent, Any]]
            .collect {
              case ServerSentEvent(data, Some(eventType), _, _) =>
                (jawn.decode[Flow.MessageAdded](data).valueOr(error => throw error), eventType)
            }
            .runFold(Vector.empty[(Flow.MessageAdded, String)])(_ :+ _),
          1.second
        )
        result shouldBe Vector(
          (akkaMessageAdded, "added"),
          (angularJsMessageAdded, "added")
        )
      }
    }
  }

  "A HttpService" should {
    "successfully bind to a socket and terminate on receiving a Stop" in {
      val address = "127.0.0.1"
      val port = 9876
      val mediator = TestProbe()
      val probe = TestProbe()

      val httpService = EventFilter.info(occurrences = 1, pattern = s"Listening on.*$address:$port").intercept {
        system.actorOf(HttpService.props(address, port, system.deadLetters, flowFacadeTimeout, mediator.ref, 99))
      }

      val otherHttpService = probe.watch(
        system.actorOf(HttpService.props(address, port, system.deadLetters, flowFacadeTimeout, mediator.ref, 99))
      )
      probe.expectTerminated(otherHttpService)

      probe.watch(httpService)
      httpService ! Stop
      probe.expectTerminated(httpService)
    }
  }

  def now() = LocalDateTime.now().withNano(0)
}
