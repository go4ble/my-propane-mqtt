package example

import myPropaneMqtt.CognitoAuthentication

import java.util.Base64
import scala.concurrent.ExecutionContext.Implicits.global

class HelloSpec extends munit.FunSuite {
//  test("say hello") {
//    assertEquals(Hello.greeting, "hello")
//  }

  test("CognitoAuthentication integration test") {
    val userPoolId: String = "us-east-1_aaaaaaaaa"
    val clientId: String = "client-id"
    val clientSecret: String = "client-secret"
    val email = "somebody@example.com"
    val password = "Pass123"

    val cognitoAuthentication = new CognitoAuthentication(userPoolId, clientId, Some(clientSecret))
    cognitoAuthentication.login(email, password).map { result =>
      val _ :: payload :: _ = result.idToken().split('.').toList
      val idTokenPayload = new String(Base64.getDecoder.decode(payload))
      println(s"idTokenPayload: $idTokenPayload")
      assert(idTokenPayload.contains(email))
    }
  }
}
