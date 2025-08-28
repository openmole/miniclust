package miniclust.compute.tool

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

class TimeCache[T](time: Int):
  private var cache: Option[T] = None
  private var cacheExpiration = Long.MinValue

  def apply(f: () => T): T = synchronized:
    if Instant.now().getEpochSecond > cacheExpiration
    then cache = None
    
    cache match
      case None =>
        val v = f()
        cache = Some(v)
        cacheExpiration = Instant.now().getEpochSecond + time
        v
      case Some(c) => c


