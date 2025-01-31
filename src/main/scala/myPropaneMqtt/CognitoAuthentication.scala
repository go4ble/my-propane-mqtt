package myPropaneMqtt

// adapted from https://github.com/aws-samples/aws-cognito-java-desktop-app

import myPropaneMqtt.CognitoAuthentication.{DerivedKeyInfo, DerivedKeySize}
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.cognitoidentityprovider.model._

import java.security.{MessageDigest, SecureRandom}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

class CognitoAuthentication(userPoolId: String, clientId: String, clientSecret: Option[String]) {
  private val awsRegion :: userPoolName :: _ = userPoolId.split('_').toList
  private val cognitoIdpClient = CognitoIdentityProviderClient
    .builder()
    .region(Region.of(awsRegion))
    .credentialsProvider(AnonymousCredentialsProvider.create())
    .build()

  def login(username: String, password: String): AuthenticationResultType = {
    val initiateAuthRequest = InitiateAuthRequest
      .builder()
      .authFlow(AuthFlowType.USER_SRP_AUTH)
      .clientId(clientId)
      .authParameters(
        Map(
          "USERNAME" -> Some(username),
          "SRP_A" -> Some(CognitoAuthentication.A.toString(16)),
          "SECRET_HASH" -> calculateSecretHash(username)
        ).collect { case (k, Some(v)) => k -> v }.asJava
      )
      .build()
    val initiateAuthResponse = cognitoIdpClient.initiateAuth(initiateAuthRequest)
    require(initiateAuthResponse.challengeName() == ChallengeNameType.PASSWORD_VERIFIER)

    val challengeParameters = initiateAuthResponse.challengeParameters().asScala
    val B = BigInt(challengeParameters("SRP_B"), 16)
    val salt = BigInt(challengeParameters("SALT"), 16)
    val userIdForSRP = challengeParameters("USER_ID_FOR_SRP")
    val secretBlock = challengeParameters("SECRET_BLOCK")
    val timestamp = Instant.now().atZone(ZoneOffset.UTC).format(CognitoAuthentication.TimestampFormat)
    val respondToAuthChallengeRequest = RespondToAuthChallengeRequest
      .builder()
      .challengeName(ChallengeNameType.PASSWORD_VERIFIER)
      .clientId(clientId)
      .session(initiateAuthResponse.session())
      .challengeResponses(
        Map(
          "PASSWORD_CLAIM_SECRET_BLOCK" -> Some(secretBlock),
          "PASSWORD_CLAIM_SIGNATURE" -> Some(
            Base64.getEncoder.encodeToString(
              calculatePasswordClaimSignature(B, salt, userIdForSRP, password, timestamp, secretBlock)
            )
          ),
          "TIMESTAMP" -> Some(timestamp),
          "USERNAME" -> challengeParameters.get("USERNAME"),
          "SECRET_HASH" -> challengeParameters.get("USERNAME").flatMap(calculateSecretHash)
        ).collect { case (k, Some(v)) => k -> v }.asJava
      )
      .build()
    val respondToAuthChallengeResponse = cognitoIdpClient.respondToAuthChallenge(respondToAuthChallengeRequest)
    val authenticationResultOpt = Option(respondToAuthChallengeResponse.authenticationResult())
    require(authenticationResultOpt.isDefined)

    authenticationResultOpt.get
  }

  private def calculateSecretHash(username: String): Option[String] = {
    clientSecret.map { secret =>
      val signingKey = new SecretKeySpec(secret.getBytes, "HmacSHA256")
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(signingKey)
      mac.update(username.getBytes)
      val rawHmac = mac.doFinal(clientId.getBytes)
      Base64.getEncoder.encodeToString(rawHmac)
    }
  }

  private def calculatePasswordClaimSignature(
      B: BigInt,
      salt: BigInt,
      username: String,
      password: String,
      timestamp: String,
      secretBlock: String
  ): Array[Byte] = {
    import CognitoAuthentication.{N, a, g, k}
    require(B.mod(CognitoAuthentication.N) != 0, "SRP error, B cannot be zero")

    // authenticate the password
    // u = H(A, B)
    val messageDigest = CognitoAuthentication.ThreadMessageDigest.get()
    messageDigest.reset()
    messageDigest.update(CognitoAuthentication.A.toByteArray)
    val u = BigInt(1, messageDigest.digest(B.toByteArray))
    require(u != 0, "hash of A and B cannot be zero")

    // x = H(salt | H(poolName | userId | ":" | password))
    messageDigest.reset()
    messageDigest.update(userPoolName.getBytes)
    messageDigest.update(username.getBytes)
    messageDigest.update(':'.toByte)
    val userIdHash = messageDigest.digest(password.getBytes)
    messageDigest.reset()
    messageDigest.update(salt.toByteArray)
    val x = BigInt(1, messageDigest.digest(userIdHash))

    val S = (B - (k * g.modPow(x, N))).modPow(a + (u * x), N).mod(N)
    val hkdf = Hkdf.getInstance("HmacSHA256")
    hkdf.init(S.toByteArray, u.toByteArray)
    val key = hkdf.deriveKey(DerivedKeyInfo, DerivedKeySize)

    val mac = Mac.getInstance("HmacSHA256")
    val keySpec = new SecretKeySpec(key, "HmacSHA256")
    mac.init(keySpec)
    mac.update(userPoolName.getBytes)
    mac.update(username.getBytes)
    mac.update(Base64.getDecoder.decode(secretBlock))
    mac.doFinal(timestamp.getBytes)
  }
}

private object CognitoAuthentication {
  private val HexN = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
    "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
    "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
    "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
    "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
    "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
    "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
    "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
    "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
    "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
    "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
    "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
  private val EphemeralKeyLength = 1024
  private val secureRandom = SecureRandom.getInstance("SHA1PRNG")
  private val TimestampFormat = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss 'UTC' yyyy")
  private val ThreadMessageDigest = ThreadLocal.withInitial(() => MessageDigest.getInstance("SHA-256"))
  private val DerivedKeySize = 16
  private val DerivedKeyInfo = "Caldera Derived Key"

  private val N = BigInt(HexN, 16)
  private val g = BigInt(2)
  private val (a, bigA) = generateA()
  private val A = bigA

  private val k = {
    val messageDigest = ThreadMessageDigest.get()
    messageDigest.reset()
    messageDigest.update(N.toByteArray)
    val digest = messageDigest.digest(g.toByteArray)
    BigInt(1, digest)
  }

  @tailrec private def generateA(): (BigInt, BigInt) = {
    val a = BigInt(EphemeralKeyLength, secureRandom).mod(N)
    val A = g.modPow(a, N)
    if (A.mod(N) == 0) generateA() else (a, A)
  }
}
