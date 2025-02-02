package myPropaneMqtt

import myPropaneMqtt.MyPropaneApiBehavior.{
  DeviceTelemetry,
  GetDeviceTelemetry,
  GetUserData,
  GetUserDevices,
  UserData,
  UserDevices
}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.util.Timeout

import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.util.{Failure, Success}

//noinspection ScalaWeakerAccess
object DemoApp extends scala.App {
  private sealed trait AppMessage
  private final case class GotUserData(userData: UserData) extends AppMessage
  private final case class GotUserDevices(userDevices: UserDevices) extends AppMessage
  private final case class GotDeviceTelemetry(deviceTelemetry: DeviceTelemetry) extends AppMessage
  private final case class GotError(error: Throwable) extends AppMessage

  private implicit val timeout: Timeout = Timeout(1.minute)

  private object DemoAppBehavior {
    def apply(): Behavior[AppMessage] = Behaviors.setup { context =>
      val myPropaneApi = context.spawn(
        MyPropaneApiBehavior(
          username = Config.myPropaneUsername,
          password = Config.myPropanePassword
        ),
        "my-propane-api"
      )

      context.ask(myPropaneApi, GetUserData(_)) {
        case Success(value)     => GotUserData(value)
        case Failure(exception) => GotError(exception)
      }

      context.ask(myPropaneApi, GetUserDevices(_)) {
        case Success(value)     => GotUserDevices(value)
        case Failure(exception) => GotError(exception)
      }

      Behaviors.receiveMessage {
        case GotUserData(userData) =>
          context.log.info("GotUserData: {}", userData)
          Behaviors.same

        case GotUserDevices(userDevices) =>
          context.log.info("GotUserDevice: {}", userDevices)
          val myDevice = userDevices.devices.find(_.device.deviceName == "Home").get
          context.ask(
            myPropaneApi,
            GetDeviceTelemetry(
              _,
              myDevice.device.deviceID,
              startTime = LocalDateTime.now.minusDays(7),
              endTime = LocalDateTime.now,
              interval = "daily",
              tankQty = myDevice.tank.tankQuantity,
              tankSize = myDevice.tank.tankSize
            )
          ) {
            case Success(value)     => GotDeviceTelemetry(value)
            case Failure(exception) => GotError(exception)
          }
          Behaviors.same

        case GotDeviceTelemetry(DeviceTelemetry(_, data)) =>
          context.log.info("GotDeviceTelemetry: {}", data)
          Behaviors.stopped

        case GotError(error) =>
          context.log.error("GotError", error)
          Behaviors.stopped
      }
    }
  }

  ActorSystem(DemoAppBehavior(), "demo-app")
}
