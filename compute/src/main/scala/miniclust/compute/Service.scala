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

import miniclust.message.*

import scala.util.Random

object Service:

  def startBackgroud(server: Minio.Server, coordinationBucket: Minio.Bucket, fileCache: FileCache, random: Random) =
    val old = 7 * 60 * 60 * 24
    val removeRandom = Random(random.nextLong)
    Cron.seconds(5 * 60): () =>
      removeOldData(server, coordinationBucket, old, removeRandom)
    Cron.seconds(60): () =>
      FileCache.clean(fileCache)

  def removeOldData(server: Minio.Server, coordinationBucket: Minio.Bucket, old: Int, random: Random) =
    val date = Minio.date(coordinationBucket.server)
    def tooOld(d: Long) = (date - d) > old

    random.shuffle(Minio.listUserBuckets(server)).take(1).foreach: b =>
      val oldStatus = Minio.listObjects(b, MiniClust.User.statusDirectory, recursive = true).filter(f => tooOld(f.lastModified().toEpochSecond))
      val oldOutputs = Minio.listObjects(b, MiniClust.User.outputDirectory, recursive = true).filter(f => tooOld(f.lastModified().toEpochSecond))
      Minio.delete(b, (oldStatus ++ oldOutputs).map(_.objectName()) *)
