package miniclust.s3message

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


import miniclust.message.Minio
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{ListObjectsV2Request, ListObjectsV2Response}
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.services.s3.S3Configuration

import java.net.URI

@main def main: Unit =
  val s = Minio.Server("https://babar.openmole.org", "test2", "test2test2")
  val m = Minio(s)

  println(Minio.date(m))