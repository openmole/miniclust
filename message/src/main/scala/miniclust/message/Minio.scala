package miniclust.message

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


import miniclust.message.MiniClust
import org.apache.http.client.methods.HttpHead

import java.io.{File, FileInputStream, FileNotFoundException, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.*
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.utils.AttributeMap

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


object Minio:
  def jsonContentType = "application/json"

  case class Server(url: String, user: String, password: String, timeout: Int = 600, insecure: Boolean = false)
  case class Bucket(name: String)

  def apply(server: Server) =
    new Minio(server)
  //val (c, minio) = client(server)
  //new Minio(minio, server, c)

  def withClient[T](minio: Minio)(f: S3Client => T): T =
    val c = client(minio.server)
    try f(c)
    finally c.close()
  //    val c = client(minio.server)
  //    try f(c._2)
  //    finally c._2.close()

  private def closeHttpClient(httpClient: SdkHttpClient) =
    httpClient.close()

  private def httpClient(server: Server) =
    if server.insecure
    then

      val attributes = AttributeMap.builder().put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true).build()
      ApacheHttpClient.builder().buildWithDefaults(attributes)
    else ApacheHttpClient.builder().build()

  private def client(server: Server) =
    val c = httpClient(server)
    S3Client.builder()
      .endpointOverride(java.net.URI.create(server.url))
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(server.user, server.password)
      ))
      .httpClient(c)
      .serviceConfiguration(S3Configuration.builder()
        .pathStyleAccessEnabled(true)
        .build()
      ).build()


  def bucket(minio: Minio, name: String, create: Boolean = true) =
    if create
    then
      try
        withClient(minio): client =>
          client.createBucket(CreateBucketRequest.builder().bucket(name).build())
      catch
        case e: software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException =>

    Bucket(name)


  def userBucket(minio: Minio, login: String, create: Boolean = true) =
    withClient(minio): c =>
      if create
      then
        try c.createBucket(CreateBucketRequest.builder().bucket(login).build())
        catch
          case e: software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException =>

        val tagging: Tagging = Tagging.builder().tagSet(Tag.builder().key(MiniClust.User.submitBucketTag._1).value(MiniClust.User.submitBucketTag._2).build()).build()
        c.putBucketTagging(PutBucketTaggingRequest.builder().bucket(login).tagging(tagging).build())

      bucket(minio, login, false)

  def listUserBuckets(minio: Minio): Seq[Bucket] =
    withClient(minio): c =>
      c.listBuckets().buckets().asScala.flatMap: bucket =>
        Try:
          c.getBucketTagging(
            GetBucketTaggingRequest.builder().bucket(bucket.name()).build()
          )
        .toOption.flatMap: t =>
          if t.tagSet().asScala.map(t => t.key() -> t.value()).toMap.get(MiniClust.User.submitBucketTag._1).contains(MiniClust.User.submitBucketTag._2)
          then Some(Bucket(bucket.name()))
          else None
      .toSeq


  def delete(minio: Minio, bucket: Bucket, path: String*): Unit =
    withClient(minio): c =>
      val delete = Delete.builder().objects(path.map(p => ObjectIdentifier.builder().key(p).build()).asJava).build()
      c.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket.name).delete(delete).build())

  def deleteRecursive(minio: Minio, bucket: Bucket, path: String): Unit =
    withClient(minio): c =>
      val listRequest = ListObjectsV2Request.builder()
        .bucket(bucket.name)
        .prefix(path)
        .build()

      var more = true

      while more
      do
        val listedObjects = c.listObjectsV2(listRequest)
        if !listedObjects.isTruncated then more = false

        val keys = listedObjects.contents().asScala.map(_.key())

        if keys.nonEmpty
        then
          val objectIdentifiers = keys.map(k => ObjectIdentifier.builder().key(k).build())
          delete(minio, bucket, keys.toSeq*)


  def download(minio: Minio, bucket: Bucket, path: String, local: File) =
    withClient(minio): c =>
      try
        val stream = c.getObject(GetObjectRequest.builder().bucket(bucket.name).key(path).build())
        try
          local.getParentFile.mkdirs()
          val fos = new FileOutputStream(local)
          try stream.transferTo(fos)
          finally fos.close()
        finally stream.close()
      catch
        case e: software.amazon.awssdk.services.s3.model.NoSuchKeyException => throw FileNotFoundException(s"File ${path} not found in bucket ${bucket.name}")

  def content(minio: Minio, bucket: Bucket, path: String): String =
    withClient(minio): c =>
      try
        val stream = c.getObject(GetObjectRequest.builder().bucket(bucket.name).key(path).build())
        try scala.io.Source.fromInputStream(stream).mkString
        finally stream.close()
      catch
        case e: software.amazon.awssdk.services.s3.model.NoSuchKeyException => throw FileNotFoundException(s"File ${path} not found in bucket ${bucket.name}")

  def upload(minio: Minio, bucket: Bucket, local: File | String, path: String, overwrite: Boolean = true, contentType: Option[String] = None): Boolean =
    withClient(minio): c =>
      val headers =
        if !overwrite then Map("If-None-Match" -> "*").asJava else Map().asJava

      val arg =
        val b =
          PutObjectRequest.builder().
            bucket(bucket.name).
            key(path)

        val b2 =
          contentType match
            case Some(c) => b.contentType(c)
            case None => b

        if overwrite then b2 else b2.ifNoneMatch("*")

      try
        local match
          case local: File =>

            c.putObject(arg.build(), local.toPath)
          case local: String =>
            val requestBody = RequestBody.fromString(local)
            contentType.foreach(arg.contentType)
            c.putObject(arg.build(), requestBody)
        true
      catch
        case e: S3Exception if e.statusCode() == 412 => false


  case class MinioObject(name: String, dir: Boolean, lastModified: Option[Long])

  def listObjects(minio: Minio, bucket: Bucket, prefix: String, recursive: Boolean = false) =
    withClient(minio): c =>
      val listRequest =
        val r = ListObjectsV2Request.builder()
          .bucket(bucket.name)
          .prefix(prefix)

        if recursive then r.delimiter("/") else r

      var token: String = null
      var more = true
      val response = scala.collection.mutable.ListBuffer[MinioObject]()

      while more
      do
        val listedObjects = c.listObjectsV2(listRequest.continuationToken(token).build())

        token = listedObjects.nextContinuationToken()
        if !listedObjects.isTruncated then more = false

        response.addAll:
          listedObjects.contents().asScala.map: c =>
            MinioObject(c.key(), c.key().endsWith("/"), Option(c.lastModified()).map(_.getEpochSecond))

      response.toSeq

//  def lazyListObjects[T](minio: S3Minio, bucket: Bucket, prefix: String, recursive: Boolean = false)(f: Iterable[Result[Item]] => T): T =
//    withClient(minio): c =>
//      f:
//        c.listObjects(
//          ListObjectsArgs.builder().bucket(bucket.name).prefix(prefix).recursive(recursive).build()
//        ).asScala
//
  def exists(minio: Minio, bucket: Bucket, prefix: String) =
    withClient(minio): c =>
      try
        c.headObject(
          HeadObjectRequest.builder().bucket(bucket.name).key(prefix).build()
        )
        true
      catch
        case e: NoSuchKeyException => false

  def date(minio: Minio) =

    val httpRequest =
      SdkHttpRequest.builder()
      .uri(java.net.URI.create(minio.server.url))
      .method(SdkHttpMethod.HEAD)
      .build()

    val client = httpClient(minio.server)
    try

      val response = client.prepareRequest(HttpExecuteRequest.builder().request(httpRequest).build()).call()

      val dateHeader = response.httpResponse().headers().get("Date").asScala.head
      val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
      ZonedDateTime.parse(dateHeader, formatter).toEpochSecond
    finally closeHttpClient(client)


case class Minio(server: Minio.Server):
  def close() = () //httpClient.connectionPool().close()