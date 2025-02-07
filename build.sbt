import Dependencies.*
import com.typesafe.sbt.packager.docker.ExecCmd

ThisBuild / scalaVersion := "2.13.15"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.example"
ThisBuild / organizationName := "example"
ThisBuild / homepage := Some(url("https://github.com/go4ble/my-propane-mqtt"))

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "my-propane-mqtt",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "cognitoidentityprovider" % "2.30.11",
      "org.apache.pekko" %% "pekko-actor-typed" % "1.1.3",
      "org.apache.pekko" %% "pekko-stream" % "1.1.3",
      "org.eclipse.paho" % "org.eclipse.paho.mqttv5.client" % "1.2.5",
      "com.softwaremill.sttp.client3" %% "pekko-http-backend" % "3.10.2",
      "com.softwaremill.sttp.client3" %% "play-json" % "3.10.2",
      "ch.qos.logback" % "logback-classic" % "1.5.16",
      "com.auth0" % "java-jwt" % "4.5.0"
    ),
    libraryDependencies ++= Seq(
      munit % Test
    ),
    //
    buildInfoKeys := Seq[BuildInfoKey](name, version, homepage),
    buildInfoPackage := "myPropaneMqtt",
    //
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerRepository := Some("ghcr.io/go4ble"),
    dockerCommands += ExecCmd("CMD", "-main", "myPropaneMqtt.App")
  )
