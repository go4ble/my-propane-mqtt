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
      mqttPublish(discoveryTopic(initialPayload), Json.toBytes(Json.toJson(discoveryPayload)))
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
        case (context, UpdateReceived(device)) =>
          mqttPublish(stateTopic(device), Json.toBytes(Json.toJson(device)))
          val lastUpdate = device.lastPostTimeIso.toInstant(ZoneOffset.UTC)
          context.log.info(
            "{}: last update was {} minutes ago ({})",
            device.device.deviceName,
            (Instant.now.getEpochSecond - lastUpdate.getEpochSecond).seconds.toMinutes,
            lastUpdate
          )
          val nextUpdate = device.nextPostTimeIso.toInstant(ZoneOffset.UTC)
          val expectedDelay = (nextUpdate.getEpochSecond - Instant.now().getEpochSecond + 60).seconds
          context.log.info(
            "{}: next update is in {} minutes ({})",
            device.device.deviceName,
            expectedDelay.toMinutes,
            nextUpdate
          )

          val actualDelay = if (expectedDelay < 1.hour) {
            context.log.warn("{}: expected delay is less than 1 hour; defaulting to 1 hour", device.device.deviceName)
            1.hour
          } else {
            expectedDelay
          }
          scheduler.startSingleTimer(msg = DoUpdate, delay = actualDelay)
          Behaviors.same
      }
    }

  private def mqttPublish(topic: String, payload: Array[Byte]): Unit = {
    val mqttMessage = new MqttMessage().tap { m =>
      m.setPayload(payload)
      m.setQos(2)
      m.setRetained(true)
    }
    val mqttClient =
      new MqttClient(
        s"tcp://${Config.mqttHost}:${Config.mqttPort}",
        s"my-propane-mqtt-${UUID.randomUUID()}",
        new MemoryPersistence
      )
    mqttClient.connect()
    mqttClient.publish(topic, mqttMessage)
    mqttClient.disconnect()
  }

  private def discoveryTopic(device: UserDevice): String =
    s"${Config.mqttDeviceDiscoveryTopicPrefix}/device/${device.device.deviceID}/config"
  private def stateTopic(device: UserDevice): String = s"${BuildInfo.name}/${device.device.deviceID}/state"

  private def generateDiscoveryPayload(device: UserDevice): DiscoveryPayload = {
    def componentFromDeviceField(
        field: String,
        platform: String,
        deviceClass: Option[String] = None,
        unitOfMeasurement: Option[String] = None,
        name: Option[String] = None,
        valueTemplate: Option[String] = None,
        icon: Option[String] = None,
        suggestedDisplayPrecision: Option[Int] = None
    ) =
      field -> DiscoveryPayload.Component(
        platform,
        name = name.getOrElse(field.split('_').map(_.capitalize).mkString(" ")),
        valueTemplate = valueTemplate.getOrElse(s"{{ value_json.$field }}"),
        uniqueId = s"${BuildInfo.name}_${device.device.deviceID}_$field",
        deviceClass,
        unitOfMeasurement,
        icon,
        suggestedDisplayPrecision
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
          unitOfMeasurement = Some("V"),
          icon = Some("mdi:battery"),
          suggestedDisplayPrecision = Some(2)
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
          unitOfMeasurement = Some("°C"),
          name = Some("Device Temperature")
        ),
        componentFromDeviceField(
          field = "last_post_time_iso",
          platform = "sensor",
          deviceClass = Some("timestamp"),
          name = Some("Last Post Time"),
          valueTemplate = Some("{{ value_json.last_post_time_iso }}Z")
        ),
        componentFromDeviceField(
          field = "next_post_time_iso",
          platform = "sensor",
          deviceClass = Some("timestamp"),
          name = Some("Next Post Time"),
          valueTemplate = Some("{{ value_json.next_post_time_iso }}Z")
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
          unitOfMeasurement = Some("V"),
          icon = Some("mdi:solar-power"),
          suggestedDisplayPrecision = Some(2)
        ),
        componentFromDeviceField(
          field = "tank_level",
          platform = "sensor",
          unitOfMeasurement = Some("%"),
          icon = Some("mdi:storage-tank")
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
        unitOfMeasurement: Option[String] = None,
        icon: Option[String] = None,
        suggestedDisplayPrecision: Option[Int] = None
    )
    @unused private implicit val componentWrites: OWrites[Component] = jsonConfiguration.writes

    implicit val discoveryPayloadWrites: OWrites[DiscoveryPayload] = jsonConfiguration.writes
  }

}
