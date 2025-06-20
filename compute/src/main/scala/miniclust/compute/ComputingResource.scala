package miniclust.compute

import miniclust.compute.Compute.getClass

import java.time.Instant
import java.util.logging.Logger

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

  val logger = Logger.getLogger(getClass.getName)

  def apply(core: Int, maxCPU: Option[Int], maxMemory: Option[Int]) = new ComputingResource(core, 3600, maxCPU, maxMemory)

  case class Allocated(pool: ComputingResource, core: Int, deadLine: Long)

  def dispose(a: Allocated): Unit =
    a.pool.synchronized:
      a.pool.core += a.core

  def request(pool: ComputingResource, core: Int, time: Option[Int]) =
    pool.synchronized:
      val usage = machineUsage

      val overloaded =
        val cpuOverloaded = pool.maxCPULoad.map(usage.cpu > _).getOrElse(false)
        val memOverloaded = pool.maxMemory.map(usage.mem > _).getOrElse(false)
        cpuOverloaded || memOverloaded

      if overloaded
      then
        logger.info(s"Machine overloaded: cpu ${usage.cpu}, mem ${usage.mem} (limits ${pool.maxCPULoad}, ${pool.maxMemory})")
        None
      else
        if pool.core >= core
        then
          pool.core -= core
          Some(Allocated(pool, core, Instant.now().getEpochSecond + time.getOrElse(pool.defaultTime)))
        else None

  def freeCore(pool: ComputingResource) =
    pool.synchronized:
      pool.core

  def machineUsage =
    val script =
      """
        |cores=$(nproc)
        |load_avg=$(cut -d ' ' -f1 /proc/loadavg | awk '{printf "%d\n", $1 * 100}')
        |cpu_avg_pct=$(awk -v loadval="$load_avg" -v cores="$cores" 'BEGIN { printf "%d", loadval / cores }')
        |
        |mem_total=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
        |mem_available=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
        |mem_used=$(expr "$mem_total" - "$mem_available")
        |mem_usage=$(expr "$mem_used" \* 100 / "$mem_total")
        |
        |echo "$cpu_avg_pct,$mem_usage"
        |""".stripMargin.split('\n').filter(!_.trim.isEmpty).mkString(" && ")

    import scala.sys.process.*
    val output = Seq("bash", "-c", script).!!.trim
    val res = output.split(",")

    (cpu = res(0).toInt, mem = res(1).toInt)


case class ComputingResource(private var core: Int, defaultTime: Int, maxCPULoad: Option[Int], maxMemory: Option[Int])

