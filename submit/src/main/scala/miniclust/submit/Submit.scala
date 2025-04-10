package miniclust.submit

import io.minio.messages.Status
import miniclust.message.*

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

import scala.concurrent.*
import scala.concurrent.ExecutionContext.Implicits.*
import scala.concurrent.duration.*

@main def run(url: String, user: String, password: String) =
  val server = Minio.Server(url, user, password, insecure = true)

  val bucket = Minio.userBucket(server, user)

  val testFile = new java.io.File("/tmp/test.txt")
  val writer = new java.io.PrintWriter(testFile)
  writer.write("youpi")
  writer.close()

  Minio.upload(bucket, testFile, "test.txt")

  val run =
    Message.Submitted(
      Account(bucket.name),
      "singularity help",
      inputFile = Seq(InputFile("test.txt", "test.txt", Some(Tool.hashFile(testFile)))),
      stdOut = Some("output.txt")
    )

  val id = submit(bucket, run)


  var s: Message = run
  while
    s = status(bucket, id)
    !s.finished
  do
    println(s)
    Thread.sleep(1000)

  println(s)
  println(Minio.content(bucket, MiniClust.User.jobOutputPath(id, "output.txt")))

//
//  val futs = Future.sequence:
//    for
//      i <- 0 to 1
//    yield
//      Future:
//        submit(bucket, run.copy(noise = s"$i"))
//
//  Await.result(futs, Duration.Inf)



import scala.util.*

def submit(bucket: Minio.Bucket, run: Message.Submitted) =
  val content = MiniClust.generateMessage(run)
  val id = Tool.hashString(content)
  Minio.upload(bucket, content, MiniClust.User.submittedJob(id), contentType = Some(Minio.jsonContentType))
  id

def status(bucket: Minio.Bucket, id: String) =
  def submitted =
    Try:
      val content = Minio.content(bucket, MiniClust.User.submittedJob(id))
      MiniClust.parseMessage(content)
    .toOption

  def status =
    Try:
      val content = Minio.content(bucket, MiniClust.User.jobStatus(id))
      MiniClust.parseMessage(content)

  submitted.getOrElse(status.get)

def cancel(bucket: Minio.Bucket, id: String) =
  Minio.upload(bucket, MiniClust.generateMessage(Message.Canceled(id)), MiniClust.User.jobStatus(id), contentType = Some(Minio.jsonContentType))

def clean(bucket: Minio.Bucket, id: String) =
  Minio.deleteRecursive(bucket, MiniClust.User.jobOutputDirectory(id))
