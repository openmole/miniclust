package miniclust.compute

import miniclust.message.Minio.{Bucket, listObjects}
import miniclust.message.*

import java.io.FileNotFoundException
import java.time.{Duration, ZonedDateTime}
import java.util.concurrent.Executors
import scala.util.*
import gears.async.*
import gears.async.default.given
import miniclust.compute.JobPull.RunningJob.{name, path}


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
  case class SubmittedJob(bucket: Bucket, id: String)

  object RunningJob:
    def path(j: SubmittedJob | RunningJob) =
      s"${MiniClust.Coordination.jobDirectory}/${RunningJob.name(j)}"

    def name(j: SubmittedJob | RunningJob) =
      j match
        case j: SubmittedJob => s"${j.bucket.name}-${j.id}"
        case j: RunningJob => s"${j.bucketName}-${j.id}"

    def parse(name: String, ping: Long): RunningJob =
      val index = name.indexOf('-')
      RunningJob(bucketName = name.take(index), id = name.drop(index + 1), ping = ping)

  case class RunningJob(bucketName: String, id: String, ping: Long)

  def startHeartBeat(coordinationBucket: Bucket, run: Message.Submitted, job: SubmittedJob) =
    val message = MiniClust.generateMessage(run)
    Cron.seconds(5): () =>
      Minio.upload(coordinationBucket, message, JobPull.RunningJob.path(job), contentType = Some(Minio.jsonContentType))

  def canceled(bucket: Bucket, id: String) =
    try
      val content = Minio.content(bucket, MiniClust.User.jobStatus(id))
      MiniClust.parseMessage(content) match
        case c: Message.Canceled => true
        case _ => false
    catch
      case e: FileNotFoundException => false

  def submittedJobs(bucket: Bucket) =
    val prefix = s"${MiniClust.User.submittedDirectory}/"
    Minio.listObjects(bucket, prefix = prefix).map: i =>
      SubmittedJob(bucket, i.objectName().drop(prefix.size))

  def runningJobs(coordinationBucket: Bucket) =
    val prefix = s"${MiniClust.Coordination.jobDirectory}/"
    Minio.listObjects(coordinationBucket, prefix = prefix).filterNot(_.isDir).map: i =>
      RunningJob.parse(i.objectName().drop(prefix.size), i.lastModified().toEpochSecond)

  def selectJob(server: Minio.Server, coordinationBucket: Bucket)(using config: JobPullConfig): Option[SubmittedJob] =
    val runningJob = runningJobs(coordinationBucket)
    val runningByBucket = runningJob.groupBy(r => r.bucketName).view.mapValues(_.size).toMap

    val submittedJob =
      Minio.listUserBuckets(server).
        sortBy(b => runningByBucket.getOrElse(b.name, 0)).
        map(submittedJobs).
        filterNot(_.isEmpty)

    if submittedJob.isEmpty
    then None
    else
      val u = submittedJob.head
      Some(u(config.random.nextInt(u.size)))

  def validate(s: SubmittedJob) =
    Try:
      val content = Minio.content(s.bucket, MiniClust.User.submittedJob(s.id))

      if Tool.hashString(content) != s.id then throw IllegalArgumentException(s"Invalid job id ${s.id}")

      val run =
        MiniClust.parseMessage(content) match
          case r: Message.Submitted => r
          case m => throw new IllegalArgumentException(s"Invalid message type ${m.getClass}, should be Run")

      if run.account.bucket != s.bucket.name then throw IllegalArgumentException(s"Incorrect bucket name ${run.account.bucket}")

      run

  def checkIn(coordinationBucket: Bucket, job: SubmittedJob): Boolean =
    Minio.upload(coordinationBucket, job.id, RunningJob.path(job), overwrite = false, contentType = Some(Minio.jsonContentType))

  def checkOut(coordinationBucket: Bucket, job: SubmittedJob): Unit =
    Minio.delete(coordinationBucket, RunningJob.path(job))

  def removeAbandonedJobs(server: Minio.Server, coordinationBucket: Bucket) =
    val date = Minio.date(coordinationBucket.server)
    val prefix = s"${MiniClust.Coordination.jobDirectory}/"
    Minio.lazyListObjects(coordinationBucket, prefix = prefix): it =>
      def listOldJobs =
        it.view.filterNot(_.get().isDir).map: i =>
          val o = i.get()
          RunningJob.parse(o.objectName().drop(prefix.length), Option(o.lastModified()).map(_.toEpochSecond).getOrElse(0))
        .filter:
          o =>
            (date - o.ping) > 60

      for
        j <- listOldJobs
      do
        util.Try:
          Minio.upload(
            Bucket(server, j.bucketName),
            MiniClust.generateMessage(Message.Failed(j.id, "Job abandoned, please resubmit", Message.Failed.Reason.Abandoned)),
            MiniClust.User.jobStatus(j.id),
            contentType = Some(Minio.jsonContentType)
          )

        Minio.delete(coordinationBucket, s"${RunningJob.path(j)}")

        logger.info(s"Removed job without heartbeat: ${j.id}")


  def pull(server: Minio.Server, coordinationBucket: Bucket)(using config: JobPullConfig): (SubmittedJob, Message.Submitted) =
    val job = selectJob(server, coordinationBucket)

    job match
      case Some(job) =>
        if checkIn(coordinationBucket, job)
        then
          logger.info(s"${job.id}: checked in")
          if !canceled(job.bucket, job.id)
          then
            validate(job) match
              case Failure(exception) =>
                logger.info(s"${job.id}: failed to validate, ${exception.getMessage}")
                Minio.upload(job.bucket, MiniClust.generateMessage(Message.Failed(job.id, exception.getMessage, Message.Failed.Reason.Invalid)), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
                Minio.delete(job.bucket, MiniClust.User.submittedJob(job.id))
                pull(server, coordinationBucket)
              case Success(run) =>
                Minio.upload(job.bucket, MiniClust.generateMessage(Message.Running(job.id)), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
                Minio.delete(job.bucket, MiniClust.User.submittedJob(job.id))
                (job, run)
          else
            Minio.upload(job.bucket, MiniClust.generateMessage(Message.Canceled(job.id, true)), MiniClust.User.jobStatus(job.id), contentType = Some(Minio.jsonContentType))
            pull(server, coordinationBucket)
        else pull(server, coordinationBucket)
      case None =>
        Thread.sleep(config.random.nextInt(2000))
        pull(server, coordinationBucket)
