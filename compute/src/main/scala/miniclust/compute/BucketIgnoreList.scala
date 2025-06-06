package miniclust.compute

import java.time.Instant

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


object BucketIgnoreList:
  def apply(ignoreAfter: Int, checkAfter: Int, initialBuckets: Seq[String]) =
    val l = new BucketIgnoreList(ignoreAfter, checkAfter)
    initialBuckets.foreach(l.initialize)
    l

class BucketIgnoreList(ignoreAfter: Int, checkAfter: Int):
  private val firstCheck = collection.mutable.Map[String, Long]()
  private val lastCheck = collection.mutable.Map[String, Long]()

  private def initialize(bucket: String) = synchronized:
    firstCheck.put(bucket, 0)
    lastCheck.put(bucket, 0)

  def seenEmpty(bucket: String): Unit = synchronized:
    val now = Instant.now().getEpochSecond
    firstCheck.getOrElseUpdate(bucket, now)
    lastCheck.put(bucket, now)

  def seenNotEmpty(bucket: String): Unit = synchronized:
    firstCheck.remove(bucket)
    lastCheck.remove(bucket)

  def shouldBeChecked(bucket: String): Boolean = synchronized:
    val now = Instant.now().getEpochSecond
    def emptySinceLong = firstCheck.get(bucket).map(t => (now - t) > ignoreAfter)
    def checkedRecently = lastCheck.get(bucket).map(t => (now - t) < checkAfter)

    (emptySinceLong zip checkedRecently).map: (e, c) =>
      !e || !c
    .getOrElse(true)

