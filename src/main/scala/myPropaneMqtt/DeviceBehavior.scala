package myPropaneMqtt

import myPropaneMqtt.MyPropaneApiBehavior.{GetUserDevices, MyPropaneApiMessage, UserDevice}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.Timeout
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttMessage
import play.api.libs.json._

import java.net.URL
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import scala.annotation.unused
import scala.concurrent.duration._
import scala.util.chaining._

object DeviceBehavior {
  sealed trait DeviceMessage
  private final case object DoUpdate extends DeviceMessage
  private final case class UpdateReceived(device: UserDevice) extends DeviceMessage

  private implicit val askTimeout: Timeout = Timeout(1.minute)

  def apply(myPropaneApi: ActorRef[MyPropaneApiMessage], initialPayload: UserDevice): Behavior[DeviceMessage] =
    Behaviors.setup { context =>
      val discoveryPayload = generateDiscoveryPayload(initialPayload)
      val mqttMessage = new MqttMessage().tap { m =>
        m.setPayload(Json.toBytes(Json.toJson(discoveryPayload)))
        m.setQos(2)
        m.setRetained(true)
      }
      mqttPublish(discoveryTopic(initialPayload), mqttMessage)
      context.self ! UpdateReceived(initialPayload)
      scheduledUpdate(myPropaneApi, initialPayload.device.deviceID)
    }

  private def scheduledUpdate(myPropaneApi: ActorRef[MyPropaneApiMessage], deviceId: UUID): Behavior[DeviceMessage] =
    Behaviors.withTimers { scheduler =>
      Behaviors.receive {
        case (context, DoUpdate) =>
          context.ask(myPropaneApi, GetUserDevices(_)) { userDevices =>
            UpdateReceived(userDevices.get.devices.find(_.device.deviceID == deviceId).get)
          }
          Behaviors.same
        case (_, UpdateReceived(device)) =>
          val mqttMessage = new MqttMessage().tap { m =>
            m.setPayload(Json.toBytes(Json.toJson(device)))
            m.setQos(2)
            m.setRetained(true)
          }
          mqttPublish(stateTopic(device), mqttMessage)
          val nextUpdate = device.nextPostTimeIso.toInstant(ZoneOffset.UTC)
          // TODO this seems to be running again immediately
          scheduler.startSingleTimer(
            msg = DoUpdate,
            delay = (nextUpdate.toEpochMilli - Instant.now().toEpochMilli + 1.minute.toMillis).millis
          )
          Behaviors.same
      }
    }

  private def mqttPublish(topic: String, message: MqttMessage): Unit = {
    val mqttClient =
      new MqttClient(s"tcp://${Config.mqttHost}:${Config.mqttPort}", "my-propane-mqtt-", new MemoryPersistence)
    mqttClient.connect()
    mqttClient.publish(topic, message)
    mqttClient.disconnect()
  }

  private def discoveryTopic(device: UserDevice): String =
    s"${Config.mqttDeviceDiscoveryTopicPrefix}/device/${device.device.deviceID}/config"
  private def stateTopic(device: UserDevice): String = s"${BuildInfo.name}/${device.device.deviceID}/state"

  // TODO icons
  // TODO timestamps
  // TODO recommended precision
  // TODO device_temp_celsius seems to be publishing the fahrenheit value
  private def generateDiscoveryPayload(device: UserDevice): DiscoveryPayload = {
    def componentFromDeviceField(
        field: String,
        platform: String,
        deviceClass: Option[String] = None,
        unitOfMeasurement: Option[String] = None,
        name: Option[String] = None,
        valueTemplate: Option[String] = None
    ) =
      field -> DiscoveryPayload.Component(
        platform = platform,
        name = name.getOrElse(field.split('_').map(_.capitalize).mkString(" ")),
        valueTemplate = valueTemplate.getOrElse(s"{{ value_json.$field }}"),
        uniqueId = s"${BuildInfo.name}_${device.device.deviceID}_$field",
        deviceClass = deviceClass,
        unitOfMeasurement = unitOfMeasurement
      )
    DiscoveryPayload(
      device = DiscoveryPayload.Device(
        identifiers = device.device.deviceID.toString,
        name = s"${device.appSource} - ${device.device.deviceName}",
        manufacturer = "CentriConnect",
        model = device.device.deviceType,
        swVersion = device.versionLTE,
        hwVersion = device.versionHW
      ),
      origin = DiscoveryPayload.Origin( /* defaults derived from build.sbt */ ),
      components = Map(
        componentFromDeviceField(
          field = "battery_volts",
          platform = "sensor",
          deviceClass = Some("voltage"),
          unitOfMeasurement = Some("V")
        ),
        componentFromDeviceField(
          field = "device_active",
          platform = "binary_sensor",
          deviceClass = Some("connectivity"),
          valueTemplate = Some("{{ value_json.device_active and \"ON\" or \"OFF\" }}")
        ),
        componentFromDeviceField(
          field = "device_status",
          platform = "sensor"
        ),
        componentFromDeviceField(
          field = "device_temp_celsius",
          platform = "sensor",
          deviceClass = Some("temperature"),
          unitOfMeasurement = Some("°C")
        ),
        componentFromDeviceField(
          field = "device_temp_fahrenheit",
          platform = "sensor",
          deviceClass = Some("temperature"),
          unitOfMeasurement = Some("°F")
        ),
        componentFromDeviceField(
          field = "signal_qual_lte",
          platform = "sensor",
          deviceClass = Some("signal_strength"),
          unitOfMeasurement = Some("dBm"),
          name = Some("Signal Quality LTE")
        ),
        componentFromDeviceField(
          field = "solar_volts",
          platform = "sensor",
          deviceClass = Some("voltage"),
          unitOfMeasurement = Some("V")
        ),
        componentFromDeviceField(
          field = "tank_level",
          platform = "sensor",
          unitOfMeasurement = Some("%")
        )
      ),
      stateTopic = stateTopic(device),
      qos = 2
    )
  }

  private case class DiscoveryPayload(
      device: DiscoveryPayload.Device,
      origin: DiscoveryPayload.Origin,
      components: Map[String, DiscoveryPayload.Component],
      stateTopic: String,
      qos: Int
  )

  private object DiscoveryPayload {
    private val jsonConfiguration = Json.configured(JsonConfiguration(naming = JsonNaming.SnakeCase))
    @unused private implicit val urlWrites: Writes[URL] = url => JsString(url.toString)

    case class Device(
        identifiers: String,
        name: String,
        manufacturer: String,
        model: String,
        swVersion: String,
        hwVersion: String
    )
    @unused private implicit val deviceWrites: OWrites[Device] = jsonConfiguration.writes

    case class Origin(
        name: String = BuildInfo.name,
        swVersion: String = BuildInfo.version,
        supportUrl: Option[URL] = BuildInfo.homepage
    )
    @unused private implicit val originWrites: OWrites[Origin] = jsonConfiguration.writes

    case class Component(
        platform: String,
        name: String,
        valueTemplate: String,
        uniqueId: String,
        deviceClass: Option[String] = None,
        unitOfMeasurement: Option[String] = None
    )
    @unused private implicit val componentWrites: OWrites[Component] = jsonConfiguration.writes

    implicit val discoveryPayloadWrites: OWrites[DiscoveryPayload] = jsonConfiguration.writes
  }

}
