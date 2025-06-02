package miniclust.application

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

object Tool:

  object Counter:
    def apply(min: Int, max: Int) = new Counter(min, min, max)

  class Counter(private var v: Int, min: Int, max: Int):
    def tryIncrement(): Boolean = synchronized:
      if v < max
      then
        v += 1
        true
      else false

    def tryDecrement(): Boolean = synchronized:
      if v > min
      then
        v -= 1
        true
      else false

    def value: Int = v