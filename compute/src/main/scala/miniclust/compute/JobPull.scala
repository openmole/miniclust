package miniclust.compute

import miniclust.message.Minio.{Bucket, content, jsonContentType, listObjects, listUserBuckets}
import miniclust.message.*

import java.io.FileNotFoundException
import java.time.{Duration, Instant, ZonedDateTime}
import java.util.concurrent.Executors
import scala.util.*
import gears.async.*
import gears.async.default.given
import miniclust.compute.JobPull.RunningJob.{name, path}
import scala.annotation.tailrec


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
    history: Int,
    ignoreAfter: Int,
    checkAfter: Int) =
    def buckets = listUserBuckets(minio)
    val ignoreList = BucketIgnoreList(ignoreAfter = ignoreAfter, checkAfter = checkAfter, initialBuckets = buckets.map(_.name))

    State(
      ComputingResource(cores),
      UsageHistory(history),
      ignoreList
    )

  case class State(
    computingResource: ComputingResource,
    usageHistory: UsageHistory,
    bucketIgnoreList: BucketIgnoreList)

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

  def startHeartBeat(minio: Minio, coordinationBucket: Bucket, job: SubmittedJob) =
    val message = MiniClust.generateMessage(job.submitted)
    Cron.seconds(5): () =>
      Minio.upload(minio, coordinationBucket, message, JobPull.RunningJob.path(job.bucket.name, job.id), contentType = Some(Minio.jsonContentType))

  def canceled(minio: Minio, bucket: Bucket, id: String) =
    Minio.exists(minio, bucket, MiniClust.User.canceledJob(id))

  def clearCancel(minio: Minio, bucket: Bucket, id: String) =
    Minio.delete(minio, bucket, MiniClust.User.canceledJob(id))

  def runningJobs(minio: Minio, coordinationBucket: Bucket) =
    val prefix = s"${MiniClust.Coordination.jobDirectory}/"
    Minio.listObjects(minio, coordinationBucket, prefix = prefix).filterNot(_.dir).map: i =>
      RunningJob.parse(i.name.drop(prefix.size), i.lastModified.get)

  def selectJob(minio: Minio, coordinationBucket: Bucket, state: State, random: Random): SelectedJob | NotSelected =

    def userJobs(bucket: Bucket) =
      val prefix = s"${MiniClust.User.submitDirectory}/"
      Minio.listObjects(minio, bucket, prefix = prefix, recursive = true).map: i =>
        i.name.drop(prefix.length)

    val buckets = Minio.listUserBuckets(minio)

    val (empty, notEmpty) =
      buckets.
        filter(b => state.bucketIgnoreList.shouldBeChecked(b.name)).
        map(b => b -> userJobs(b)).
        partition(_._2.isEmpty)

    empty.foreach(b => state.bucketIgnoreList.seenEmpty(b._1.name))
    notEmpty.foreach(b => state.bucketIgnoreList.seenNotEmpty(b._1.name))

    def jobs = notEmpty.sortBy((b, _) => state.usageHistory.quantity(b.name)).headOption

    jobs match
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

  def removeAbandonedJobs(minio: Minio, coordinationBucket: Bucket) =
    val date = Minio.date(minio)
    val prefix = s"${MiniClust.Coordination.jobDirectory}/"

    Minio.listAndApply(minio, coordinationBucket, prefix = prefix, recursive = true, maxKeys = Some(100)): i =>
      if !i.dir
      then
        val j = RunningJob.parse(i.name.drop(prefix.length), i.lastModified.getOrElse(0L))
        if (date - j.ping) > 60
        then
          Minio.upload(
            minio,
            Bucket(j.bucketName),
            MiniClust.generateMessage(Message.Failed(j.id, "Job abandoned, please resubmit", Message.Failed.Reason.Abandoned)),
            MiniClust.User.jobStatus(j.id),
            contentType = Some(Minio.jsonContentType)
          )

          Minio.delete(minio, coordinationBucket, s"${RunningJob.path(j.bucketName, j.id)}")

          logger.info(s"Removed job without heartbeat for user ${j.bucketName}: ${j.id}")

  @tailrec def pull(minio: Minio, coordinationBucket: Bucket, state: State, random: Random): Option[SubmittedJob] =
    val job = selectJob(minio, coordinationBucket, state, random)

    job match
      case job: InvalidJob =>
        if checkIn(minio, coordinationBucket, job)
        then
          logger.info(s"${job.id}: failed to validate, ${job.exception.getMessage}")
          Minio.upload(minio, job.bucket, MiniClust.generateMessage(Message.Failed(job.id, Tool.exceptionToString(job.exception), Message.Failed.Reason.Invalid)), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
          Minio.delete(minio, job.bucket, MiniClust.User.submittedJob(job.id))
          clearCheckIn(minio, coordinationBucket, job)

        pull(minio, coordinationBucket, state, random)
      case job: SubmittedJob =>
        def result =
          checkIn(minio, coordinationBucket, job) match
            case true =>
              logger.info(s"${job.id}: checked in remaining resources ${state.computingResource}")
              Minio.upload(minio, job.bucket, MiniClust.generateMessage(Message.Running(job.id)), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
              Minio.delete(minio, job.bucket, MiniClust.User.submittedJob(job.id))
              Some(job)
            case false => None

        def tryResult =
          try result
          catch
            case e: Throwable =>
              ComputingResource.dispose(job.allocated)
              throw e

        tryResult match
          case Some(j) => Some(j)
          case None =>
            ComputingResource.dispose(job.allocated)
            pull(minio, coordinationBucket, state, random)

      case NotSelected.JobRemoved => pull(minio, coordinationBucket, state, random)
      case NotSelected.NotFound | NotSelected.NotEnoughResource => None

  def executeJob(minio: Minio, coordinationBucket: Minio.Bucket, job: SubmittedJob, accounting: UsageHistory, nodeInfo: MiniClust.NodeInfo, heartBeat: Cron.StopTask)(using Compute.ComputeConfig, FileCache) =
    val start = Instant.now()
    try
      logger.info(s"${job.id}: running")
      val msg = Compute.run(minio, coordinationBucket, job)
      logger.info(s"${job.id}: job finished ${msg}")

      val elapsed = UsageHistory.elapsedSeconds(start)

      Background.run:
        Minio.upload(minio, job.bucket, MiniClust.generateMessage(msg), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))

        if msg.canceled
        then JobPull.clearCancel(minio, job.bucket, job.id)

        val usage = MiniClust.JobResourceUsage(job.bucket.name, nodeInfo, elapsed, job.submitted.resource, msg)
        MiniClust.JobResourceUsage.publish(minio, coordinationBucket, usage)
    finally
      ComputingResource.dispose(job.allocated)
      heartBeat.stop()
      accounting.updateAccount(job.bucket.name, UsageHistory.currentHour, UsageHistory.elapsedSeconds(start) * job.allocated.core)
      JobPull.clearCheckIn(minio, coordinationBucket, job)
