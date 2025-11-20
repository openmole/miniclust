package miniclust.compute.tool

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

import java.util.concurrent.{Callable, Executors, ThreadFactory, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*


object Background:
  def run(t: => Unit) =
    import Cron.given_ExecutionContext
    Future(t)

object Cron:

  val daemonThreadFactory = new ThreadFactory:
    override def newThread(r: Runnable): Thread =
      val t = Thread(r)
      t.setDaemon(true)
      t

  val scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory)
  given ExecutionContext = ExecutionContext.fromExecutor(Executors.newVirtualThreadPerTaskExecutor())


  object StopTask:
    case class CombinedStopTask(tasks: Seq[StopTask]):
      def stop() = tasks.foreach(_.stop())

    class UnitStopTask(var delay: Option[Int]) extends StopTask:
      def stop() =
        synchronized:
          delay = None

    def combine(tasks: StopTask*) = CombinedStopTask(tasks)

  sealed trait StopTask:
    def stop(): Unit

  def seconds(delay: Int, fail: Boolean = false, initialSchedule: Boolean = false, startDelay: Option[Int] = None)(task: () => Unit): StopTask =
    val stopTask = StopTask.UnitStopTask(Some(delay))

    def schedule(initial: Boolean, first: Boolean): Unit =
      val scheduledTask = new Runnable:
        override def run(): Unit =
          Future:
            stopTask.synchronized:
              if stopTask.delay.isDefined
              then task()
          .onComplete:
            case Success(_) =>
              stopTask.delay.foreach: w =>
                schedule(false, false)
            case Failure(_) =>
              if !fail
              then schedule(false, false)

      if initial
      then scheduledTask.run()
      else
        def delayValue = if first then startDelay.getOrElse(delay) else delay
        scheduler.schedule(scheduledTask, delayValue, TimeUnit.SECONDS)

    schedule(initialSchedule, first = true)
    stopTask

