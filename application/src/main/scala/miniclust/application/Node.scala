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
      val configuration = Configuration.read(config.configurationFile.get)

      val cores = configuration.compute.cores.getOrElse(Runtime.getRuntime.availableProcessors())
      val activity = MiniClust.WorkerActivity(cores)

      val baseDirectory = File(configuration.compute.workDirectory)
      baseDirectory.createDirectories()

      given fileCache: FileCache = FileCache(baseDirectory / "cache", configuration.compute.cache)

      val server = Minio.Server(configuration.minio.url, configuration.minio.key, configuration.minio.secret, insecure = configuration.minio.insecure)

      val minio = Minio(server)
      val coordinationBucket = Minio.bucket(minio, MiniClust.Coordination.bucketName)
      val seed = UUID.randomUUID().hashCode()

      val random = util.Random(seed)

      JobPull.removeAbandonedJobs(minio, coordinationBucket)
      val services = Service.startBackgroud(minio, coordinationBucket, fileCache, activity, random)

      val pool = ComputingResource(cores)
      val accounting = Accounting(48)

      (0 until 10).map: i =>
        given Compute.ComputeConfig =
          Compute.ComputeConfig(
            baseDirectory = File(configuration.compute.workDirectory) / i.toString,
            cache = configuration.compute.cache,
            sudo = configuration.compute.user orElse configuration.compute.sudo
          )

        given JobPull.JobPullConfig = JobPull.JobPullConfig(util.Random(seed + i + 1))

        Background.run:
          while true
          do
            try JobPull.pullJob(minio, coordinationBucket, pool, accounting)
            catch
              case e: Exception =>
                Compute.logger.log(Level.SEVERE, "Error in run loop", e)

      Node.logger.info(s"Worker is running, pulling jobs, resources: ${cores} cores")

      val finished = Semaphore(0)
      scala.sys.addShutdownHook:
        finished.release()
      finished.acquire()

      services.stop()
      minio.close()

    case _ =>



