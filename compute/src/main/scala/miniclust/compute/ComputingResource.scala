package miniclust.compute

import miniclust.compute.Compute.getClass

import java.time.Instant
import java.util.logging.Logger

import squants.information.*
import squants.time.*

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

  def apply(core: Int) =
    new ComputingResource(core)

  case class Allocated(core: Int, deadLine: Long, time: Time)

  def dispose(a: Allocated, pool: ComputingResource): Unit =
    pool.synchronized:
      pool.core += a.core

  def request(pool: ComputingResource, core: Int, time: Option[Time], memory: Option[Information])(using context: ComputingContext) =
    pool.synchronized:
      val usage = machineUsage
      val memoryCoreRequest =
        memory.map: m =>
          (m / context.memoryPerCore).ceil.toInt

      val coreRequest  = Math.max(core, memoryCoreRequest.getOrElse(0))

      val overloaded =
        val cpuOverloaded = context.maxCPULoad.map(usage.cpu > _).getOrElse(false)
        val memOverloaded = context.maxMemory.map(usage.mem > _).getOrElse(false)
        cpuOverloaded || memOverloaded

      if overloaded
      then
        logger.info(s"Machine overloaded: cpu ${usage.cpu}, mem ${usage.mem} (limits ${context.maxCPULoad}, ${context.maxMemory})")
        None
      else
        if coreRequest >= 1 && pool.core >= coreRequest
        then
          pool.core -= coreRequest
          Some(Allocated(coreRequest, Instant.now().getEpochSecond + time.getOrElse(context.defaultTime).toSeconds.toLong, time.getOrElse(context.defaultTime)))
        else None

  def freeCore(pool: ComputingResource) =
    pool.synchronized:
      pool.core

  def machineUsage =
    val script =
      """
        |cores=$(nproc)
        |load_avg=$(cut -d ' ' -f1 /proc/loadavg)
        |cpu_avg_pct=$(awk -v loadavg="$load_avg" -v cores="$cores" 'BEGIN { printf("%.2f", (loadavg / cores) * 100) }')
        |mem_total=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
        |mem_available=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
        |mem_used=$(expr "$mem_total" - "$mem_available")
        |mem_usage=$(expr "$mem_used" \* 100 / "$mem_total")
        |
        |echo "$cpu_avg_pct,$mem_usage"
        |""".stripMargin.split('\n').filter(_.trim.nonEmpty).mkString(" && ")

    import scala.sys.process.*
    val output = Seq("bash", "-c", script).!!.trim
    val res = output.split(",")

    (cpu = res(0).toDouble, mem = res(1).toInt)


case class ComputingResource(private var core: Int)

object ComputingContext:
  def apply(memoryPerCore: Information, maxCPU: Option[Int], maxMemory: Option[Int], defaultTime: Time = Hours(1)) =
    new ComputingContext(memoryPerCore, defaultTime, maxCPU, maxMemory)

case class ComputingContext(
  memoryPerCore: Information,
  defaultTime: Time,
  maxCPULoad: Option[Int],
  maxMemory: Option[Int])
