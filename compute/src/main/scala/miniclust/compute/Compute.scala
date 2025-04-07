package miniclust.compute


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

import scala.jdk.CollectionConverters.*
import miniclust.message.*
import better.files.*
import gears.async.*
import gears.async.default.given
import miniclust.compute.JobPull.SubmittedJob

import scala.util.boundary
import java.util.logging.{Level, Logger}


object Compute:
  val logger = Logger.getLogger(getClass.getName)

  val processDestroyer = new ProcessDestroyer

  object ComputeConfig:
    def apply(baseDirectory: File, cache: Int) =
      baseDirectory.createDirectories()
      val jobDirectory = baseDirectory / "job"
      jobDirectory.createDirectories()
      new ComputeConfig(baseDirectory, jobDirectory)

  case class ComputeConfig(baseDirectory: File, jobDirectory: File)

  def runJob(server: Minio.Server, coordinationBucket: Minio.Bucket)(using JobPull.JobPullConfig, Compute.ComputeConfig, FileCache, Async.Spawn) =
    val (job, run) = JobPull.pull(server, coordinationBucket)
    val heartBeat = JobPull.startHeartBeat(coordinationBucket, run, job)
    try
      val msg = Compute.run(coordinationBucket, job, run)
      Minio.upload(job.bucket, MiniClust.generateMessage(msg), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
    finally heartBeat.stop()
    JobPull.checkOut(coordinationBucket, job)


  def jobDirectory(id: String)(using config: ComputeConfig) = config.jobDirectory / id

  def prepare(bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, fileCache: FileCache): Unit =
    jobDirectory(id).delete(true)
    jobDirectory(id).createDirectories()

    Async.blocking:
      r.inputFile.map: input =>
        Future:
          val local = jobDirectory(id) / input.local
          input.cache match
            case None => Minio.download(bucket, input.remote, local.toJava)
            case Some(l) =>
              val cached = FileCache.cached(fileCache, r.account, l)
              FileCache.use(cached): file =>
                if !file.exists then Minio.download(bucket, input.remote, file.toJava)
                file.copyTo(local)
      .awaitAll

  def complete(bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig): Unit =
    try
      Async.blocking:
        r.outputFile.map: output =>
          Future:
            val local = jobDirectory(id) / output.local
            Minio.upload(bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${output.remote}")
        .awaitAll
    finally jobDirectory(id).delete()


  def run(
    coordinationBucket: Minio.Bucket,
    job: SubmittedJob,
    r: Message.Submitted)(using config: ComputeConfig, fileCache: FileCache) =
    import scala.sys.process.*

    boundary[Message]:
      logger.info(s"${job.id}: preparing files")

      def testCanceled(): Unit =
        if JobPull.canceled(job.bucket, job.id)
        then boundary.break(Message.Canceled(job.id, true))

      testCanceled()

      try prepare(job.bucket, r, job.id)
      catch
         case e: Exception =>
           logger.info(s"${job.id}: error preparing files $e")
           boundary.break(Message.Failed(job.id, e.getMessage, Message.Failed.Reason.PreparationFailed))

      testCanceled()

      val outputWriter = r.stdOut.map(p => jobDirectory(job.id) / p).map(_.newPrintWriter())
      val errorWriter = r.stdErr.map(p => jobDirectory(job.id) / p).map(_.newPrintWriter())

      val exit =
        try
          val processLogger = ProcessLogger(
            line => outputWriter match
              case Some(writer) => writer.println(line)
              case None => println(line),
            line => errorWriter match
              case Some(writer) => writer.println(line)
              case None => System.err.println(line)
          )

          logger.info(s"${job.id}: run ${r.command}")
          val process = Process(r.command, cwd = jobDirectory(job.id).toJava).run(processLogger)
          processDestroyer.add(process)

          while process.isAlive()
          do
            testCanceled()
            Thread.sleep(5000)

          try process.exitValue()
          finally processDestroyer.remove(process)
        finally
          outputWriter.foreach(_.close())
          errorWriter.foreach(_.close())

      logger.info(s"${job.id}: complete job")

      testCanceled()

      complete(job.bucket, r, job.id)
      Message.Completed(job.id, exit)

