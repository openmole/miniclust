package miniclust.application

import better.files.File
import gears.async.default.given
import gears.async.*
import miniclust.compute.*
import miniclust.compute.JobPull.executeJob
import miniclust.message.{MiniClust, Minio}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, Semaphore}
import java.util.logging.*
import scala.util.Random
import scala.util.hashing.MurmurHash3

/*
 * Copyright (C) 2025 Romain Reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

object Node:
  val logger = Logger.getLogger(getClass.getName)

def loadConfiguration(configurationFile: File) =
  val configuration = Configuration.read(configurationFile)

  val cores = configuration.compute.cores.getOrElse(Runtime.getRuntime.availableProcessors())
  val activity = MiniClust.WorkerActivity(cores, configuration.minio.key)

  val baseDirectory = File(configuration.compute.workDirectory)
  baseDirectory.createDirectories()

  val fileCache: FileCache = FileCache(baseDirectory / "cache", configuration.compute.cache)

  val server = Minio.Server(configuration.minio.url, configuration.minio.key, configuration.minio.secret, insecure = configuration.minio.insecure)

  val minio = Minio(server)
  val coordinationBucket = Minio.bucket(minio, MiniClust.Coordination.bucketName)
  val seed = UUID.randomUUID().hashCode()

  val random = util.Random(seed)

  (
    configuration = configuration,
    cores = cores,
    activity = activity,
    baseDirectory = baseDirectory,
    fileCache = fileCache,
    server = server,
    minio = minio,
    coordinationBucket = coordinationBucket,
    random = random,
    seed = seed
  )


@main def run(args: String*) =
  case class Args(
    configurationFile: Option[File] = None)

  import scopt.OParser
  val builder = OParser.builder[Args]
  val parser1 =
    import builder.*
    OParser.sequence(
      programName("miniclust"),
      opt[String]("config")
        .action((x, c) => c.copy(configurationFile = Some(File(x))))
        .text("Configuration file")
    )

  OParser.parse(parser1, args, Args()) match
    case Some(config) =>
      val c = loadConfiguration(config.configurationFile.get)
      given FileCache = c.fileCache

      val pool = ComputingResource(c.cores)
      val accounting = UsageHistory(48)
      
      val services = Service.startBackgroud(c.minio, c.coordinationBucket, c.fileCache, c.activity, pool, c.random)
      val pullers = Tool.Counter(1, 10)

      def runPuller(): Unit =
        Node.logger.info(s"Add a puller, currently ${pullers.value}")

        given computeConfig: Compute.ComputeConfig =
          Compute.ComputeConfig(
            baseDirectory = File.newTemporaryDirectory("", Some(File(c.configuration.compute.workDirectory))),
            cache = c.configuration.compute.cache,
            sudo = c.configuration.compute.user orElse c.configuration.compute.sudo
          )

        Background.run:
          var stop = false
          while !stop
          do
            try
              val job = JobPull.pull(c.minio, c.coordinationBucket, pool, accounting, c.random)

              job match
                case Some(job) =>
                  val heartBeat = JobPull.startHeartBeat(c.minio, c.coordinationBucket, job)

                  Background.run:
                    JobPull.executeJob(c.minio, c.coordinationBucket, job, accounting, c.activity.identifier, heartBeat)

                  if pullers.tryIncrement()
                  then runPuller()
                case None =>
                  if pullers.tryDecrement()
                  then stop = true
            catch
              case e: Exception =>
                Compute.logger.log(Level.SEVERE, "Error in run loop", e)

          Node.logger.info(s"Stop a puller, currently ${pullers.value}")

        computeConfig.baseDirectory.delete(true)

      runPuller()
      Node.logger.info(s"Worker is running, pulling jobs, resources: ${c.cores} cores")

      val finished = Semaphore(0)
      scala.sys.addShutdownHook:
        finished.release()
      finished.acquire()

      services.stop()
      c.minio.close()

    case _ =>



