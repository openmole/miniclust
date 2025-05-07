package miniclust.documentation

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

import io.circe.Printer
import io.circe.syntax.*
import sttp.apispec.circe.*
import sttp.apispec.{Schema => ASchema}
import sttp.tapir.*
import sttp.tapir.docs.apispec.schema.*
import sttp.tapir.generic.auto.*
import sttp.tapir.Schema.annotations.title

import miniclust.message.Message

object Schema:
  def run =
    object Childhood {
      @title("my child") case class Child(age: Int, height: Option[Int])
    }
    case class Parent(innerChildField: Child, childDetails: Childhood.Child)
    case class Child(childName: String)
    val tSchema = implicitly[Schema[Message]]

    val jsonSchema: ASchema = TapirSchemaToJsonSchema(
      tSchema,
      markOptionsAsNullable = true)

    // JSON serialization
    val schemaAsJson = jsonSchema.asJson
    val schemaStr: String = Printer.spaces2.print(schemaAsJson.deepDropNullValues)
    schemaStr
