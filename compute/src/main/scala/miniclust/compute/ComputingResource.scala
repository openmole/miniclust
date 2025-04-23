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

object ComputingResource:

  def apply(core: Int) = new ComputingResource(core)

  case class Allocated(pool: ComputingResource, core: Int)
  def dispose(a: Allocated): Unit =
    a.pool.synchronized:
      a.pool.core += a.core

  def request(pool: ComputingResource, core: Int) =
    pool.synchronized:
      if pool.core >= core
      then
        pool.core -= core
        Some(Allocated(pool, core))
      else None


case class ComputingResource(private var core: Int)

