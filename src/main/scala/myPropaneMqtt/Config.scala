package myPropaneMqtt

object Config {
  private val env = sys.env.withDefault(name => throw new Exception(s"environment variable $name is not defined"))

  val myPropaneUsername: String = env("MY_PROPANE_USERNAME")
  val myPropanePassword: String = env("MY_PROPANE_PASSWORD")
  val myPropaneUserPoolId: String = env("MY_PROPANE_USER_POOL_ID")
  val myPropaneClientId: String = env("MY_PROPANE_CLIENT_ID")
  val myPropaneClientSecret: String = env("MY_PROPANE_CLIENT_SECRET")

  val mqttHost: String = env.getOrElse("MQTT_HOST", "localhost")
  val mqttPort: Int = env.get("MQTT_PORT").fold(1883)(_.toInt)
  val mqttDeviceDiscoveryTopicPrefix: String = env.getOrElse("MQTT_DEVICE_DISCOVERY_TOPIC_PREFIX", "homeassistant")
}
