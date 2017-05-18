name := "josefk"

version := "0.2"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.github.scopt" %% "scopt" % "3.5.0",
  "org.apache.kafka" %% "kafka" % "0.10.0.1",
  "org.apache.kafka" % "kafka-clients" % "0.10.0.1")


lazy val dockerSettings: Seq[sbt.Def.Setting[_]] = Seq(
  dockerfile in docker := {
    val tar: File = packArchiveTbz.value
    val basename = tar.name.replaceAll("""(?i)(.*)\.tar\.bz2""", "$1")
    val dest = s"/app/josefk"

    new Dockerfile {
      from("java:8-jre-alpine")

      env("JOSEFK_HOME", s"${dest}")
      add(tar, "/tmp")
      runRaw(s"mkdir -p $$(dirname $$JOSEFK_HOME) && mv /tmp/${basename} $$JOSEFK_HOME")

      env("LOG4J_CONFIGURATION", "file://$JOSEFK_HOME/log4j.properties")

      workDir("${JOSEFK_HOME}")
      cmd("${JOSEFK_HOME}/bin/main")
    }
  },
  imageNames in docker := Seq(
    ImageName(s"thinkin/api:${version.value}")
  )
)


enablePlugins(DockerPlugin)

packAutoSettings

dockerfile in docker := {
  val tar: File = packArchiveTbz.value
  val basename = tar.name.replaceAll("""(?i)(.*)\.tar\.bz2""", "$1")
  val dest = s"/app/josefk"

  new Dockerfile {
    from("java:8")

    env("JOSEFK_HOME", s"${dest}")
    add(tar, "/tmp")
    runRaw(s"mkdir -p $$(dirname $$JOSEFK_HOME) && mv /tmp/${basename} $$JOSEFK_HOME")

    env("LOG4J_CONFIGURATION", "file://$JOSEFK_HOME/log4j.properties")

    workDir("${JOSEFK_HOME}")
    cmd("${JOSEFK_HOME}/bin/main")
  }
}

imageNames in docker := Seq(
  ImageName(s"uhopper/josefk:kafka0100-${version.value}")
)

