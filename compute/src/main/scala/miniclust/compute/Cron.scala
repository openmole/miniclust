package miniclust.compute


import java.util.concurrent.{Callable, Executors, ThreadFactory, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

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

  class StopTask(var delay: Option[Int]):
    def stop() =
      synchronized:
        delay = None

  def seconds(delay: Int, fail: Boolean = false)(task: () => Unit): StopTask =
    val stopTask = StopTask(Some(delay))

    val scheduledTask = new Runnable:
      override def run(): Unit =
        Future:
          stopTask.synchronized:
            if stopTask.delay.isDefined
            then task()
        .onComplete:
          case Success(_) =>
            stopTask.delay.foreach: w =>
              seconds(w, fail)(task)
          case Failure(_) => if !fail then seconds(delay, fail)(task)

    scheduler.schedule(scheduledTask, delay, TimeUnit.SECONDS)
    stopTask

