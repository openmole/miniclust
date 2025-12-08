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
    def accountingDirectory = "accounting"
    def workerAccountingDirectory = s"$accountingDirectory/worker"
    def jobAccountingDirectory = s"$accountingDirectory/job"

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


  object NodeInfo:
    given derivation.Configuration = Tool.jsonConfiguration
    given Codec[NodeInfo] = derivation.ConfiguredCodec.derived

    def apply(key: String, hostname: Option[String], id: String, cores: Int, machineCores: Int, space: Long, memory: Long) =
      new NodeInfo(
        ip = Tool.queryExternalIP.getOrElse("NA"),
        id = id,
        key = key,
        hostname = hostname,
        cores = cores,
        machineCores = machineCores,
        space = space,
        memory = memory
      )

  case class NodeInfo(
    id: String,
    ip: String,
    key: String,
    hostname: Option[String],
    cores: Int,
    machineCores: Int,
    space: Long,
    memory: Long)

  object Accounting:
    object Job:
      given derivation.Configuration = Tool.jsonConfiguration
      given Codec[Job] = derivation.ConfiguredCodec.derived

      def publish(minio: Minio, coordinationBucket: Minio.Bucket, usage: Job) =
        import com.github.f4b6a3.ulid.*
        val content = usage.asJson.noSpaces
        val ulid = UlidCreator.getUlid
        val path = s"${Coordination.jobAccountingDirectory}/${ulid.toLowerCase}"
        Minio.upload(minio, coordinationBucket, content, path)

      def parse(j: String): Job = parser.parse(j).toTry.get.as[Job].toTry.get

    case class Job(
      bucket: String,
      nodeInfo: NodeInfo,
      second: Long,
      resource: Seq[Message.Resource],
      finalState: Message)

    object Worker:
      given derivation.Configuration = Tool.jsonConfiguration

      given Codec[Worker] = derivation.ConfiguredCodec.derived

      def publish(minio: Minio, coordinationBucket: Minio.Bucket, activity: Worker) =
        val content = activity.asJson.noSpaces
        import com.github.f4b6a3.ulid.*
        val ulid = UlidCreator.getUlid
        Minio.upload(minio, coordinationBucket, content, s"${Coordination.workerAccountingDirectory}/${ulid.toLowerCase}")

      def parse(j: String): Worker = parser.parse(j).toTry.get.as[Worker].toTry.get


      case class MiniClust(
        version: String = miniclust.BuildInfo.version,
        build: Long = miniclust.BuildInfo.buildTime) derives derivation.ConfiguredCodec

      case class Usage(
        cores: Int,
        availableSpace: Long,
        availableMemory: Long,
        load: Double) derives derivation.ConfiguredCodec

    case class Worker(
      nodeInfo: NodeInfo,
      miniclust: Worker.MiniClust,
      usage: Worker.Usage)
