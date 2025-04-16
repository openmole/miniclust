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

import miniclust.message.Account
import better.files.*

import java.util.concurrent.atomic.AtomicInteger

object FileCache:

  case class CachedFile(fileCache: FileCache, name: String)

  def cached[T](fileCache: FileCache, user: Account, name: String) =
    CachedFile(fileCache, s"${user.bucket}-${name}")

  def apply(folder: File, maxSize: Long) =
    folder.createDirectories()
    new FileCache(folder, maxSize)

  private def removeOldFiles(cache: FileCache): Unit =
    val dateLimit = System.currentTimeMillis() - 7 * 1000 * 60 * 60 * 24

    cache.folder.list.map(_.toJava).toSeq
      .filter(_.isFile)
      .sortBy(_.lastModified() < dateLimit).foreach: f =>
        f.delete()

  def enforceSizeLimit(cache: FileCache): Unit =
    val files =
      cache.folder.list.map(_.toJava).toSeq
      .filter(_.isFile)
      .sortBy(_.lastModified())

    def toMega(s: Long) = s.toDouble / (1024 * 1024)

    val totalSize = files.map(_.length()).sum

    if toMega(totalSize) > cache.maxSize
    then
      var currentSize = totalSize

      for
        file <- files
        if toMega(currentSize) > cache.maxSize
      do
        cache.locks.withLock(file.getName):
          if file.exists()
          then
            currentSize -= file.length()
            file.delete()


  def use[A](cache: CachedFile)(op: File => A): A =
    val file = cache.fileCache.folder / cache.name
    cache.fileCache.locks.withLock(cache.name):
      try op(file)
      finally
        if file.exists
        then file.toJava.setLastModified(System.currentTimeMillis())


  object LockRepository:
    def apply[T]() = new LockRepository[T]()

  class LockRepository[T]:
    import java.util.concurrent.locks.*

    val locks = new collection.mutable.HashMap[T, (ReentrantLock, AtomicInteger)]

    def nbLocked(k: T) = locks.synchronized(locks.get(k).map { (_, users) => users.get }.getOrElse(0))

    private def getLock(obj: T) = locks.synchronized:
      val (lock, users) = locks.getOrElseUpdate(obj, (new ReentrantLock, new AtomicInteger(0)))
      users.incrementAndGet
      lock

    private def cleanLock(obj: T) = locks.synchronized:
      locks.get(obj) match
        case Some((lock, users)) =>
          val value = users.decrementAndGet
          if (value <= 0) locks.remove(obj)
          lock
        case None => throw new IllegalArgumentException("Unlocking an object that has not been locked.")


    def withLock[A](obj: T)(op: => A) =
      val lock = getLock(obj)
      lock.lock()
      try op
      finally
        try cleanLock(obj)
        finally lock.unlock()

  opaque type Used = String

case class FileCache(folder: File, maxSize: Long):
  val locks = FileCache.LockRepository[String]()
