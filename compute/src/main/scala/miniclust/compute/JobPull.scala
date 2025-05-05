package miniclust.compute

import miniclust.message.Minio.{Bucket, jsonContentType, listObjects}
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

  case class JobPullConfig(random: util.Random)

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
    case NotFound, NotEnoughResource

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

  def selectJob(minio: Minio, coordinationBucket: Bucket, pool: ComputingResource, accounting: Accounting)(using config: JobPullConfig): SelectedJob | NotSelected =
//    val runningJob = runningJobs(coordinationBucket)
//    val runningByBucket = runningJob.groupBy(r => r.bucketName).view.mapValues(_.size).toMap

    def userJobs(bucket: Bucket) =
      val prefix = s"${MiniClust.User.submitDirectory}/"
      Minio.listObjects(minio, bucket, prefix = prefix, recursive = true).map: i =>
        (bucket, i.name.drop(prefix.length))

    val jobs =
      val buckets = Minio.listUserBuckets(minio)
      buckets.sortBy(b => accounting.quantity(b.name)).view.map(userJobs).find(_.nonEmpty)

//    val allUserJobs =
//      Minio.listUserBuckets(server).
//        sortBy(b => runningByBucket.getOrElse(b.name, 0)).
//        map(userJobs).
//        filterNot(_.isEmpty)

    jobs match
      case Some(u) =>
        val (bucket, id) = u(config.random.nextInt(u.size))
        validate(minio, bucket, id) match
          case Success(s) =>
            val cores = s.resource.collectFirst { case r: Resource.Core => r.core }.getOrElse(1)
            val time = s.resource.collectFirst { case r: Resource.MaxTime => r.second }
            ComputingResource.request(pool, cores, time) match
              case Some(r) => SubmittedJob(bucket, id, s, r)
              case None => NotSelected.NotEnoughResource
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

      if run.account.bucket != bucket.name then throw IllegalArgumentException(s"Incorrect bucket name ${run.account.bucket}")

      run

  def checkIn(minio: Minio, coordinationBucket: Bucket, job: SelectedJob): Boolean =
    Minio.upload(minio, coordinationBucket, job.id, RunningJob.path(job.bucket.name, job.id), overwrite = false)

  def checkOut(minio: Minio, coordinationBucket: Bucket, job: SelectedJob): Unit =
    logger.info(s"${job.id}: checkout delete ${RunningJob.path(job.bucket.name, job.id)}")
    Minio.delete(minio, coordinationBucket, RunningJob.path(job.bucket.name, job.id))

  def removeAbandonedJobs(minio: Minio, coordinationBucket: Bucket) =
    val date = Minio.date(minio)
    val prefix = s"${MiniClust.Coordination.jobDirectory}/"
    val objects = Minio.listObjects(minio, coordinationBucket, prefix = prefix)

    case class Directory(path: String)

    val oldJobs =
      Minio.listObjects(minio, coordinationBucket, prefix = prefix).view.flatMap: i =>
        if i.dir
        then None //Some(Directory(o.objectName()))
        else
          val j = RunningJob.parse(i.name.drop(prefix.length), i.lastModified.getOrElse(0L))
          if (date - j.ping) > 60
          then Some(j)
          else None

    for
      j <- oldJobs
    do
      j match
//          case d: Directory =>
//            Minio.delete(minio, coordinationBucket, d.path)
        case j: RunningJob =>
          Minio.upload(
            minio,
            Bucket(j.bucketName),
            MiniClust.generateMessage(Message.Failed(j.id, "Job abandoned, please resubmit", Message.Failed.Reason.Abandoned)),
            MiniClust.User.jobStatus(j.id),
            contentType = Some(Minio.jsonContentType)
          )

          Minio.delete(minio, coordinationBucket, s"${RunningJob.path(j.bucketName, j.id)}")

          logger.info(s"Removed job without heartbeat: ${j.id}")



  @tailrec def pull(minio: Minio, coordinationBucket: Bucket, pool: ComputingResource, accounting: Accounting)(using config: JobPullConfig): SubmittedJob =
    val job = selectJob(minio, coordinationBucket, pool, accounting)

    job match
      case job: InvalidJob =>
        if checkIn(minio, coordinationBucket, job)
        then
          logger.info(s"${job.id}: failed to validate, ${job.exception.getMessage}")
          Minio.upload(minio, job.bucket, MiniClust.generateMessage(Message.Failed(job.id, job.exception.getMessage, Message.Failed.Reason.Invalid)), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
          Minio.delete(minio, job.bucket, MiniClust.User.submittedJob(job.id))
          checkOut(minio, coordinationBucket, job)

        pull(minio, coordinationBucket, pool, accounting)
      case job: SubmittedJob =>
        def result =
          checkIn(minio, coordinationBucket, job) match
            case true =>
              logger.info(s"${job.id}: checked in remaining resources $pool")
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
          case Some(j) => j
          case None =>
            ComputingResource.dispose(job.allocated)
            pull(minio, coordinationBucket, pool, accounting)

      case NotSelected.NotFound | NotSelected.NotEnoughResource =>
        Thread.sleep(config.random.nextInt(10000))
        pull(minio, coordinationBucket, pool, accounting)


  def pullJob(minio: Minio, coordinationBucket: Minio.Bucket, pool: ComputingResource, accounting: Accounting)(using JobPull.JobPullConfig, Compute.ComputeConfig, FileCache) =
    val job = JobPull.pull(minio, coordinationBucket, pool, accounting)
    val heartBeat = JobPull.startHeartBeat(minio, coordinationBucket, job)
    Background.run:
      val start = Instant.now()
      try
        logger.info(s"${job.id}: running")
        val msg = Compute.run(minio, coordinationBucket, job)
        logger.info(s"${job.id}: job finished ${msg}")

        Minio.upload(minio, job.bucket, MiniClust.generateMessage(msg), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))

        if msg.canceled then JobPull.clearCancel(minio, job.bucket, job.id)
      finally
        ComputingResource.dispose(job.allocated)
        accounting.updateAccount(job.bucket.name, Accounting.currentHour, Accounting.elapsedSeconds(start))
        heartBeat.stop()
        JobPull.checkOut(minio, coordinationBucket, job)
