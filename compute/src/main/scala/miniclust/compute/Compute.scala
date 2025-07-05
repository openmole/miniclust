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
import miniclust.message.Message.InputFile.Extraction

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

  def baseDirectory(id: String)(using config: ComputeConfig) = config.jobDirectory / id.split(":")(1)
  def jobDirectory(id: String)(using config: ComputeConfig) = baseDirectory(id) / "job"

  def prepare(minio: Minio, bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, fileCache: FileCache): Seq[FileCache.UsedKey] =
    def createCache(file: File, remote: String, providedHash: String, extraction: Option[Extraction] = None) =
      val tmp = File.newTemporaryFile()
      try
        Minio.download(minio, bucket, remote, tmp.toJava)
        val hash = Tool.hashFile(tmp.toJava)

        if hash != providedHash
        then
          tmp.delete(true)
          throw new InvalidParameterException(s"Cache key for file ${remote} is not the hash of the file, should be equal to $hash")

        extraction match
          case None =>
            tmp.moveTo(file)
            FileCache.setPermissions(file)
          case Some(Extraction.TarGZ) =>
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
                  Minio.download(minio, bucket, input.remote, local.toJava)
                  None
                case Some(cache) =>
                  Some:
                    val (file, key) =
                      FileCache.use(fileCache, cache.hash, cache.extraction): file =>
                        createCache(file, input.remote, cache.hash, cache.extraction)
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


  def uploadOutput(minio: Minio, bucket: Minio.Bucket, id: String, output: Option[(String, File)], error: Option[(String, File)])(using config: ComputeConfig, s: Async.Spawn) =
    (output ++ error).toSeq.map: o =>
      Future:
        val local = o._2
        if !local.exists
        then throw new InvalidParameterException(s"Standard output file $o does not exist")
        Minio.upload(minio, bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${o._1}")

  def uploadOutputFiles(minio: Minio, bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, s: Async.Spawn) =
    r.outputFile.map: output =>
      Future:
        val local = jobDirectory(id) / output.local
        if !local.exists
        then throw new InvalidParameterException(s"Output file ${output.local} does not exist")
        logger.info(s"${id}: upload file ${local} to ${MiniClust.User.jobOutputDirectory(id)}/${output.remote}")
        Minio.upload(minio, bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${output.remote}")

  def createDirectories(id: String)(using config: ComputeConfig) =
    baseDirectory(id).delete(true)
    baseDirectory(id).createDirectories()
    jobDirectory(id).createDirectories()

  def cleanBaseDirectory(id: String)(using config: ComputeConfig) = baseDirectory(id).delete(true)

  def createProcess(id: String, command: String, out: Option[File], err: Option[File])(using config: ComputeConfig, label: boundary.Label[Message.FinalState]): ProcessUtil.MyProcess =
    try
      config.sudo match
        case None =>
          ProcessUtil.createProcess(Seq("bash", "-c", command), jobDirectory(id), out, err, config.sudo)
        case Some(sudo) =>
          val fullCommand =
            Seq(
              s"sudo chown -R $sudo ${jobDirectory(id)}",
              s"sudo -u $sudo -- $command").mkString(" && ")

          ProcessUtil.createProcess(Seq("bash", "-c", fullCommand), jobDirectory(id), out, err, config.sudo)

    catch
      case e: Exception =>
        logger.info(s"${id}: error launching job execution $e")
        boundary.break(Message.Failed(id, Tool.exceptionToString(e), Message.Failed.Reason.ExecutionFailed))


  def run(
    minio: Minio,
    coordinationBucket: Minio.Bucket,
    job: SubmittedJob)(using config: ComputeConfig, fileCache: FileCache): Message.FinalState =
    createDirectories(job.id)

    try
      boundary[Message.FinalState]:
        logger.info(s"${job.id}: preparing files")

        def testCanceled(): Unit =
          if JobPull.canceled(minio, job.bucket, job.id)
          then boundary.break(Message.Canceled(job.id, true))

        testCanceled()

        val output = job.submitted.stdOut.map(p => (path = p, file = baseDirectory(job.id) / "__output__"))
        val error = job.submitted.stdErr.map(p => (path = p, file = baseDirectory(job.id) / "__error__"))

        def create(f: File) =
          f.parent.createDirectories()
          f.write("")

        output.foreach(o => create(o.file))
        error.foreach(o => create(o.file))

        def uploadOutputError(errorMessage: Option[String]) =
          Async.blocking:
            (error zip errorMessage).foreach((s, m) => s.file.appendLine(s"[Error] $m"))
            uploadOutput(minio, job.bucket, job.id, output.map(_.toTuple), error.map(_.toTuple)).awaitAll

        val usedCache =
          try prepare(minio, job.bucket, job.submitted, job.id)
          catch
             case e: Exception =>
               uploadOutputError(Some(s"${job.id}: error preparing files $e"))
               boundary.break(Message.Failed(job.id, Tool.exceptionToString(e), Message.Failed.Reason.PreparationFailed))

        try
          testCanceled()

          val exit =
            logger.info(s"${job.id}: run ${job.submitted.command}")
            val process = createProcess(job.id, job.submitted.command, output.map(_.file), error.map(_.file))

            try
              while process.isAlive
              do
                if Instant.now().getEpochSecond > job.allocated.deadLine
                then
                  process.dispose()
                  def message = s"Max requested CPU time for the job has been exhausted"
                  uploadOutputError(Some(s"${job.id}: $message"))
                  boundary.break(Message.Failed(job.id, message, Message.Failed.Reason.TimeExhausted))

                if JobPull.canceled(minio, job.bucket, job.id)
                then
                  process.dispose()
                  boundary.break(Message.Canceled(job.id, true))

                Thread.sleep(10000)
              end while

              process.dispose()
            finally
              import scala.sys.process.*
              s"sh -c 'sudo chown -R $$(whoami) ${jobDirectory(job.id)}'".run()

            process.exitValue

          logger.info(s"${job.id}: process ended")

          testCanceled()

          if exit != 0
          then
            def message = s"Return exit code of execution was not 0 but ${exit}"
            uploadOutputError(Some(s"${job.id}: $message"))
            boundary.break(Message.Failed(job.id, message, Message.Failed.Reason.ExecutionFailed))
        finally usedCache.foreach(FileCache.release(fileCache, _))

        try
          Async.blocking:
            uploadOutputFiles(minio, job.bucket, job.submitted, job.id).awaitAll
        catch
          case e: Exception =>
            uploadOutputError(Some(s"${job.id}: error completing the job $e"))
            boundary.break(Message.Failed(job.id, Tool.exceptionToString(e), Message.Failed.Reason.CompletionFailed))

        uploadOutputError(None)
        Message.Completed(job.id)
      catch
        case e: Exception => Message.Failed(job.id, Tool.exceptionToString(e), Message.Failed.Reason.UnexpectedError)
      finally cleanBaseDirectory(job.id)



object ProcessUtil:
  import java.io.PrintStream
  import scala.jdk.CollectionConverters.*

  val logger = Logger.getLogger(getClass.getName)

  class MyProcess(process: Process, user: Option[String]):
    def isAlive: Boolean = process.isAlive
    def pid: Long = process.pid()
    def dispose(): Unit =
      val killCommand =
        val killAll =
          """
            |kill_tree() {
            |  local pid="$1"
            |  local timeout=30
            |  local elapsed=0
            |
            |  local children
            |  children=$(ps -eo pid=,ppid= | awk -v p="$pid" "
            |    {
            |      pid[\$1] = \$2
            |    }
            |    END {
            |      for (i in pid) {
            |        ppid = pid[i]
            |        while (ppid && ppid != p) {
            |          ppid = pid[ppid]
            |        }
            |        if (ppid == p) {
            |          print i
            |        }
            |      }
            |    }")
            |
            |  for child in $children; do
            |    kill -TERM "$child" 2>/dev/null
            |  done
            |
            |  kill -TERM "$pid" 2>/dev/null
            |
            |  while ps -p "$pid" > /dev/null; do
            |    if [ "$elapsed" -ge "$timeout" ]; then
            |      kill -KILL "$pid" 2>/dev/null
            |      break
            |    fi
            |    sleep 1
            |    ((elapsed++))
            |  done
            |}
            |
            |kill_tree "$1"
            |""".stripMargin

        user match
          case Some(user) => s"sudo -u $user bash -c '$killAll' bash ${process.pid()}"
          case None => s"bash -c '$killAll' bash ${process.pid()}"

      import scala.sys.process.*
      logger.info(s"Killing process ${process.pid()}")
      killCommand.run()

    def exitValue: Int = process.exitValue()

  def createProcess(command: Seq[String], workDirectory: File, out: Option[File], err: Option[File], user: Option[String]): MyProcess =
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
    MyProcess(p, user)

  // In KB
  def memory(pid: Long) =
    import scala.sys.process.*
    import scala.util.*

    val script =
      s"""
         |#!/bin/bash
         |
         |get_descendants() {
         |    local pid=$$1
         |    local children=$$(ps --no-headers -o pid --ppid "$$pid")
         |    for child in $$children; do
         |        echo $$child
         |        get_descendants $$child
         |    done
         |}
         |
         |PID=$pid
         |
         |# Start with the main process
         |ALL_PIDS=($$PID)
         |
         |# Add all descendants
         |while read -r child; do
         |    ALL_PIDS+=($$child)
         |done < <(get_descendants $$PID)
         |
         |# Sum RSS for all PIDs
         |ps -o rss= -p $$(IFS=,; echo "$${ALL_PIDS[*]}") | awk '{sum+=$$1} END {print sum}'
         |""".stripMargin

    Try:
      val res = Seq("bash", "-c", script).!!.trim
      res.toLong


  // In seconds
  def cpuTime(pid: Long) =
    import scala.sys.process.*
    import scala.util.*

    val script =
      s"""
         |ps -eo pid,ppid,cputime | awk -v target=${pid} '
         |    BEGIN {
         |        total_seconds = 0
         |        processes[target] = 1
         |    }
         |    {
         |        if ($$2 in processes) {
         |            processes[$$1] = 1
         |        }
         |        if ($$1 in processes) {
         |            # Parse cputime format (HH:MM:SS or MM:SS)
         |            split($$3, time_parts, ":")
         |            if (length(time_parts) == 3) {
         |                seconds = time_parts[1]*3600 + time_parts[2]*60 + time_parts[3]
         |            } else {
         |                seconds = time_parts[1]*60 + time_parts[2]
         |            }
         |            total_seconds += seconds
         |        }
         |    }
         |    END { print total_seconds }
         |'
         |""".stripMargin

    Try:
      val res = Seq("bash", "-c", script).!!
      res.toDouble
