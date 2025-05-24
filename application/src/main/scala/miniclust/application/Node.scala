package miniclust.application

import better.files.File
import gears.async.default.given
import gears.async.*
import miniclust.compute.*
import miniclust.message.{MiniClust, Minio}

import java.util.UUID
import java.util.concurrent.{Executors, Semaphore}
import java.util.logging.*
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

      JobPull.removeAbandonedJobs(c.minio, c.coordinationBucket)

      val pool = ComputingResource(c.cores)
      val accounting = UsageHistory(48)
      
      val services = Service.startBackgroud(c.minio, c.coordinationBucket, c.fileCache, c.activity, pool, c.random)
      
      (0 until 10).map: i =>
        given Compute.ComputeConfig =
          Compute.ComputeConfig(
            baseDirectory = File(c.configuration.compute.workDirectory) / i.toString,
            cache = c.configuration.compute.cache,
            sudo = c.configuration.compute.user orElse c.configuration.compute.sudo
          )

        given JobPull.JobPullConfig = JobPull.JobPullConfig(util.Random(c.seed + i + 1))

        Background.run:
          while true
          do
            try JobPull.pullJob(c.minio, c.coordinationBucket, pool, accounting, c.activity.identifier)
            catch
              case e: Exception =>
                Compute.logger.log(Level.SEVERE, "Error in run loop", e)

      Node.logger.info(s"Worker is running, pulling jobs, resources: ${c.cores} cores")

      val finished = Semaphore(0)
      scala.sys.addShutdownHook:
        finished.release()
      finished.acquire()

      services.stop()
      c.minio.close()

    case _ =>



