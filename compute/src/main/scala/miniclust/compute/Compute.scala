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

import java.io.PrintStream
import java.security.InvalidParameterException
import scala.util.{Failure, Success, boundary}
import java.util.logging.{Level, Logger}
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ExecutorService

object Compute:
  val logger = Logger.getLogger(getClass.getName)

  object ComputeConfig:
    def apply(baseDirectory: File, cache: Int, sudo: Option[String] = None) =
      baseDirectory.createDirectories()
      val jobDirectory = baseDirectory / "job"
      jobDirectory.createDirectories()
      new ComputeConfig(baseDirectory, jobDirectory, sudo)

  case class ComputeConfig(baseDirectory: File, jobDirectory: File, sudo: Option[String])


  def jobDirectory(id: String)(using config: ComputeConfig) = config.jobDirectory / id.split(":")(1)

  def prepare(bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, fileCache: FileCache): Seq[FileCache.UsedKey] =
    def createCache(file: File, remote: String, providedHash: String, extract: Boolean = false) =
      val tmp = File.newTemporaryFile()
      try
        Minio.download(bucket, remote, tmp.toJava)
        val hash = Tool.hashFile(tmp.toJava)

        if hash != providedHash
        then
          tmp.delete(true)
          throw new InvalidParameterException(s"Cache key for file ${remote} is not the hash of the file, should be equal to $hash")

        if !extract
        then
          tmp.moveTo(file)
          FileCache.setPermissions(file)
        else
          val tmpDirectory = File.newTemporaryDirectory()
          if scala.sys.process.Process(s"tar -xzf ${tmp} -C ${tmpDirectory}").run().exitValue() != 0
          then
            tmpDirectory.delete(true)
            throw new InvalidParameterException(s"Error extracting the archive ${remote}, should be a tgz archive")

          tmpDirectory.listRecursively.foreach(FileCache.setPermissions)
          tmpDirectory.moveTo(file)
          FileCache.setPermissions(file)

      finally tmp.delete(true)

    val cacheUse =
      Async.blocking:
        r.inputFile.map: input =>
          Future:
            util.Try:
              val local = jobDirectory(id) / input.local
              input.cacheKey match
                case None =>
                  Minio.download(bucket, input.remote, local.toJava)
                  None
                case Some(cache) =>
                  Some:
                    val (file, key) =
                      FileCache.use(fileCache, cache.hash, cache.extract): file =>
                        createCache(file, input.remote, cache.hash, cache.extract)
                    Files.createSymbolicLink(local.toJava.toPath, file.toJava.getAbsoluteFile.toPath)
                    key
        .awaitAll

    val (successTry, failureTry) = cacheUse.partition(_.isSuccess)
    val successValues = successTry.collect { case Success(s) => s }
    val failureValues = failureTry.collect { case Failure(e) => e }
    if failureValues.nonEmpty
    then
      successValues.flatten.foreach(FileCache.release(fileCache, _))
      throw failureValues.head
    else successValues.flatten


  def uploadOutput(bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, s: Async.Spawn) =
    (r.stdOut ++ r.stdErr).toSeq.map: o =>
      Future:
        val local = jobDirectory(id) / o
        if !local.exists
        then throw new InvalidParameterException(s"Standard output file $o does not exist")
        Minio.upload(bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${o}")

  def uploadOutputFiles(bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, s: Async.Spawn) =
    r.outputFile.map: output =>
      Future:
        val local = jobDirectory(id) / output.local
        if !local.exists
        then throw new InvalidParameterException(s"Output file ${output.local} does not exist")
        logger.info(s"${id}: upload file ${local} to ${MiniClust.User.jobOutputDirectory(id)}/${output.remote}")
        Minio.upload(bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${output.remote}")

  def createJobDirectory(id: String)(using config: ComputeConfig) =
    jobDirectory(id).delete(true)
    jobDirectory(id).createDirectories()

  def cleanJobDirectory(id: String)(using config: ComputeConfig) = jobDirectory(id).delete(true)

  def createProcess(id: String, command: String, out: Option[File], err: Option[File])(using config: ComputeConfig, label: boundary.Label[Message]): ProcessUtil.MyProcess =
    try
      config.sudo match
        case None =>
          ProcessUtil.createProcess(Seq("bash", "-c", command), jobDirectory(id), out, err)
        case Some(sudo) =>
          val fullCommand =
            Seq(
              s"sudo chown -R $sudo ${jobDirectory(id)}",
              s"sudo -u $sudo -- $command",
              s"sh -c 'sudo chown -R $$(whoami) ${jobDirectory(id)}'").mkString(" && ")

          ProcessUtil.createProcess(Seq("bash", "-c", fullCommand), jobDirectory(id), out, err)
    catch
      case e: Exception =>
        logger.info(s"${id}: error launching job execution $e")
        boundary.break(Message.Failed(id, e.getMessage, Message.Failed.Reason.ExecutionFailed))


  def run(
    coordinationBucket: Minio.Bucket,
    job: SubmittedJob)(using config: ComputeConfig, fileCache: FileCache) =
    createJobDirectory(job.id)

    try
      import scala.sys.process.*

      boundary[Message]:
        logger.info(s"${job.id}: preparing files")

        def testCanceled(): Unit =
          if JobPull.canceled(job.bucket, job.id)
          then boundary.break(Message.Canceled(job.id, true))

        testCanceled()

        val usedCache =
          try prepare(job.bucket, job.submitted, job.id)
          catch
             case e: Exception =>
               logger.info(s"${job.id}: error preparing files $e")
               boundary.break(Message.Failed(job.id, e.getMessage, Message.Failed.Reason.PreparationFailed))

        try
          testCanceled()

          val exit =
            logger.info(s"${job.id}: run ${job.submitted.command}")
            val output = job.submitted.stdOut.map(p => jobDirectory(job.id) / p)
            val error = job.submitted.stdErr.map(p => jobDirectory(job.id) / p)
            val process = createProcess(job.id, job.submitted.command, output, error)

            while process.isAlive
            do
              if Instant.now().getEpochSecond > job.allocated.deadLine
              then
                boundary.break(Message.Failed(job.id, "Max time requested for the job has been exhausted", Message.Failed.Reason.TimeExhausted))

              if JobPull.canceled(job.bucket, job.id)
              then
                process.dispose()
                boundary.break(Message.Canceled(job.id, true))

              Thread.sleep(10000)

            process.dispose()
            process.exitValue

          logger.info(s"${job.id}: complete job")

          testCanceled()

          if exit != 0
          then
            Async.blocking:
              uploadOutput(job.bucket, job.submitted, job.id).awaitAll

            boundary.break(Message.Failed(job.id, s"Return exit code of execution was not 0 but ${exit}", Message.Failed.Reason.ExecutionFailed))
        finally usedCache.foreach(FileCache.release(fileCache, _))

        try
          Async.blocking:
            uploadOutput(job.bucket, job.submitted, job.id).awaitAll
            uploadOutputFiles(job.bucket, job.submitted, job.id).awaitAll
        catch
          case e: Exception =>
            logger.info(s"${job.id}: error completing the job $e")
            boundary.break(Message.Failed(job.id, e.getMessage, Message.Failed.Reason.CompletionFailed))

        Message.Completed(job.id)
      catch
        case e: Exception => Message.Failed(job.id, e.getMessage, Message.Failed.Reason.UnexpectedError)
      finally cleanJobDirectory(job.id)



object ProcessUtil:
  import org.apache.commons.exec.{PumpStreamHandler, ShutdownHookProcessDestroyer}
  import java.io.PrintStream

  val processDestroyer = new ShutdownHookProcessDestroyer

  class MyProcess(process: Process):
    def isAlive: Boolean = process.isAlive
    def dispose(): Unit =
      try
        def kill(p: ProcessHandle) = p.destroyForcibly()
        process.descendants().forEach(kill)
        kill(process.toHandle)
      finally
        processDestroyer.remove(process)

    def exitValue: Int = process.exitValue()

  def createProcess(command: Seq[String], workDirectory: File, out: Option[File], err: Option[File]): MyProcess =
    val runtime = Runtime.getRuntime

    val builder = new ProcessBuilder(command*)
    builder.directory(workDirectory.toJava)

    out match
      case Some(f) => builder.redirectOutput(f.toJava)
      case None => builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)

    err match
      case Some(f) => builder.redirectError(f.toJava)
      case None => builder.redirectError(ProcessBuilder.Redirect.DISCARD)

    val p = builder.start()

    processDestroyer.add(p)
    MyProcess(p)