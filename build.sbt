import com.typesafe.sbt.packager.docker.*

val scala3Version = "3.3.5"

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
    libraryDependencies += "io.circe" %% "circe-yaml" % "0.16.0",
    libraryDependencies += "ch.epfl.lamp" %% "gears" % "0.2.0",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
  .dependsOn(message)


lazy val message = project
  .in(file("message"))
  .settings(
    name := "message",
    libraryDependencies += "io.minio" % "minio" % "8.5.17",
    libraryDependencies += "commons-codec" % "commons-codec" % "1.18.0",
    libraryDependencies += "io.circe" %% "circe-generic" % circeVersion,
    libraryDependencies += "io.circe" %% "circe-parser" % circeVersion,
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )

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
          |apt-get install --no-install-recommends -y ca-certificates ca-certificates-java bash tar gzip locales sudo && \
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
  dockerBaseImage := "openjdk:24-slim"
)


ThisBuild / licenses := Seq("GPLv3" -> url("http://www.gnu.org/licenses/"))
ThisBuild / homepage := Some(url("https://github.com/openmole/miniclust"))

ThisBuild / publishTo := sonatypePublishToBundle.value

ThisBuild / pomIncludeRepository := { _ => false}
ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/openmole/miniclust.git"), "scm:git:git@github.com:openmole/miniclust.git"))

ThisBuild / pomExtra := {
  <!-- Developer contact information -->
    <developers>
      <developer>
        <id>romainreuillon</id>
        <name>Romain Reuillon</name>
        <url>https://github.com/romainreuillon/</url>
      </developer>
    </developers>
}

ThisBuild / sonatypeProfileName := "org.openmole"

import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations.*

releaseVersionBump := sbtrelease.Version.Bump.Minor
releaseTagComment    := s"Releasing ${(ThisBuild / version).value}"
releaseCommitMessage := s"Bump version to ${(ThisBuild / version).value}"

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("+publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
  setNextVersion,
  commitNextVersion,
  //releaseStepCommand("sonatypeReleaseAll"),
  pushChanges
)

