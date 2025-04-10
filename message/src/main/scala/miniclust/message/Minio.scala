package miniclust.message

import io.minio.errors.*

import java.io.{File, FileInputStream, FileNotFoundException, FileOutputStream}
import okhttp3.*

import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

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

  import io.minio.*

  case class Server(url: String, user: String, password: String, timeout: Int = 60, insecure: Boolean = false)
  case class Bucket(server: Server, name: String)

  def withClient[T](server: Server)(f: MinioClient => T): T =
    val c = client(server)
    try f(c)
    finally c.close()

  def httpClient(server: Server) =
    val builder =
      new OkHttpClient.Builder()
        .connectTimeout(server.timeout, TimeUnit.SECONDS)
        .readTimeout(server.timeout, TimeUnit.SECONDS)
        .writeTimeout(server.timeout, TimeUnit.SECONDS)

    if server.insecure
    then
      import javax.net.ssl.*
      import java.security.cert.*
      import java.security.*

      val trustAllCerts =
        Array[TrustManager]:
          new X509TrustManager:
            override def getAcceptedIssuers = Array[X509Certificate]()
            override def checkClientTrusted(certs: Array[X509Certificate], authType: String): Unit = {}
            override def checkServerTrusted(certs: Array[X509Certificate], authType: String): Unit = {}

      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(null, trustAllCerts, new SecureRandom())
      val sslSocketFactory = sslContext.getSocketFactory

      // Bypass hostname verification
      val hostnameVerifier =
        new HostnameVerifier:
          override def verify(hostname: String, session: SSLSession): Boolean = true


      builder.setHostnameVerifier$okhttp(hostnameVerifier)
      builder.sslSocketFactory(sslSocketFactory, trustAllCerts(0).asInstanceOf[X509TrustManager])

    builder.build()

  def client(server: Server) =
    MinioClient.builder()
      .endpoint(server.url)
      .credentials(server.user, server.password)
      .httpClient(httpClient(server))
      .build()

  def bucket(server: Server, name: String, create: Boolean = true) =
    withClient(server): c =>
      if create
      then
        try c.makeBucket(MakeBucketArgs.builder().bucket(name).build())
        catch
          case e: ErrorResponseException if e.errorResponse().code() == "BucketAlreadyOwnedByYou" =>
      Bucket(server, name)


  def userBucket(server: Server, login: String, create: Boolean = true) =
    withClient(server): c =>
      if create
      then
        try c.makeBucket(MakeBucketArgs.builder().bucket(login).build())
        catch
          case e: ErrorResponseException if e.errorResponse().code() == "BucketAlreadyOwnedByYou" =>

        c.setBucketTags(SetBucketTagsArgs.builder().bucket(login).tags(Map(MiniClust.User.submitBucketTag).asJava).build())

      c.listBuckets().asScala.filter: bucket =>
        val tags = c.getBucketTags(GetBucketTagsArgs.builder().bucket(bucket.name()).build()).get
        tags.asScala.toMap.get(MiniClust.User.submitBucketTag._1).contains(MiniClust.User.submitBucketTag._2)
      .map(b => Bucket(server, b.name())).headOption.getOrElse:
        throw java.util.NoSuchElementException(s"Cannot get or create a bucket for user ${login}, tagged with tag ${MiniClust.User.submitBucketTag}")

  def listUserBuckets(server: Server): Seq[Bucket] =
    withClient(server): c =>
      c.listBuckets().asScala.filter: bucket =>
        val tags = c.getBucketTags(
          GetBucketTagsArgs.builder().bucket(bucket.name()).build()
        ).get
        tags.asScala.toMap.get(MiniClust.User.submitBucketTag._1).contains(MiniClust.User.submitBucketTag._2)
      .map(b => Bucket(server, b.name())).toSeq


  def delete(bucket: Bucket, path: String*): Unit =
    withClient(bucket.server): c =>
      val objects = path.map(p => io.minio.messages.DeleteObject(p))
      c.removeObjects(RemoveObjectsArgs.builder().bucket(bucket.name).objects(objects.asJava).build()).asScala.toSeq

  def deleteRecursive(bucket: Bucket, path: String): Unit =
    withClient(bucket.server): c =>
      val objects = listObjects(bucket, path, recursive = true).map(o => io.minio.messages.DeleteObject(o.objectName()))
      c.removeObjects(RemoveObjectsArgs.builder().bucket(bucket.name).objects(objects.asJava).build()).asScala.toSeq

  def download(bucket: Bucket, path: String, local: File) =
    withClient(bucket.server): c =>
      try
        val stream = c.getObject(GetObjectArgs.builder().bucket(bucket.name).`object`(path).build())
        try
          local.getParentFile.mkdirs()
          val fos = new FileOutputStream(local)
          try stream.transferTo(fos)
          finally fos.close()
        finally stream.close()
      catch
        case e: ErrorResponseException if e.errorResponse().code() == "NoSuchKey" => throw FileNotFoundException(s"File ${path} not found in bucket ${bucket.name}")


  def content(bucket: Bucket, path: String): String =
    withClient(bucket.server): c =>
      try
        val stream = c.getObject(GetObjectArgs.builder().bucket(bucket.name).`object`(path).build())
        try scala.io.Source.fromInputStream(stream).mkString
        finally stream.close()
      catch
        case e: ErrorResponseException if e.errorResponse().code() == "NoSuchKey" => throw FileNotFoundException(s"File ${path} not found in bucket ${bucket.name}")


  def upload(bucket: Bucket, local: File | String, path: String, tags: Seq[(String, String)] = Seq(), overwrite: Boolean = true, contentType: Option[String] = None): Boolean =
    val headers =
      if !overwrite then Map("If-None-Match" -> "*").asJava else Map().asJava

    try
      withClient(bucket.server): c =>
        local match
          case local: File =>
            val arg =
              UploadObjectArgs.builder().
                bucket(bucket.name).
                `object`(path).
                filename(local.getAbsolutePath).
                tags(tags.toMap.asJava)

            contentType.foreach(arg.contentType)
            c.uploadObject(arg.headers(headers).build())
          case local: String =>
              val is = new java.io.ByteArrayInputStream(local.getBytes(StandardCharsets.UTF_8))
              val arg =
                PutObjectArgs.builder().bucket(bucket.name).`object`(path).stream(is, local.size, -1).tags(tags.toMap.asJava)

              contentType.foreach(arg.contentType)
              c.putObject(arg.headers(headers).build())
      true
    catch
      case e: ErrorResponseException if !overwrite && e.errorResponse().code() == "PreconditionFailed" => false


  def listObjects(bucket: Bucket, prefix: String, recursive: Boolean = false) =
    withClient(bucket.server): c =>
      c.listObjects(
        ListObjectsArgs.builder().bucket(bucket.name).prefix(prefix).recursive(recursive).build()
      ).asScala.toSeq.map: i =>
        i.get()

  def exists(bucket: Bucket, prefix: String) =
    withClient(bucket.server): c =>
      try
        c.statObject(
          StatObjectArgs.builder().bucket(bucket.name).`object`(prefix).build()
        )
        true
      catch
        case e: ErrorResponseException if e.errorResponse().code() == "NoSuchKey" => false

  def date(server: Server) =
    val client = httpClient(server)
    val request = new Request.Builder()
      .url(server.url)
      .head()
      .build()

    val response = client.newCall(request).execute()

    try
      val dateHeader = response.header("Date")
      val formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
      ZonedDateTime.parse(dateHeader, formatter).toEpochSecond
    finally response.close()