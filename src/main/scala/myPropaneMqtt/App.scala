package myPropaneMqtt

import myPropaneMqtt.MyPropaneApiBehavior.{GetUserDevices, MyPropaneApiMessage, UserDevice}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.util.Timeout

import scala.concurrent.duration._

object App extends scala.App {
  private object AppBehavior {
    def apply(): Behavior[AppMessage] = Behaviors.setup { context =>
      val myPropaneApi = context.spawn(
        MyPropaneApiBehavior(
          username = Config.myPropaneUsername,
          password = Config.myPropanePassword
        ),
        "my-propane-api"
      )
      context.watch(myPropaneApi)

      implicit val timeout: Timeout = Timeout(1.minute)
      context.ask(myPropaneApi, GetUserDevices(_))(devices => ReceivedDevices(devices.get.devices))

      waitingForDevices(myPropaneApi)
    }
  }

  private def waitingForDevices(myPropaneApi: ActorRef[MyPropaneApiMessage]): Behavior[AppMessage] =
    Behaviors.receive { case (context, ReceivedDevices(devices)) =>
      devices.foreach { device =>
        val deviceActor = context.spawn(DeviceBehavior(myPropaneApi, device), s"device-${device.device.deviceID}")
        context.watch(deviceActor)
      }
      Behaviors.empty
    }

  private sealed trait AppMessage
  private final case class ReceivedDevices(devices: Seq[UserDevice]) extends AppMessage

  ActorSystem(AppBehavior(), "app")
}
