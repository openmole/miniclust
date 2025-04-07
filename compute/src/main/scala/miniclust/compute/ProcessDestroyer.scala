package miniclust.compute

/*
 * Copyright (C) 2025 Romain Reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.Collections
import scala.sys.*

class ProcessDestroyer:
  private val processes = Collections.synchronizedSet(new java.util.HashSet[scala.sys.process.Process]())
  private val shutdownHook: ShutdownHookThread = ShutdownHookThread(destroyProcesses())

  def add(process: scala.sys.process.Process): Boolean =
    processes.add(process)

  def remove(process: scala.sys.process.Process): Boolean =
    processes.remove(process)

  def destroyProcesses(): Unit =
    processes.synchronized:
      processes.forEach: p =>
        try p.destroy()
        catch
          case _: Exception =>

      processes.clear()

  def close() =
    shutdownHook.remove()