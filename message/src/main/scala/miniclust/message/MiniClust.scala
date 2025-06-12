package miniclust.message

/*
 * Copyright (C) 2025 Romain Reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.github.f4b6a3.ulid.Ulid
import io.circe.*
import io.circe.syntax.*

import java.util.UUID

object MiniClust:
  type Hash = String

  object Coordination:
    def bucketName = "miniclust"
    def jobDirectory = "job"
    def workerDirectory = "worker"
    def workerActivity = s"$workerDirectory/activity"
    def accountingDirectory = s"$workerDirectory/accounting"
    def activityFile(id: String) = s"$workerActivity/${id}"

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

  def generateMessage(message: Message, pretty: Boolean = false)(using version: Message.Version): String =
    val json = message.asJson
    val versionJson =
      Json.obj:
        "version" -> Json.fromString(version.asString)
    val merged = json.deepMerge(versionJson)
    if !pretty then merged.noSpaces else merged.spaces2

  def jobId(run: Message.Submitted) = Tool.hashString(generateMessage(run))

  object WorkerActivity:
    given derivation.Configuration = Tool.jsonConfiguration
    given Codec[WorkerActivity] = derivation.ConfiguredCodec.derived

    def apply(cores: Int, nodeInfo: NodeInfo) =
      new WorkerActivity(cores, 0, nodeInfo, WorkerActivity.MiniClust())

    def publish(minio: Minio, coordinationBucket: Minio.Bucket, activity: WorkerActivity) =
      val content = activity.asJson.noSpaces
      Minio.upload(minio, coordinationBucket, content, Coordination.activityFile(activity.nodeInfo.id))

    case class MiniClust(
      version: String = miniclust.BuildInfo.version,
      build: Long = miniclust.BuildInfo.buildTime) derives derivation.ConfiguredCodec

  case class WorkerActivity(
    cores: Int,
    used: Int,
    nodeInfo: NodeInfo,
    miniclust: WorkerActivity.MiniClust)


  object NodeInfo:
    given derivation.Configuration = Tool.jsonConfiguration
    given Codec[NodeInfo] = derivation.ConfiguredCodec.derived

    def apply(key: String, hostname: Option[String]) =
      new NodeInfo(
        ip = Tool.queryExternalIP.getOrElse("NA"),
        id = UUID.randomUUID().toString,
        key = key,
        hostname = hostname
      )

  case class NodeInfo(
    id: String,
    ip: String,
    key: String,
    hostname: Option[String])


  object JobResourceUsage:
    given derivation.Configuration = Tool.jsonConfiguration
    given Codec[JobResourceUsage] = derivation.ConfiguredCodec.derived

    def publish(minio: Minio, coordinationBucket: Minio.Bucket, usage: JobResourceUsage) =
      import com.github.f4b6a3.ulid.*
      val content = usage.asJson.noSpaces
      val ulid = UlidCreator.getUlid
      val path = s"${Coordination.accountingDirectory}/${ulid.toLowerCase}"
      Minio.upload(minio, coordinationBucket, content, path)

    def parse(j: String): JobResourceUsage = parser.parse(j).toTry.get.as[JobResourceUsage].toTry.get

  case class JobResourceUsage(
    bucket: String,
    nodeInfo: NodeInfo,
    second: Long,
    resource: Seq[Message.Resource],
    finalState: Message)