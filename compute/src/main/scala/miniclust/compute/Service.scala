package miniclust.compute

/*
 * Copyright (C) 2025 Romain Reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import miniclust.compute.Cron.StopTask
import miniclust.message.*
import miniclust.message.MiniClust.WorkerActivity

import java.util.logging.Logger
import scala.util.Random

object Service:

  val logger = Logger.getLogger(getClass.getName)

  def startBackgroud(minio: Minio, coordinationBucket: Minio.Bucket, fileCache: FileCache, activity: WorkerActivity, resource: ComputingResource, random: Random) =
    val removeRandom = Random(random.nextLong)
    val s1 =
      Cron.seconds(5 * 60): () =>
        removeOldData(minio, coordinationBucket, removeRandom)
    val s2 =
      Cron.seconds(60): () =>
        FileCache.clean(fileCache)
    val s3 =
      Cron.seconds(60, initialSchedule = true): () =>
        JobPull.removeAbandonedJobs(minio, coordinationBucket)
    val s4 =
      Cron.seconds(60): () =>
        val currentActivity = activity.copy(used = activity.cores - ComputingResource.freeCore(resource))
        MiniClust.WorkerActivity.publish(minio, coordinationBucket, currentActivity)

    StopTask.combine(s1, s2, s3, s4)

  def removeOldData(minio: Minio, coordinationBucket: Minio.Bucket, random: Random) =
    val date = Minio.date(minio)
    def tooOld(d: Long, old: Long) = (date - d) > old

    def oldActivity =
      Minio.listObjects(minio, coordinationBucket, MiniClust.Coordination.workerActivity).filter(f => tooOld(f.lastModified.get, 5 * 60))

    random.shuffle(Minio.listUserBuckets(minio)).take(1).foreach: b =>
      val old = 7 * 60 * 60 * 24
      logger.info(s"Removing old data of bucket ${b}")
      def oldStatus = Minio.listObjects(minio, b, MiniClust.User.statusDirectory, recursive = true).filter(f => tooOld(f.lastModified.get, old))
      def oldOutputs = Minio.listObjects(minio, b, MiniClust.User.outputDirectory, recursive = true).filter(f => tooOld(f.lastModified.get, old))
      def oldCancel = Minio.listObjects(minio, b, MiniClust.User.cancelDirectory, recursive = true).filter(f => tooOld(f.lastModified.get, old))

      for
        f <- (oldActivity ++ oldStatus ++ oldOutputs ++ oldCancel).map(_.name).sliding(100, 100)
      do
        Minio.delete(minio, b, f*)



