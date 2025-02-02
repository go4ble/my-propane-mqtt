package myPropaneMqtt

import com.auth0.jwt.JWT
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import play.api.libs.json._
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType
import sttp.capabilities.pekko.PekkoStreams
import sttp.client3._

import java.time.{Instant, LocalDateTime}
import java.util.UUID
import scala.annotation.unused
import scala.concurrent.Future

object MyPropaneApiBehavior {
  private val BaseUrl = "https://b1u009l9yf.execute-api.us-east-1.amazonaws.com/prod-v6/mypropane"

  sealed trait MyPropaneApiMessage
  final case class GetUserData(replyTo: ActorRef[UserData]) extends MyPropaneApiMessage
  final case class GetUserDevices(replyTo: ActorRef[UserDevices]) extends MyPropaneApiMessage
  final case class GetDeviceTelemetry(
      replyTo: ActorRef[DeviceTelemetry],
      deviceId: UUID,
      startTime: LocalDateTime,
      endTime: LocalDateTime,
      interval: String,
      tankQty: Int,
      tankSize: Int
  ) extends MyPropaneApiMessage
  private final case class ForwardResponse[T <: MyPropaneApiResponse](replyTo: ActorRef[T], response: Response[T])
      extends MyPropaneApiMessage

  def apply(username: String, password: String): Behavior[MyPropaneApiMessage] = {
    val cognitoAuthentication = new CognitoAuthentication(
      userPoolId = Config.myPropaneUserPoolId,
      clientId = Config.myPropaneClientId,
      clientSecret = Some(Config.myPropaneClientSecret)
    )
    val authResult = cognitoAuthentication.login(username, password)
    Behaviors.setup { context =>
      val sttpBackend = pekkohttp.PekkoHttpBackend.usingActorSystem(context.system.classicSystem)
      MyPropaneApiBehavior(sttpBackend, cognitoAuthentication, authResult)
    }
  }

  private def apply(
      sttpBackend: SttpBackend[Future, PekkoStreams],
      cognitoAuthentication: CognitoAuthentication,
      authResult: AuthenticationResultType
  ): Behavior[MyPropaneApiMessage] = {
    val idToken = JWT.decode(authResult.idToken())
    def authTokenIsExpired = idToken.getExpiresAtAsInstant isBefore Instant.now()

    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case message if authTokenIsExpired =>
          context.self ! message
          val refreshedAuthResult = cognitoAuthentication.refresh(authResult)
          MyPropaneApiBehavior(sttpBackend, cognitoAuthentication, refreshedAuthResult)

        case GetUserData(replyTo) =>
          val response = basicRequest
            .get(uri"$BaseUrl/${idToken.getSubject}/user-data")
            .auth
            .bearer(authResult.idToken())
            .response(playJson.asJson[UserData].getRight)
            .send(sttpBackend)
          context.pipeToSelf(response)(_.fold(e => throw e, ForwardResponse(replyTo, _)))
          Behaviors.same

        case GetUserDevices(replyTo) =>
          val response = basicRequest
            .get(uri"$BaseUrl/${idToken.getSubject}/user-devices")
            .auth
            .bearer(authResult.idToken())
            .response(playJson.asJson[UserDevices].getRight)
            .send(sttpBackend)
          context.pipeToSelf(response)(_.fold(e => throw e, ForwardResponse(replyTo, _)))
          Behaviors.same

        case GetDeviceTelemetry(replyTo, deviceId, startTime, endTime, interval, tankQty, tankSize) =>
          val uri = uri"$BaseUrl/${idToken.getSubject}/device-telemetry/$deviceId".addParams(
            "start_time" -> startTime.withNano(0).toString.replace('T', ' '),
            "end_time" -> endTime.withNano(0).toString.replace('T', ' '),
            "interval" -> interval,
            "tank_qty" -> tankQty.toString,
            "tank_size" -> tankSize.toString
          )
          val response = basicRequest
            .get(uri)
            .auth
            .bearer(authResult.idToken())
            .response(playJson.asJson[DeviceTelemetry].getRight)
            .send(sttpBackend)
          context.pipeToSelf(response)(_.fold(e => throw e, ForwardResponse(replyTo, _)))
          Behaviors.same

        case ForwardResponse(replyTo, payload) =>
          replyTo ! payload.body
          Behaviors.same
      }
    }
  }

  private val jsonConfiguration = Json.configured(JsonConfiguration(naming = JsonNaming.PascalCase))

  // customize DefaultLocalDateTimeReads to handle missing 'T'
  @unused private implicit val localDateTimeReads: Reads[LocalDateTime] =
    Reads.DefaultLocalDateTimeReads.preprocess {
      case JsString(value) => JsString(value.replace(' ', 'T'))
      case jsValue         => jsValue
    }

  private implicit class EnhancedReads[T](reads: Reads[T]) {
    def withNestedPrefixes(prefixes: Seq[String]): Reads[T] = reads.preprocess {
      case JsObject(values) =>
        val groupedByPrefix = values
          .groupBy { case (k, _) => prefixes.find(k.startsWith) }
          .withDefaultValue(Map.empty[String, JsValue])
        prefixes.foldLeft(JsObject(groupedByPrefix(None))) { case (acc, prefix) =>
          acc + (prefix, JsObject(groupedByPrefix(Some(prefix))))
        }
      case jsValue => jsValue
    }
  }

  sealed trait MyPropaneApiResponse

  case class UserData(
      appSource: String,
      appVersion: String,
      businessID: UUID,
      createdDate: LocalDateTime,
      enableLocationServices: Boolean,
      lastAccessTimestamp: LocalDateTime,
      termsAcceptance: Boolean,
      termsAcceptanceTimestamp: LocalDateTime,
      user: UserData.User
  ) extends MyPropaneApiResponse

  object UserData {
    case class User(
        userCity: String,
        userCountry: String,
        userDeviceToken: String,
        userEmail: String,
        userFirst: String,
        userID: UUID,
        userLast: String,
        userPhone: String,
        userPNS: String,
        userState: String,
        userStreet: String,
        userZip: String,
        userLicense: String,
        userLicenseStatus: String,
        userLicenseExpiration: String,
        userNotificationsEmail: Boolean,
        userNotificationsPush: Boolean
    )
    @unused private implicit val userReads: Reads[User] = jsonConfiguration.reads

    private[MyPropaneApiBehavior] implicit val userDataReads: Reads[UserData] =
      jsonConfiguration.reads[UserData].withNestedPrefixes(Seq("User"))
  }

  case class UserDevice(
      alertStatus: String,
      altitude: BigDecimal,
      batteryVolts: BigDecimal,
      createdDate: LocalDateTime,
      device: UserDevice.Device,
      firstLevelAlert: BigInt,
      fuelType: String,
      lastPostTimeIso: LocalDateTime,
      latitude: BigDecimal,
      longitude: BigDecimal,
      nextPostTimeIso: LocalDateTime,
      pollsPerDay: BigInt,
      property: UserDevice.Property,
      secondLevelAlert: BigInt,
      signalQualLTE: BigInt,
      solarVolts: BigDecimal,
      tank: UserDevice.Tank,
      usageType: UserDevice.UsageType,
      weather: UserDevice.Weather,
      userDeviceLicense: UserDevice.UserDeviceLicense,
      versionHW: String,
      versionLTE: String
  )

  object UserDevice {
    case class Device(
        deviceActive: Boolean,
        deviceID: UUID,
        deviceName: String,
        deviceNotificationsEmail: Boolean,
        deviceNotificationsPush: Boolean,
        deviceOwner: Boolean,
        deviceStatus: String,
        deviceTempCelsius: BigInt,
        deviceTempFahrenheit: BigInt,
        deviceType: String
    )
    @unused private implicit val deviceReads: Reads[Device] = jsonConfiguration.reads

    case class Tank(
        tankCity: String,
        tankCountry: String,
        tankLevel: Int,
        tankInstallation: String,
        tankOrientation: String,
        tankOwnership: String,
        tankQuantity: Int,
        tankSize: Int,
        tankSizeUnit: String,
        tankState: String,
        tankStreet: String,
        tankSupplier: String,
        tankZip: String
    )
    @unused private implicit val tankReads: Reads[Tank] = jsonConfiguration.reads

    case class UsageType(
        usageTypeDryer: Boolean,
        usageTypeFireplace: Boolean,
        usageTypeFurnace: Boolean,
        usageTypeGasOven: Boolean,
        usageTypeGasStove: Boolean,
        usageTypeGenerator: Boolean,
        usageTypeIndustrial: Boolean,
        usageTypeOther: Boolean,
        usageTypePoolHeat: Boolean,
        usageTypeWaterHeat: Boolean
    )
    @unused private implicit val usageTypeReads: Reads[UsageType] = jsonConfiguration.reads

    case class Property(
        propertyOwnership: String,
        propertySize: Int,
        propertyType: String
    )
    @unused private implicit val propertyReads: Reads[Property] = jsonConfiguration.reads

    case class Weather(
        weatherHumidity: JsValue,
        weatherTempCelsius: JsValue,
        weatherTempFahrenheit: JsValue
    )
    @unused private implicit val weatherReads: Reads[Weather] = jsonConfiguration.reads

    case class UserDeviceLicense(
        userDeviceLicense: String,
        userDeviceLicenseExpiration: String,
        userDeviceLicenseStatus: String
    )
    @unused private implicit val userDeviceLicenseReads: Reads[UserDeviceLicense] =
      jsonConfiguration.reads

    private[MyPropaneApiBehavior] implicit val userDeviceReads: Reads[UserDevice] = jsonConfiguration
      .reads[UserDevice]
      .withNestedPrefixes(Seq("Device", "Tank", "UsageType", "Property", "Weather", "UserDeviceLicense"))
  }

  case class UserDevices(devices: Seq[UserDevice]) extends MyPropaneApiResponse
  private implicit val userDevicesRead: Reads[UserDevices] =
    Reads.of[Map[UUID, UserDevice]].map(devices => UserDevices(devices.values.toSeq))

  case class DeviceTelemetry(
      deviceID: UUID,
      data: Seq[DeviceTelemetry.Data]
  ) extends MyPropaneApiResponse

  object DeviceTelemetry {
    case class Data(
        deviceID: UUID,
        cloudTimeIso: LocalDateTime,
        batteryVolts: BigDecimal,
        signalQualLTE: BigDecimal,
        solarVolts: Option[BigDecimal],
        tankLevel: Int,
        tankLevelPercentDiff: Option[Int],
        consumption: Option[Int]
    )
    @unused private implicit val dataReads: Reads[Data] = jsonConfiguration.reads

    private[MyPropaneApiBehavior] implicit val deviceTelemetryReads: Reads[DeviceTelemetry] = jsonConfiguration.reads
  }
}
