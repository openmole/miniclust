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

import java.security.InvalidParameterException
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
    Async.blocking:
      r.inputFile.map: input =>
        Future:
          val local = jobDirectory(id) / input.local
          input.cacheKey match
            case None => Minio.download(bucket, input.remote, local.toJava)
            case Some(l) =>
              val cached = FileCache.cached(fileCache, r.account, l)
              FileCache.use(cached): file =>
                if !file.exists
                then
                  val tmp = File.newTemporaryFile()
                  Minio.download(bucket, input.remote, tmp.toJava)
                  val hash = Tool.hashFile(tmp.toJava)

                  if hash != l
                  then
                    tmp.delete(true)
                    throw new InvalidParameterException(s"Cache key for file ${input.remote} is not the hash of the file, should be equal to $hash")

                  tmp.moveTo(file)

                file.copyTo(local)
      .awaitAll


  def uploadOutput(bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, s: Async.Spawn) =
    (r.stdOut ++ r.stdErr).foreach: o =>
      Future:
        val local = jobDirectory(id) / o
        if !local.exists
        then throw new InvalidParameterException(s"Output file $o does not exist")
        Minio.upload(bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${o}")

  def uploadOutputFiles(bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, s: Async.Spawn) =
    r.outputFile.foreach: output =>
      Future:
        val local = jobDirectory(id) / output.local
        if !local.exists
        then throw new InvalidParameterException(s"Output file ${output.local} does not exist")
        Minio.upload(bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${output.remote}")

  def createJobDirectory(id: String)(using config: ComputeConfig) =
    jobDirectory(id).delete(true)
    jobDirectory(id).createDirectories()

  def cleanJobDirectory(id: String)(using config: ComputeConfig) = jobDirectory(id).delete(true)

  def run(
    coordinationBucket: Minio.Bucket,
    job: SubmittedJob,
    r: Message.Submitted)(using config: ComputeConfig, fileCache: FileCache) =
    createJobDirectory(job.id)

    try
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

            val process =
              try Process(r.command, cwd = jobDirectory(job.id).toJava).run(processLogger)
              catch
                case e: Exception =>
                  logger.info(s"${job.id}: error launching job execution $e")
                  boundary.break(Message.Failed(job.id, e.getMessage, Message.Failed.Reason.ExecutionFailed))

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

        if exit != 0
        then
          Async.blocking:
            uploadOutput(job.bucket, r, job.id)

          boundary.break(Message.Failed(job.id, s"Return exit code of execution was not 0 but ${exit}", Message.Failed.Reason.ExecutionFailed))

        try
          Async.blocking:
            uploadOutput(job.bucket, r, job.id)
            uploadOutputFiles(job.bucket, r, job.id)

        catch
          case e: Exception =>
            logger.info(s"${job.id}: error completing the job $e")
            boundary.break(Message.Failed(job.id, e.getMessage, Message.Failed.Reason.CompletionFailed))

        Message.Completed(job.id)
      finally cleanJobDirectory(job.id)
