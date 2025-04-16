package miniclust.application

import better.files.File
import gears.async.default.given
import gears.async.*
import miniclust.compute.*
import miniclust.message.{MiniClust, Minio}

import java.util.UUID
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


@main def run(configurationFile: String) =
  val configuration = Configuration.read(File(configurationFile))
  val baseDirectory = File(configuration.compute.workDirectory)
  baseDirectory.createDirectories()

  given fileCache: FileCache = FileCache(baseDirectory / "cache", configuration.compute.cache)

  val server = Minio.Server(configuration.minio.url, configuration.minio.user, configuration.minio.password, insecure = configuration.minio.insecure)
  val coordinationBucket = Minio.bucket(server, MiniClust.Coordination.bucketName)
  val seed = UUID.randomUUID().hashCode()

  val random = util.Random(seed)
  Service.startBackgroud(server, coordinationBucket, fileCache, random)

  Async.blocking:
    (0 until Runtime.getRuntime.availableProcessors()).map: i =>
      given Compute.ComputeConfig =
        Compute.ComputeConfig(
          baseDirectory = File(configuration.compute.workDirectory) / i.toString,
          cache = configuration.compute.cache,
          sudo = configuration.compute.sudo
        )

      given JobPull.JobPullConfig = JobPull.JobPullConfig(util.Random(seed + i + 1))
      runWorker(server, coordinationBucket)
    .awaitAll

def runWorker(server: Minio.Server, coordinationBucket: Minio.Bucket)(using JobPull.JobPullConfig, Compute.ComputeConfig, FileCache, Async.Spawn) =
  Future:
    while true
    do
      try Compute.runJob(server, coordinationBucket)
      catch
        case e: Exception =>
          Compute.logger.log(Level.SEVERE, "Error in run loop", e)
