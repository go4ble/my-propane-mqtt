import Dependencies._

ThisBuild / scalaVersion := "2.13.14"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "my-propane-mqtt",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "cognitoidentityprovider" % "2.30.11",
      "org.apache.pekko" %% "pekko-actor-typed" % "1.1.3",
      "org.apache.pekko" %% "pekko-stream" % "1.1.3",
      "com.softwaremill.sttp.client3" %% "pekko-http-backend" % "3.10.2",
      "com.softwaremill.sttp.client3" %% "play-json" % "3.10.2",
      "ch.qos.logback" % "logback-classic" % "1.5.16",
      "com.auth0" % "java-jwt" % "4.5.0"
    ),
    libraryDependencies ++= Seq(
      munit % Test
    )
  )
