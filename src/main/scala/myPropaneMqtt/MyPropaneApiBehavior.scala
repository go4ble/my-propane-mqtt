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
  private final case class WaitForAuthResult(authResult: AuthenticationResultType) extends MyPropaneApiMessage
  private final case class ForwardResponse[T <: MyPropaneApiResponse](replyTo: ActorRef[T], response: Response[T])
      extends MyPropaneApiMessage

  def apply(username: String, password: String): Behavior[MyPropaneApiMessage] = {
    val cognitoAuthentication = new CognitoAuthentication(
      userPoolId = Config.myPropaneUserPoolId,
      clientId = Config.myPropaneClientId,
      clientSecret = Some(Config.myPropaneClientSecret)
    )
    Behaviors.setup { context =>
      val sttpBackend = pekkohttp.PekkoHttpBackend.usingActorSystem(context.system.classicSystem)

      context.pipeToSelf(cognitoAuthentication.login(username, password)(context.executionContext)) { authResult =>
        WaitForAuthResult(authResult.get)
      }

      MyPropaneApiBehavior.waitForAuthResult(sttpBackend, cognitoAuthentication)
    }
  }

  private def waitForAuthResult(
      sttpBackend: SttpBackend[Future, PekkoStreams],
      cognitoAuthentication: CognitoAuthentication,
      pendingMessages: Seq[MyPropaneApiMessage] = Nil
  ): Behavior[MyPropaneApiMessage] = Behaviors.receive {
    case (context, WaitForAuthResult(authResult)) =>
      pendingMessages.foreach { context.self ! _ }
      MyPropaneApiBehavior.readyForApiRequests(sttpBackend, cognitoAuthentication, authResult)
    case (_, other) =>
      // collect and replay any messages that were sent before initial authentication was complete
      MyPropaneApiBehavior.waitForAuthResult(sttpBackend, cognitoAuthentication, pendingMessages :+ other)
  }

  private def readyForApiRequests(
      sttpBackend: SttpBackend[Future, PekkoStreams],
      cognitoAuthentication: CognitoAuthentication,
      authResult: AuthenticationResultType
  ): Behavior[MyPropaneApiMessage] = {
    val idToken = JWT.decode(authResult.idToken())
    def authTokenIsExpired = idToken.getExpiresAtAsInstant isBefore Instant.now()

    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case message if authTokenIsExpired =>
          context.log.info("refreshing auth token")
          context.pipeToSelf(cognitoAuthentication.refresh(authResult)(context.executionContext)) { authResult =>
            WaitForAuthResult(authResult.get)
          }
          MyPropaneApiBehavior.waitForAuthResult(sttpBackend, cognitoAuthentication, Seq(message))

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

        case other => throw new Exception(s"unexpected message: $other")
      }
    }
  }

  private val jsonReadsConfiguration = Json.configured(JsonConfiguration(naming = JsonNaming.PascalCase))
  private val jsonWritesConfiguration = Json.configured(JsonConfiguration(naming = JsonNaming.SnakeCase))

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

  private implicit class EnhancedOWrites[T](writes: OWrites[T]) {
    def flatten: OWrites[T] = writes.transform(flattenJsObject)

    private def flattenJsObject(jsObject: JsObject): JsObject = {
      JsObject(jsObject.value.flatMap {
        case (_, nestedJsObject: JsObject) =>
          flattenJsObject(nestedJsObject).value
        case x => Seq(x)
      })
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
    @unused private implicit val userReads: Reads[User] = jsonReadsConfiguration.reads

    private[MyPropaneApiBehavior] implicit val userDataReads: Reads[UserData] =
      jsonReadsConfiguration.reads[UserData].withNestedPrefixes(Seq("User"))
  }

  case class UserDevice(
      alertStatus: String,
      altitude: BigDecimal,
      appSource: String,
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
    @unused private implicit val deviceReads: Reads[Device] = jsonReadsConfiguration.reads
    implicit val deviceWrites: OWrites[Device] = jsonWritesConfiguration.writes

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
    @unused private implicit val tankReads: Reads[Tank] = jsonReadsConfiguration.reads
    implicit val tankWrites: OWrites[Tank] = jsonWritesConfiguration.writes

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
    @unused private implicit val usageTypeReads: Reads[UsageType] = jsonReadsConfiguration.reads
    implicit val usageTypeWrites: OWrites[UsageType] = jsonWritesConfiguration.writes

    case class Property(
        propertyOwnership: String,
        propertySize: Int,
        propertyType: String
    )
    @unused private implicit val propertyReads: Reads[Property] = jsonReadsConfiguration.reads
    implicit val propertyWrites: OWrites[Property] = jsonWritesConfiguration.writes

//    case class Weather(
//        weatherHumidity: JsValue,
//        weatherTempCelsius: JsValue,
//        weatherTempFahrenheit: JsValue
//    )
//    @unused private implicit val weatherReads: Reads[Weather] = jsonReadsConfiguration.reads
//    implicit val weatherWrites: OWrites[Weather] = jsonWritesConfiguration.writes

    case class UserDeviceLicense(
        userDeviceLicense: String,
        userDeviceLicenseExpiration: String,
        userDeviceLicenseStatus: String
    )
    @unused private implicit val userDeviceLicenseReads: Reads[UserDeviceLicense] = jsonReadsConfiguration.reads
    implicit val userDeviceLicenseWrites: OWrites[UserDeviceLicense] = jsonWritesConfiguration.writes

    private[MyPropaneApiBehavior] implicit val userDeviceReads: Reads[UserDevice] = jsonReadsConfiguration
      .reads[UserDevice]
      .withNestedPrefixes(Seq("Device", "Tank", "UsageType", "Property", "Weather", "UserDeviceLicense"))
    implicit val userDeviceWrites: OWrites[UserDevice] = jsonWritesConfiguration.writes[UserDevice].flatten
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
    @unused private implicit val dataReads: Reads[Data] = jsonReadsConfiguration.reads

    private[MyPropaneApiBehavior] implicit val deviceTelemetryReads: Reads[DeviceTelemetry] =
      jsonReadsConfiguration.reads
  }
}
