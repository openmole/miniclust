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


object UsageHistory:
  case class Hour(hour: Long, consumedSeconds: Long)
  def currentHour = Instant.now().getEpochSecond / 3600
  def elapsedSeconds(t: Instant) = Instant.now().getEpochSecond - t.getEpochSecond

  def clean(map: Map[String, List[UsageHistory.Hour]], expire: Long) =
    map.map: (k, v) =>
      k -> v.filterNot(_.hour < expire)
    .filter(_._2.nonEmpty)

class UsageHistory(expireAfterHour: Int):
  var accounts: Map[String, List[UsageHistory.Hour]] = Map()

  def updateAccount(id: String, currentHour: Long, consumedSeconds: Long) = synchronized:
    val info = accounts.getOrElse(id, List[UsageHistory.Hour]())
    val newAccounting: List[UsageHistory.Hour] =
      info match
        case head :: tail if head.hour == currentHour => head.copy(consumedSeconds = head.consumedSeconds + consumedSeconds) :: tail
        case _ => UsageHistory.Hour(currentHour, consumedSeconds) :: info

    accounts =
      UsageHistory.clean(
        accounts.updated(id, newAccounting),
        currentHour - expireAfterHour
      )

  def quantity(id: String) = synchronized:
    accounts.getOrElse(id, List()).map(_.consumedSeconds).sum
