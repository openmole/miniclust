package miniclust.message

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

import io.circe.*
import io.circe.syntax.*
import io.circe.generic.auto.*

object MiniClust:
  type Hash = String

  object Coordination:
    def bucketName = "miniclust"
    def jobDirectory = "job"
  //def jobFile(id: String) = s"$jobDirectory/$id"

  object User:
    def submitBucketTag = ("miniclust", "submit")
    def jobDirectory = "job"
    def outputDirectory = s"${jobDirectory}/output"

    def submitDirectory = s"${jobDirectory}/submit"
    def submittedJob(id: String) = s"${submitDirectory}/$id"

    def cancelDirectory = s"${jobDirectory}/cancel"
    def canceledJob(id: String) = s"${cancelDirectory}/$id"

    def statusDirectory = s"${jobDirectory}/status"
    def jobStatus(id: String) = s"${statusDirectory}/$id"
    def jobOutputDirectory(id: String) = s"${outputDirectory}/$id"
    def jobOutputPath(id: String, name: String) = s"${outputDirectory}/$id/$name"


  def parseMessage(s: String) =
    val json = parser.parse(s).toTry.get.asObject.map(_.remove("version")).asJson
    json.as[Message].toTry.get

  def generateMessage(message: Message)(using version: Message.Version): String =
    val json = message.asJson
    val versionJson =
      Json.obj:
        "version" -> Json.fromString(version.asString)
    json.deepMerge(versionJson).noSpaces

  def jobId(run: Message.Submitted) = Tool.hashString(generateMessage(run))

