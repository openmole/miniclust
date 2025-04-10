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

case class Account(bucket: String)

case class InputFile(remote: String, local: String, cacheKey: Option[String] = None)
case class OutputFile(local: String, remote: String)

object Message:
  extension (m: Message)
    def finished =
      m match
        case _: Failed | _: Completed | _: Canceled => true
        case _: Running | _: Submitted => false

  object Version:
    given Message.Version = "1"

    extension (v: Version)
      def asString: String = v

  opaque type Version = String

  object Failed:
    enum Reason:
      case Abandoned, Invalid, PreparationFailed, ExecutionFailed, CompletionFailed

  case class Submitted(
    account: Account,
    command: String,
    inputFile: Seq[InputFile] = Seq(),
    outputFile: Seq[OutputFile] = Seq(),
    stdOut: Option[String] = None,
    stdErr: Option[String] = None,
    noise: String = "") extends Message

  case class Failed(id: String, message: String, reason: Failed.Reason) extends Message
  case class Completed(id: String) extends Message
  case class Running(id: String) extends Message
  case class Canceled(id: String, canceled: Boolean = false) extends Message


sealed trait Message

