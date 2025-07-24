import com.typesafe.sbt.packager.docker.*

val scala3Version = "3.7.0"

ThisBuild / version := "1.0-SNAPSHOT"
ThisBuild / organization := "org.openmole.miniclust"
ThisBuild / scalacOptions := Seq("-Xmax-inlines:100")
ThisBuild / scalaVersion := scala3Version

def circeVersion = "0.14.12"


lazy val submit = project
  .in(file("submit"))
  .settings(
      name := "submit",
      libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
  .dependsOn(message)


lazy val compute = project
  .in(file("compute"))
  .settings(
    name := "compute",
    libraryDependencies += "com.github.pathikrit" %% "better-files" % "3.9.2",
    libraryDependencies += "io.circe" %% "circe-yaml" % "0.16.1",
    libraryDependencies += "ch.epfl.lamp" %% "gears" % "0.2.0",
    libraryDependencies += "com.github.ben-manes.caffeine" % "caffeine" % "3.2.2",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
  .dependsOn(message)


lazy val message = project
  .in(file("message"))
  .settings(
    name := "message",
    libraryDependencies += "software.amazon.awssdk" % "s3" % "2.32.7",
    libraryDependencies += "software.amazon.awssdk" % "apache-client" % "2.32.7",
    libraryDependencies += "commons-codec" % "commons-codec" % "1.19.0",
    libraryDependencies += "io.circe" %% "circe-generic" % circeVersion,
    libraryDependencies += "io.circe" %% "circe-parser" % circeVersion,
    libraryDependencies += "com.github.f4b6a3" % "ulid-creator" % "5.2.3",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq(
      version,
      BuildInfoKey.action("buildTime") { System.currentTimeMillis }),
    buildInfoPackage := "miniclust"
  )

lazy val documentation = project
  .in(file("documentation"))
  .enablePlugins(MdocPlugin)
  .settings(
    mdocIn := sourceDirectory.value / "main/docs",
    mdocOut := baseDirectory.value / "..",
    name := "documentation",
    mdocVariables := Map(
      "VERSION" -> version.value,
      "SCALA_VERSION" -> scalaVersion.value,
    ),
    libraryDependencies += "com.softwaremill.sttp.apispec" %% "jsonschema-circe" % "0.11.10",
    libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-apispec-docs" % "1.11.38"
  ) dependsOn message

//val prefix = "/opt/docker/application/"
lazy val application = project.in(file("application")) dependsOn(compute) enablePlugins (JavaServerAppPackaging) settings(
  //  daemonUserUid in Docker := None,
  //  daemonUser in Docker    := "openmoleconnect",
  //  dockerChmodType := DockerChmodType.UserGroupWriteExecute,
  //  dockerAdditionalPermissions += (DockerChmodType.UserGroupPlusExecute, "/opt/docker/bin/application"),
  //  dockerAdditionalPermissions += (DockerChmodType.UserGroupWriteExecute, "/home/demiourgos728/.openmole-connect"),
  //libraryDependencies += "com.github.scopt" %% "scopt" % "4.1.0",
//  Docker / mappings ++=
//    Seq(
//      (dependencyFile in client in Compile).value -> s"$prefix/webapp/js/connect-deps.js",
//      (fullOptJS in client in Compile).value.data -> s"$prefix/webapp/js/connect.js"
//    ) ++ doMapping((resourceDirectory in client in Compile).value, prefix)
//      ++ doMapping((cssFile in client in target).value, s"$prefix/webapp/css/")
//      ++ doMapping((resourceDirectory in client in Compile).value / "webapp" / "fonts", s"$prefix/webapp/fonts/"),

  libraryDependencies += "com.github.scopt" %% "scopt" % "4.1.0",
  libraryDependencies += "org.slf4j" % "slf4j-api" % "2.0.17",
  libraryDependencies += "org.slf4j" % "slf4j-nop" % "2.0.17",
  Docker / daemonUser := "miniclust",
  dockerCommands :=
    {
      import com.typesafe.sbt.packager.docker.*
      val dockerCommandsValue = dockerCommands.value

      val executionStageOffset = dockerCommandsValue.indexWhere(_ == DockerStageBreak) + 3
      dockerCommands.value.take(executionStageOffset) ++ Seq(
      Cmd("RUN",
        """echo "deb http://deb.debian.org/debian unstable main non-free contrib" >> /etc/apt/sources.list && \
          |apt-get update && \
          |apt-get install --no-install-recommends -y ca-certificates ca-certificates-java bash tar gzip locales sudo procps&& \
          |apt-get install -y singularity-container && \
          |apt-get clean autoclean && apt-get autoremove --yes && rm -rf /var/lib/{apt,dpkg,cache,log}/ /var/lib/apt/lists/* && \
          |mkdir -p /lib/modules && \
          |sed -i '/^sessiondir max size/c\sessiondir max size = 0' /etc/singularity/singularity.conf && \
          |useradd --system --create-home --uid 1002 job && \
          |echo "miniclust ALL=(job) NOPASSWD: ALL" > /etc/sudoers.d/miniclust_to_job && \
          |chmod 440 /etc/sudoers.d/miniclust_to_job && \
          |echo 'miniclust ALL=(root) NOPASSWD: /bin/chown -R job *' > /etc/sudoers.d/miniclust_chown && \
          |echo 'miniclust ALL=(root) NOPASSWD: /bin/chown -R miniclust *' >> /etc/sudoers.d/miniclust_chown && \
          |chmod 440 /etc/sudoers.d/miniclust_chown
          |""".stripMargin)
      ) ++ dockerCommands.value.drop(executionStageOffset)
  },
  Docker / packageName := "openmole/miniclust",
  Docker / organization := "openmole",
  dockerUpdateLatest := true,
  dockerBaseImage := "openjdk:24-slim",
  Universal / javaOptions ++= Seq("-J-Xmx400m")
)

ThisBuild / licenses := Seq("GPLv3" -> url("http://www.gnu.org/licenses/"))
ThisBuild / homepage := Some(url("https://github.com/openmole/miniclust"))

ThisBuild / publishTo := {
  if (isSnapshot.value) Some(Resolver.sonatypeCentralSnapshots)
  else localStaging.value
}

//ThisBuild / pomIncludeRepository := { _ => false}
ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/openmole/miniclust.git"), "scm:git:git@github.com:openmole/miniclust.git"))

ThisBuild / developers := List(
  Developer(
    id    = "romainreuillon",
    name  = "Romain Reuillon",
    email = "",
    url   = url("https://github.com/romainreuillon/")
  )
)

//ThisBuild / sonatypeProfileName := "org.openmole"
import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations.*

releaseVersionBump := sbtrelease.Version.Bump.Minor
releaseTagComment    := s"Releasing ${(ThisBuild / version).value}"
releaseCommitMessage := s"Bump version to ${(ThisBuild / version).value}"

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  //runClean,
  //runTest,
  setReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("publishSigned"),
  releaseStepCommand("sonaRelease"),
  setNextVersion,
  commitNextVersion,
  //releaseStepCommand("sonatypeReleaseAll"),
  pushChanges
)

