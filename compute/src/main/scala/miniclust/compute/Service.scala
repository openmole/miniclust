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

import miniclust.compute.tool.Cron.StopTask
import miniclust.message.*
import miniclust.message.MiniClust.*

import java.util.logging.Logger
import scala.util.Random
import better.files.*
import miniclust.compute.tool.Cron

object Service:

  val logger = Logger.getLogger(getClass.getName)

  def startBackgroud(
                      minio: Minio,
                      coordinationBucket: Minio.Bucket,
                      fileCache: FileCache,
                      nodeInfo: NodeInfo,
                      miniclust: Accounting.Worker.MiniClust,
                      resource: ComputingResource,
                      trashDirectory: File,
                      random: Random) =
    val removeRandom = Random(random.nextLong)

    val s1 =
      Cron.seconds(24 * 60 * 60, startDelay = Some(random.nextInt(24 * 60 * 60))): () =>
        removeOldData(minio, coordinationBucket, removeRandom)

    val s2 =
      Cron.seconds(60): () =>
        FileCache.clean(fileCache)

    val s3 =
      Cron.seconds(120, initialSchedule = true, startDelay = Some(random.nextInt(120))): () =>
        JobPull.removeAbandonedJobs(minio, coordinationBucket, removeRandom)

    val s4 =
      Cron.seconds(60): () =>
        val usage = MiniClust.Accounting.Worker.Usage(
          cores = nodeInfo.cores - ComputingResource.freeCore(resource),
          availableSpace = Tool.diskUsage(fileCache.fileFolder.toJava).usable,
          availableMemory = Tool.availableMemory,
          load = Tool.machineLoad
        )
        val currentActivity = MiniClust.Accounting.Worker(nodeInfo, miniclust, usage)
        MiniClust.Accounting.Worker.publish(minio, coordinationBucket, currentActivity)

    val s6 =
      Cron.seconds(60): () =>
        def cleanDirectory(file: File) =
          import scala.sys.process.*
          ProcessUtil.chown(file.pathAsString).!
          s"rm -rf ${file.pathAsString}".!

        trashDirectory.list.foreach(cleanDirectory)

    val s7 =
      Cron.seconds(60 * 60, startDelay = Some(random.nextInt(60 * 60))): () =>
        removeOldAccounting(minio, coordinationBucket)

    val s8 =
      Cron.seconds(60 * 60, startDelay = Some(random.nextInt(60 * 60))): () =>
        removeOldCancel(minio, coordinationBucket, removeRandom)


    StopTask.combine(s1, s2, s3, s4, s6, s7, s8)


//  def removeOldActivity(minio: Minio, coordinationBucket: Minio.Bucket) =
//    val date = Minio.date(minio)
//    def tooOld(d: Long, old: Long) = (date - d) > old
//
//    def oldActivity =
//      Minio.listObjects(minio, coordinationBucket, MiniClust.Coordination.activeWorker).filter(f => tooOld(f.lastModified.get, 5 * 60))
//
//    for f <- oldActivity.map(_.name).sliding(100, 100)
//    do Minio.delete(minio, coordinationBucket, f*)

  def tooOld(d: Long, old: Long, date: Long) = (date - d) > old

  def removeOld(minio: Minio, bucket: Minio.Bucket, prefix: String, date: Long, old: Long) =
    Minio.listAndApply(minio, bucket, prefix): f =>
      if tooOld(f.lastModified.get, old, date)
      then Minio.delete(minio, bucket, f.name)

  def removeOldAccounting(minio: Minio, coordinationBucket: Minio.Bucket) =
    val date = Minio.date(minio)
    val old = 7 * 60 * 60 * 24
    removeOld(minio, coordinationBucket, MiniClust.Coordination.jobAccountingDirectory, date, old)
    removeOld(minio, coordinationBucket, MiniClust.Coordination.workerAccountingDirectory, date, old)

  def removeOldCancel(minio: Minio, coordinationBucket: Minio.Bucket, random: Random) =
    val old = 60 * 60
    val date = Minio.date(minio)
    removeOld(minio, coordinationBucket, MiniClust.User.cancelDirectory, date, old)

  def removeOldData(minio: Minio, coordinationBucket: Minio.Bucket, random: Random) =
    val date = Minio.date(minio)
    val old = 7 * 60 * 60 * 24

    random.shuffle(Minio.listUserBuckets(minio)).take(1).foreach: b =>
      logger.info(s"Removing old data of bucket ${b}")
      removeOld(minio, b, MiniClust.User.statusDirectory, date, old)
      removeOld(minio, b, MiniClust.User.outputDirectory, date, old)



