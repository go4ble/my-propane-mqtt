import Dependencies._

ThisBuild / scalaVersion := "2.13.14"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "my-propane-mqtt",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "cognitoidentityprovider" % "2.30.10"
    ),
    libraryDependencies ++= Seq(
      munit % Test
    )
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
