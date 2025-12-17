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
import miniclust.compute.tool.Background
import java.io.PrintStream
import java.security.InvalidParameterException
import scala.util.{Failure, Success, boundary}
import java.util.logging.{Level, Logger}
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ExecutorService
import MiniClust.Accounting

object Compute:
  val logger = Logger.getLogger(getClass.getName)

  object ComputeConfig:
    def apply(baseDirectory: File, trashDirectory: File, cache: Int, sudo: Option[String] = None) =
      baseDirectory.createDirectories()
      val jobDirectory = baseDirectory / "jobs"
      jobDirectory.createDirectories()
      new ComputeConfig(baseDirectory, jobDirectory, trashDirectory, sudo)

  case class ComputeConfig(baseDirectory: File, jobDirectory: File, trashDirectory: File, sudo: Option[String])

  def baseDirectory(id: String)(using config: ComputeConfig) = config.jobDirectory / id.split(":")(1)
  def jobDirectory(id: String)(using config: ComputeConfig) = baseDirectory(id) / "job"
  def trashDirectory(using config: ComputeConfig) = config.trashDirectory

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
            if scala.sys.process.Process(s"tar -xzf ${tmp} -C ${tmpDirectory}").! != 0
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
        Minio.upload(minio, bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${o._1}", timeout = Some(120))

  def uploadOutputFiles(minio: Minio, bucket: Minio.Bucket, r: Message.Submitted, id: String)(using config: ComputeConfig, s: Async.Spawn) =
    r.outputFile.map: output =>
      Future:
        val local = jobDirectory(id) / output.local
        if !local.exists
        then throw new InvalidParameterException(s"Output file ${output.local} does not exist")
        logger.info(s"${id}: upload file ${local} to ${MiniClust.User.jobOutputDirectory(id)}/${output.remote}")
        Minio.upload(minio, bucket, local.toJava, s"${MiniClust.User.jobOutputDirectory(id)}/${output.remote}", timeout = Some(120))

  def createDirectories(id: String)(using config: ComputeConfig) =
    baseDirectory(id).delete(true)
    baseDirectory(id).createDirectories()
    jobDirectory(id).createDirectories()

  def moveBaseDirecotryToTrash(id: String)(using config: ComputeConfig) =
    import scala.sys.process.*
    ProcessUtil.chown(baseDirectory(id).pathAsString, recursive = false).!
    s"mv ${baseDirectory(id)} ${trashDirectory}".!
  
  def createProcess(id: String, command: String, out: Option[File], err: Option[File])(using config: ComputeConfig, label: boundary.Label[(Message.FinalState, Option[Accounting.Job.Profile])]): ProcessUtil.MyProcess =
    try
      config.sudo match
        case None =>
          ProcessUtil.createProcess(Seq("nice", "-n", "5", "bash", "-c", command), jobDirectory(id), out, err, config.sudo)
        case Some(sudo) =>
          import scala.sys.process.*
          ProcessUtil.chown(jobDirectory(id).pathAsString, Some(sudo)).!
          val fullCommand = s"sudo -u $sudo -- $command"
          ProcessUtil.createProcess(Seq("nice", "-n", "5", "bash", "-c", fullCommand), jobDirectory(id), out, err, config.sudo)

    catch
      case e: Exception =>
        logger.info(s"${id}: error launching job execution $e")
        boundary.break((Message.Failed(id, Tool.exceptionToString(e), Message.Failed.Reason.ExecutionFailed), None))


  def run(
    minio: Minio,
    coordinationBucket: Minio.Bucket,
    job: SubmittedJob,
    random: util.Random)(using config: ComputeConfig, fileCache: FileCache): (Message.FinalState, Option[Accounting.Job.Profile]) =

    createDirectories(job.id)

    try
      boundary[(Message.FinalState, Option[Accounting.Job.Profile])]:
        logger.info(s"${job.id}: preparing files")

        def testCanceled(usage: Option[Accounting.Job.Profile]): Unit =
          if JobPull.canceled(minio, job.bucket, job.id)
          then boundary.break((Message.Canceled(job.id, true), usage))

        testCanceled(None)

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
               boundary.break:
                 (Message.Failed(job.id, Tool.exceptionToString(e), Message.Failed.Reason.PreparationFailed), None)


        val sampler = ReservoirSampler(100, random.nextLong(), System.currentTimeMillis())

        try
          testCanceled(None)

          val exit =
            logger.info(s"${job.id}: run ${job.submitted.command}")

            val process = createProcess(job.id, job.submitted.command, output.map(_.file), error.map(_.file))

            val samplerCron =
              tool.Cron.seconds(10, initialSchedule = true)(() => sampler.sample(process.pid, config.sudo, jobDirectory(job.id)))

            try
              while process.isAlive
              do
                if Instant.now().getEpochSecond > job.allocated.deadLine
                then
                  process.dispose()
                  def message = s"Max requested CPU time for the job has been exhausted"
                  uploadOutputError(Some(s"${job.id}: $message"))
                  boundary.break:
                    (Message.Failed(job.id, message, Message.Failed.Reason.TimeExhausted), Some(sampler.sampled))

                if JobPull.canceled(minio, job.bucket, job.id)
                then
                  process.dispose()
                  boundary.break:
                    (Message.Canceled(job.id, true), Some(sampler.sampled))

                Thread.sleep(10000)
              end while

              process.dispose()
            finally
              samplerCron.stop()
              import scala.sys.process.*
              ProcessUtil.chown(jobDirectory(job.id).pathAsString).!

            process.exitValue

          logger.info(s"${job.id}: process ended")

          testCanceled(Some(sampler.sampled))

          if exit != 0
          then
            def message = s"Return exit code of execution was not 0 but ${exit}"
            uploadOutputError(Some(s"${job.id}: $message"))
            boundary.break:
              (Message.Failed(job.id, message, Message.Failed.Reason.ExecutionFailed), Some(sampler.sampled))

        finally usedCache.foreach(FileCache.release(fileCache, _))

        try
          Async.blocking:
            uploadOutputFiles(minio, job.bucket, job.submitted, job.id).awaitAll
        catch
          case e: Exception =>
            uploadOutputError(Some(s"${job.id}: error completing the job $e"))
            boundary.break:
              (Message.Failed(job.id, Tool.exceptionToString(e), Message.Failed.Reason.CompletionFailed), Some(sampler.sampled))

        uploadOutputError(None)
        (Message.Completed(job.id), Some(sampler.sampled))

    catch
      case e: Exception => (Message.Failed(job.id, Tool.exceptionToString(e), Message.Failed.Reason.UnexpectedError), None)
    finally
      Compute.moveBaseDirecotryToTrash(job.id)



object ProcessUtil:
  import java.io.PrintStream
  import scala.jdk.CollectionConverters.*

  val logger = Logger.getLogger(getClass.getName)

  def sudo(user: Option[String])(cmd: String) =
    user match
      case Some(user) => s"sudo -u $user $cmd"
      case None => cmd

  def chown(path: String, user: Option[String] = None, recursive: Boolean = true) =
    val recursiveFlag = if recursive then "-R" else ""
    user match
      case Some(user) => s"sh -c 'sudo safe-wrapper chown $recursiveFlag $user:$user $path'"
      case None => s"sh -c 'sudo safe-wrapper chown $recursiveFlag $$(whoami):$$(whoami) $path'"

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

        sudo(user)(s"bash -c '$killAll' bash ${process.pid()}")

      import scala.sys.process.*
      logger.info(s"Killing process ${process.pid()}")
      killCommand.!

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
  def diskUsage(f: File, user: Option[String]) =
    import scala.sys.process.*
    import scala.util.*
    Try:
      val res = sudo(user)(s"""du -ks ${f.pathAsString}""").!!.trim.takeWhile(_.isDigit)
      res.toLong

  // In KB
  def memory(pid: Long) =
    import scala.sys.process.*
    import scala.util.*

    val script =
      """
        |#!/usr/bin/env bash
        |# Usage: ./mem_tree.sh <PID>
        |# Reports total resident memory (RSS) of PID and its children, in kB
        |
        |pid=$1
        |[ -z "$pid" ] && { echo "Usage: $0 <PID>"; exit 1; }
        |
        |# Recursively collect PIDs
        |collect_pids() {
        |  local p=$1
        |  [ -r /proc/$p/stat ] || return
        |  echo $p
        |  for t in /proc/$p/task/*/children; do
        |    [ -r "$t" ] || continue
        |    for c in $(<"$t"); do collect_pids "$c"; done
        |  done
        |}
        |
        |# Sum RSS (resident set size, in kB) from /proc/[pid]/status
        |sum_rss() {
        |  for p in "$@"; do
        |    [ -r /proc/$p/status ] && awk '/VmRSS:/ {print $2}' /proc/$p/status
        |  done | awk '{s+=$1} END{print s+0}'
        |}
        |
        |# Collect and compute
        |pids=($(collect_pids $pid))
        |sum_rss "${pids[@]}"
        |""".stripMargin

    Try:
      val res = Seq("bash", "-c", script, "--", s"$pid").!!.trim
      res.toLong

  def cpuUsage(pid: Long, sleep: Int = 1): util.Try[Double] =
    import scala.sys.process.*
    import scala.util.*

    val script =
      """#!/usr/bin/env bash
        |# Usage: ./cpu_tree.sh <PID> [seconds]
        |# 100% = one full core
        |
        |pid=$1
        |dur=${2:-1}
        |[ -z "$pid" ] && { echo "Usage: $0 <PID> [seconds]"; exit 1; }
        |
        |clk_tck=$(getconf CLK_TCK)
        |LC_NUMERIC=C  # ensure dot decimal
        |
        |# Recursively collect PIDs
        |collect_pids() {
        |  local p=$1
        |  [ -r /proc/$p/stat ] || return
        |  echo $p
        |  for t in /proc/$p/task/*/children; do
        |    [ -r "$t" ] || continue
        |    for c in $(<"$t"); do collect_pids "$c"; done
        |  done
        |}
        |
        |# Sum utime+stime jiffies
        |sum_jiffies() {
        |  for p in "$@"; do
        |    [ -r /proc/$p/stat ] && awk '{
        |      sub(/^[0-9]+ \([^)]*\) /,"")
        |      print $(12)+$(13)
        |    }' /proc/$p/stat
        |  done | awk '{s+=$1} END{print s+0}'
        |}
        |
        |# First snapshot
        |pids=($(collect_pids $pid))
        |j0=$(sum_jiffies "${pids[@]}")
        |t0=$(date +%s%N)
        |
        |sleep "$dur"
        |
        |# Second snapshot
        |pids=($(collect_pids $pid))
        |if [ ${#pids[@]} -eq 0 ]; then
        |  j1=$j0   # process tree gone → assume no more CPU
        |else
        |  j1=$(sum_jiffies "${pids[@]}")
        |fi
        |t1=$(date +%s%N)
        |
        |dj=$((j1 - j0))
        |elapsed=$(awk -v ns=$((t1 - t0)) 'BEGIN{print ns/1e9}')
        |
        |awk -v dj="$dj" -v clk="$clk_tck" -v sec="$elapsed" \
        |  'BEGIN {
        |     usage = (dj / (clk * sec)) * 100
        |     if (usage < 0) usage = 0
        |     printf "%.2f\n", usage
        |   }'
        |""".stripMargin

    Try:
      val res = Seq("bash", "-c", script, "--", s"$pid", s"$sleep").!!.trim
      res.toDouble


class ReservoirSampler(
  size: Int,
  seed: Long,
  start: Long):

  private val times: Array[Int] = Array.fill(size)(-1)
  private val samples: Array[(memory: Int, disk: Int, cpu: Float)] = Array.fill(size)(null)
  private lazy val random = util.Random(seed)
  private var nbSampled = 0

  def sample(pid: Long, user: Option[String], directory: File) = synchronized:
    val s =
      for
        mem <- ProcessUtil.memory(pid)
        disk <- ProcessUtil.diskUsage(directory, user)
        cpu <- ProcessUtil.cpuUsage(pid)
      yield (memory = (mem / 1024).toInt, disk = (disk / 1024).toInt, cpu = cpu.toFloat)

    s.foreach: s =>
      val time = (System.currentTimeMillis() - start).toInt / 60
      val index =
        if nbSampled < size
        then nbSampled
        else random.nextInt(size)

      times(index) = time
      samples(index) = s
      nbSampled += 1

  def sampled = synchronized:
    val data = (times zip samples).take(nbSampled).sortBy(_._1)
    MiniClust.Accounting.Job.Profile(
      time = data.map(_._1),
      cpu = data.map(_._2.cpu),
      memory = data.map(_._2.memory),
      disk = data.map(_._2.disk)
    )


