package miniclust.compute

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


import io.circe.yaml
import io.circe.derivation
import io.circe.generic.auto.*
import better.files.*

object Configuration:

  given derivation.Configuration = derivation.Configuration.default.withDefaults.withKebabCaseMemberNames.withKebabCaseConstructorNames

  def read(file: File): Configuration =
    yaml.parser.parse(file.contentAsString).toTry.get.as[Configuration].toTry.get

  case class Minio(
    url: String,
    key: String,
    secret: String,
    insecure: Boolean = false) derives derivation.ConfiguredCodec

  case class Compute(
    workDirectory: String,
    cache: Int,
    user: Option[String],
    sudo: Option[String],
    cores: Option[Int]) derives derivation.ConfiguredCodec

  case class Worker(
    storage: Option[String] = None)

case class Configuration(
  minio: Configuration.Minio,
  compute: Configuration.Compute,
  worker: Configuration.Worker = Configuration.Worker()) derives derivation.ConfiguredCodec
