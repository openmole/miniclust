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

import io.circe.derivation
import org.apache.commons.codec.digest.*
import software.amazon.awssdk.http.{HttpExecuteRequest, SdkHttpMethod, SdkHttpRequest}
import software.amazon.awssdk.http.apache.ApacheHttpClient

import java.io.*
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

object Tool:

  def hashString(input: String): String =
    import java.nio.charset.StandardCharsets
    val hashBytes = Blake3.hash(input.getBytes(StandardCharsets.UTF_8))
    s"blake3:${hashBytes.map("%02x".format(_)).mkString}"

  def splitHash(hash: String) =
    val i = hash.indexOf(":")
    if i == -1
    then ("", hash)
    else (hash.take(i), hash.drop(i + 1))

  def hashFile(input: File): String =
    val hasher = Blake3.initHash()
    val buffer = Array.ofDim[Byte](64 * 1024)
    val is = FileInputStream(input)
    try
      var bytesRead = 0
      while
        bytesRead = is.read(buffer)
        bytesRead != -1
      do hasher.update(buffer, 0, bytesRead)
    finally is.close()

    val hash = hasher.doFinalize(32)
    s"blake3:${hash.map("%02x".format(_)).mkString}"

  def jsonConfiguration =
    derivation.Configuration.default.
      withDiscriminator("type").
      withDefaults.
      withoutStrictDecoding.
      with.
      withKebabCaseMemberNames.withKebabCaseConstructorNames

  def queryExternalIP: Option[String] =
    val client =
      ApacheHttpClient.builder().
        connectionTimeout(Duration.ofSeconds(20)).
        socketTimeout(Duration.ofSeconds(20)).build()

    try
      util.Try:
        val httpRequest =
          HttpExecuteRequest.builder().request:
            SdkHttpRequest.builder()
              .uri(java.net.URI.create("http://checkip.amazonaws.com"))
              .method(SdkHttpMethod.GET)
              .build()
          .build()

        val response = client.prepareRequest(httpRequest).call()
        val entity =
          response.responseBody().get().readAllBytes()
        String(entity).takeWhile(_ != '\n')
      .toOption
    finally
      client.close()

  def exceptionToString(e: Throwable): String =
    import java.io.{PrintWriter, StringWriter}
    val sw = StringWriter()
    e.printStackTrace(PrintWriter(sw))
    sw.toString
