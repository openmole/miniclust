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

import scala.util.Random

object Service:

  def startBackgroud(minio: Minio, coordinationBucket: Minio.Bucket, fileCache: FileCache, random: Random) =
    val old = 7 * 60 * 60 * 24
    val removeRandom = Random(random.nextLong)
    val s1 =
      Cron.seconds(5 * 60): () =>
        removeOldData(minio, coordinationBucket, old, removeRandom)
    val s2 =
      Cron.seconds(60): () =>
        FileCache.clean(fileCache)
    val s3 =
      Cron.seconds(60): () =>
        JobPull.removeAbandonedJobs(minio, coordinationBucket)
    StopTask.combine(s1, s2, s3)

  def removeOldData(minio: Minio, coordinationBucket: Minio.Bucket, old: Int, random: Random) =
    val date = Minio.date(minio)
    def tooOld(d: Long) = (date - d) > old

    random.shuffle(Minio.listUserBuckets(minio)).take(1).foreach: b =>
      val oldStatus = Minio.listObjects(minio, b, MiniClust.User.statusDirectory, recursive = true).filter(f => tooOld(f.lastModified.get))
      val oldOutputs = Minio.listObjects(minio, b, MiniClust.User.outputDirectory, recursive = true).filter(f => tooOld(f.lastModified.get))
      val oldCancel = Minio.listObjects(minio, b, MiniClust.User.cancelDirectory, recursive = true).filter(f => tooOld(f.lastModified.get))

      Minio.delete(minio, b, (oldStatus ++ oldOutputs ++ oldCancel).map(_.name) *)

