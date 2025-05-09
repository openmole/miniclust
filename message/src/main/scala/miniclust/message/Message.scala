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


object Message:
  import io.circe.*

  given derivation.Configuration = Tool.jsonConfiguration
  given Codec[Message] = derivation.ConfiguredCodec.derived[Message]

  extension (m: Message)
    def finished =
      m match
        case _: Failed | _: Completed | _: Canceled => true
        case _: Running | _: Submitted => false

    def canceled =
      m match
        case _: Canceled => true
        case _ => false


  object Version:
    given Message.Version = "1"

    extension (v: Version)
      def asString: String = v

  opaque type Version = String

  object Failed:
    enum Reason derives derivation.ConfiguredCodec:
      case Abandoned, Invalid, PreparationFailed, ExecutionFailed, CompletionFailed, TimeExhausted, UnexpectedError

  case class Submitted(
    account: Account,
    command: String,
    inputFile: Seq[InputFile] = Seq(),
    outputFile: Seq[OutputFile] = Seq(),
    stdOut: Option[String] = None,
    stdErr: Option[String] = None,
    resource: Seq[Resource] = Seq(),
    noise: String = "") extends Message

  case class Failed(id: String, message: String, reason: Failed.Reason) extends Message
  case class Completed(id: String) extends Message
  case class Running(id: String) extends Message
  case class Canceled(id: String, canceled: Boolean = false) extends Message

  type FinalState = Canceled | Failed | Completed

  object Account:
    given Conversion[String, Account] = s => Account(s)

  case class Account(bucket: String) derives derivation.ConfiguredCodec

  object InputFile:
    object Cache:
      given Conversion[String, Cache] = s => Cache(s)

    case class Cache(hash: String, extract: Boolean = false) derives derivation.ConfiguredCodec

  case class InputFile(remote: String, local: String, cacheKey: Option[InputFile.Cache] = None) derives derivation.ConfiguredCodec

  case class OutputFile(local: String, remote: String) derives derivation.ConfiguredCodec

  enum Resource derives derivation.ConfiguredCodec:
    case Core(core: Int)
    case MaxTime(second: Int)


sealed trait Message

export Message.{Account, InputFile, OutputFile, Resource}