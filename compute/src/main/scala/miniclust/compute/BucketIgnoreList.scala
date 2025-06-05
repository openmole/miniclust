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

class BucketIgnoreList(ignoreAfter: Int, checkAfter: Int):
  val firstCheck = collection.mutable.Map[String, Long]()
  val lastCheck = collection.mutable.Map[String, Long]()

  def seenEmpty(bucket: String): Unit = synchronized:
    val now = Instant.now().getEpochSecond
    firstCheck.getOrElseUpdate(bucket, now)
    lastCheck.put(bucket, now)

  def shouldBeChecked(bucket: String) = synchronized:
    val now = Instant.now().getEpochSecond
    def emptySinceLong = firstCheck.get(bucket).map(t => (now - t) > ignoreAfter)
    def checkedRecently = lastCheck.get(bucket).map(t => (now - t) < checkAfter)

    (emptySinceLong zip checkedRecently).map: (e, c) =>
      !e || !c
    .getOrElse(true)


  def notEmpty(bucket: String) = synchronized:
    firstCheck.remove(bucket)
    lastCheck.remove(bucket)