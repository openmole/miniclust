package miniclust.compute

import miniclust.message.Minio.{Bucket, content, jsonContentType, listObjects}
import miniclust.message.*

import java.io.FileNotFoundException
import java.time.{Duration, Instant, ZonedDateTime}
import java.util.concurrent.Executors
import scala.util.*
import gears.async.*
import gears.async.default.given
import miniclust.compute.JobPull.RunningJob.{name, path}
import miniclust.compute.tool.{Background, Cron, IdleBucketList, TimeCache, UsageHistory}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks


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

import java.util.logging.{Logger, Level}



object JobPull:
  private val logger = Logger.getLogger(getClass.getName)

  val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

  def state(
    minio: Minio,
    cores: Int,
    maxCPU: Option[Int],
    maxMemory: Option[Int],
    history: Int,
    ignoreAfter: Int,
    checkAfter: Int,
    bucketCache: Int) =
    def buckets = Minio.listUserBuckets(minio)
    val ignoreList = IdleBucketList(ignoreAfter = ignoreAfter, checkAfter = checkAfter, initialBuckets = buckets.map(_.name))

    State(
      ComputingResource(cores, maxCPU = maxCPU, maxMemory = maxMemory),
      UsageHistory(history),
      ignoreList,
      TimeCache(bucketCache)
    )

  case class State(
    computingResource: ComputingResource,
    usageHistory: UsageHistory,
    bucketIgnoreList: IdleBucketList,
    bucketCache: TimeCache[Seq[Bucket]])

  extension (v: SelectedJob)
    def id =
      v match
        case s: SubmittedJob => s.id
        case i: InvalidJob => i.id

    def bucket =
      v match
        case s: SubmittedJob => s.bucket
        case i: InvalidJob => i.bucket


  type SelectedJob = SubmittedJob | InvalidJob
  case class SubmittedJob(bucket: Bucket, id: String, submitted: Message.Submitted, allocated: ComputingResource.Allocated)
  case class InvalidJob(bucket: Bucket, id: String, exception: Throwable)

  enum PulledJob:
    case Pulled(job: SubmittedJob)
    case NotFound, NotEnoughResource, JobRemoved, Invalid, NotCheckedIn

  enum NotSelected:
    case NotFound, NotEnoughResource, JobRemoved

  object RunningJob:
    def path(bucket: String, id: String) =
      s"${MiniClust.Coordination.jobDirectory}/${RunningJob.name(bucket, id)}"

    def name(bucket: String, id: String) = s"${bucket}/${id}"

    def parse(name: String, ping: Long): RunningJob =
      val index = name.indexOf('/')
      RunningJob(bucketName = name.take(index), id = name.drop(index + 1), ping = ping)

  case class RunningJob(bucketName: String, id: String, ping: Long)

  def startHeartBeat(minio: Minio, coordinationBucket: Bucket, job: SubmittedJob, interval: Int = 30) =
    val message = MiniClust.generateMessage(job.submitted)
    Cron.seconds(interval): () =>
      Minio.upload(minio, coordinationBucket, message, JobPull.RunningJob.path(job.bucket.name, job.id), contentType = Some(Minio.jsonContentType))

  def canceled(minio: Minio, bucket: Bucket, id: String) =
    Minio.exists(minio, bucket, MiniClust.User.canceledJob(id))

  def clearCancel(minio: Minio, bucket: Bucket, id: String) =
    Minio.delete(minio, bucket, MiniClust.User.canceledJob(id))

  def runningJobs(minio: Minio, coordinationBucket: Bucket) =
    val prefix = s"${MiniClust.Coordination.jobDirectory}/"
    Minio.listObjects(minio, coordinationBucket, prefix = prefix).filterNot(_.prefix).map: i =>
      RunningJob.parse(i.name.drop(prefix.length), i.lastModified.get)

  def listUserBuckets(minio: Minio, state: State): Seq[Bucket] =
    def ignore(b: Bucket) = !state.bucketIgnoreList.shouldBeChecked(b.name)
    def ignoreValue(b: Bucket) = b.name == MiniClust.Coordination.bucketName || ignore(b)
    def listBuckets = Minio.listUserBuckets(minio)
    state.bucketCache(() => listBuckets).filterNot(ignoreValue)

  def selectJob(minio: Minio, coordinationBucket: Bucket, state: State, random: Random): SelectedJob | NotSelected =
    def userJobs(bucket: Bucket) =
      val prefix = s"${MiniClust.User.submitDirectory}/"
      Minio.listObjects(minio, bucket, prefix = prefix, maxList = Some(1000), maxKeys = Some(1000)).map: i =>
        i.name.drop(prefix.length)

    def getFirstNonEmpty =
      import scala.util.boundary.*
      val empty = ListBuffer[Bucket]()

      def weightedShuffleGumbel[T](elements: Seq[T], weights: Seq[Double], rnd: Random): Seq[T] =
        val scored =
          (elements zip weights).map: (elem, w) =>
            val key =
              if w == 0
              then Double.PositiveInfinity
              else
                val u = rnd.nextDouble()
                -math.log(u) / w

            (elem, key)

        scored.sortBy(_._2).map(_._1).reverse


      val nonEmpty: Option[(Bucket, Seq[String])] =
        boundary:
          val buckets =
            val userBuckets = listUserBuckets(minio, state)
            weightedShuffleGumbel(userBuckets, userBuckets.map(b => state.usageHistory.quantity(b.name)), random)

          val tried = ListBuffer[(Bucket, Int)]()

          for
            bucket <- buckets
          do
            val jobs = userJobs(bucket)
            if jobs.nonEmpty
            then break(Some(bucket, jobs))
            else empty.addOne(bucket)

          None

      (empty, nonEmpty)

    if ComputingResource.freeCore(state.computingResource) <= 0
    then NotSelected.NotEnoughResource
    else
      val (empty, notEmpty) = getFirstNonEmpty

      logger.info(s"""Listed buckets, not empty: ${notEmpty.map(b => (b._1.name, b._2.size)).getOrElse("None")}, empty: ${empty.mkString(", ")}, usage state: ${state.usageHistory.quantities.toSeq.sortBy(_._1).mkString(", ")}""".stripMargin)

      empty.foreach(b => state.bucketIgnoreList.seenEmpty(b.name))
      notEmpty.foreach(b => state.bucketIgnoreList.seenNotEmpty(b._1.name))

      notEmpty match
        case Some((bucket, u)) =>
          val id = u(random.nextInt(u.size))
          validate(minio, bucket, id) match
            case Success(s) =>
              val cores = s.resource.collectFirst { case r: Resource.Core => r.core }.getOrElse(1)
              val time = s.resource.collectFirst { case r: Resource.MaxTime => r.second }
              ComputingResource.request(state.computingResource, cores, time) match
                case Some(r) => SubmittedJob(bucket, id, s, r)
                case None => NotSelected.NotEnoughResource
            case Failure(e: FileNotFoundException) => NotSelected.JobRemoved
            case Failure(e) => InvalidJob(bucket, id, e)
        case None => NotSelected.NotFound

  def validate(minio: Minio, bucket: Bucket, id: String) =
    Try:
      val content = Minio.content(minio, bucket, MiniClust.User.submittedJob(id))

      if Tool.hashString(content) != id then throw IllegalArgumentException(s"Invalid job id ${id}")

      val run =
        MiniClust.parseMessage(content) match
          case r: Message.Submitted => r
          case m => throw new IllegalArgumentException(s"Invalid message type ${m.getClass}, should be Run")

      if run.account.bucket != bucket.name then throw IllegalArgumentException(s"Incorrect bucket name: ${run.account.bucket}")

      run

  def checkIn(minio: Minio, coordinationBucket: Bucket, job: SelectedJob): Boolean =
    Minio.upload(minio, coordinationBucket, job.id, RunningJob.path(job.bucket.name, job.id), overwrite = false)

  def clearCheckIn(minio: Minio, coordinationBucket: Bucket, job: SelectedJob): Unit =
    logger.info(s"${job.id}: clear check in ${RunningJob.path(job.bucket.name, job.id)}")
    Minio.delete(minio, coordinationBucket, RunningJob.path(job.bucket.name, job.id))

  def abandonedJob(j: RunningJob, date: Long) = (date - j.ping) > 120

  def checkAbandoned(minio: Minio, coordinationBucket: Bucket, job: SelectedJob): Unit =
    Minio.listAndApply(minio, coordinationBucket, RunningJob.path(job.bucket.name, job.id)): o =>
      val date = Minio.date(minio)
      val prefix = s"${MiniClust.Coordination.jobDirectory}"
      val j = RunningJob.parse(o.name.drop(prefix.length + 1), o.lastModified.getOrElse(0L))
      if abandonedJob(j, date)
      then
        Minio.upload(
          minio,
          Bucket(j.bucketName),
          MiniClust.generateMessage(Message.Failed(j.id, "Job abandoned, please resubmit", Message.Failed.Reason.Abandoned)),
          MiniClust.User.jobStatus(j.id),
          contentType = Some(Minio.jsonContentType)
        )

        Minio.delete(minio, coordinationBucket, o.name) //s"${RunningJob.path(j.bucketName, j.id)}")

        logger.info(s"Removed job without heartbeat in ${j.bucketName}: ${j.id}")


  def removeAbandonedJobs(minio: Minio, coordinationBucket: Bucket, random: Random) =
    val date = Minio.date(minio)
    val prefix = s"${MiniClust.Coordination.jobDirectory}"

    val bucketNames =
      Minio.listObjects(minio, coordinationBucket, s"$prefix/", listCommonPrefix = true).filter(_.prefix)

    for
      b <- random.shuffle(bucketNames)
    do
      logger.info(s"Check abandoned in $b")
      Minio.listAndApply(minio, coordinationBucket, prefix = s"${b.name}"): i =>
        val j = RunningJob.parse(i.name.drop(prefix.length + 1), i.lastModified.getOrElse(0L))
        if abandonedJob(j, date)
        then
          Minio.upload(
            minio,
            Bucket(j.bucketName),
            MiniClust.generateMessage(Message.Failed(j.id, "Job abandoned, please resubmit", Message.Failed.Reason.Abandoned)),
            MiniClust.User.jobStatus(j.id),
            contentType = Some(Minio.jsonContentType)
          )

          Minio.delete(minio, coordinationBucket, i.name) //s"${RunningJob.path(j.bucketName, j.id)}")
          logger.info(s"Removed job without heartbeat in ${b.name}: ${j.id}")

  def pull(minio: Minio, coordinationBucket: Bucket, state: State, random: Random): PulledJob =
    val job = selectJob(minio, coordinationBucket, state, random)

    job match
      case job: InvalidJob =>
        state.usageHistory.updateAccount(job.bucket.name, UsageHistory.currentHour, 60)

        if checkIn(minio, coordinationBucket, job)
        then
          logger.info(s"${job.id}: failed to validate, ${job.exception.getMessage}")
          Minio.upload(minio, job.bucket, MiniClust.generateMessage(Message.Failed(job.id, Tool.exceptionToString(job.exception), Message.Failed.Reason.Invalid)), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
          Minio.delete(minio, job.bucket, MiniClust.User.submittedJob(job.id))
          clearCheckIn(minio, coordinationBucket, job)

        PulledJob.Invalid
      case job: SubmittedJob =>
        state.usageHistory.updateAccount(job.bucket.name, UsageHistory.currentHour, 60)

        def result =
          checkIn(minio, coordinationBucket, job) match
            case true =>
              logger.info(s"${job.id}: checked in remaining resources ${state.computingResource}")
              Minio.upload(minio, job.bucket, MiniClust.generateMessage(Message.Running(job.id)), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
              Minio.delete(minio, job.bucket, MiniClust.User.submittedJob(job.id))
              Some(job)
            case false =>
              logger.info(s"${job.id}: checked in failed")
              checkAbandoned(minio, coordinationBucket, job)
              None

        def tryResult =
          try result
          catch
            case e: Throwable =>
              ComputingResource.dispose(job.allocated)
              throw e

        tryResult match
          case Some(j) => PulledJob.Pulled(j)
          case None =>
            ComputingResource.dispose(job.allocated)
            PulledJob.NotCheckedIn

      case NotSelected.JobRemoved => PulledJob.JobRemoved
      case NotSelected.NotFound => PulledJob.NotFound
      case NotSelected.NotEnoughResource => PulledJob.NotEnoughResource

  def executeJob(minio: Minio, coordinationBucket: Minio.Bucket, job: SubmittedJob, accounting: UsageHistory, nodeInfo: MiniClust.NodeInfo, heartBeat: Cron.StopTask, random: util.Random)(using Compute.ComputeConfig, FileCache) =
    val start = Instant.now()
    try
      logger.info(s"${job.id}: running")
      val msg = Compute.run(minio, coordinationBucket, job, random)
      logger.info(s"${job.id}: job finished ${msg}")

      val elapsed = UsageHistory.elapsedSeconds(start)
      Minio.upload(minio, job.bucket, MiniClust.generateMessage(msg), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))

      Background.run:
        def allocatedTime = UsageHistory.elapsedSeconds(start) * job.allocated.core
        accounting.updateAccount(job.bucket.name, UsageHistory.currentHour, allocatedTime)

        if msg.canceled
        then JobPull.clearCancel(minio, job.bucket, job.id)

        val usage = MiniClust.Accounting.Job(job.bucket.name, nodeInfo.id, elapsed, job.submitted.resource, msg)
        MiniClust.Accounting.Job.publish(minio, coordinationBucket, usage)
    finally
      ComputingResource.dispose(job.allocated)
      heartBeat.stop()
      JobPull.clearCheckIn(minio, coordinationBucket, job)
