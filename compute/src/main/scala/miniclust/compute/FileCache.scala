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
import com.github.benmanes.caffeine.cache.*

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.locks.ReentrantLock


object FileCache:

  opaque type UsedKey = String

  class UsageTracker:
    private val usageMap = scala.collection.mutable.Map[String, Int]()

    def acquire(key: String): Unit = usageMap.synchronized:
      usageMap.updateWith(key):
        case None => Some(1)
        case Some(v) => Some(v + 1)

    def release(key: String): Unit = usageMap.synchronized:
      usageMap.updateWith(key):
        case None => throw IllegalArgumentException(s"Key $key is not in use")
        case Some(v) =>
          if v > 1
          then Some(v - 1)
          else None

    def isInUse(key: String): Boolean = usageMap.synchronized:
      usageMap.contains(key)

  def isRemovalFile(name: String) = name.startsWith(".wh.")
  def removalFile(name: String) = s".wh.$name"

  def setPermissions(file: File): Unit =
    import java.nio.file.Files
    import java.nio.file.attribute.PosixFilePermission
    import scala.jdk.CollectionConverters.*

    val currentPerms = Files.getPosixFilePermissions(file.path).asScala
    val ownerRead = currentPerms.contains(PosixFilePermission.OWNER_READ)
    val ownerExec = currentPerms.contains(PosixFilePermission.OWNER_EXECUTE)

    val newPerms = scala.collection.mutable.Set[PosixFilePermission]()
    newPerms ++= currentPerms
    if ownerRead then newPerms ++= Seq(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)
    if ownerExec then newPerms ++= Seq(PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)

    Files.setPosixFilePermissions(file.path, newPerms.asJava)


  def apply(folder: File, maxSize: Long) =
    val fileFolder = folder.createDirectories()
    setPermissions(folder)
    
    val fileWeigher = new Weigher[String, File]:
      override def weigh(key: String, file: File): Int =
        val size = if file.exists() then Math.ceil(file.size().toDouble / 1024).toLong else 0L
        if size > Int.MaxValue then Int.MaxValue else size.toInt

    val removalListener = new RemovalListener[String, File]:
      override def onRemoval(key: String, file: File, cause: RemovalCause): Unit =
        if file != null then (folder / removalFile(key)).touch()

    val cache: Cache[String, File] =
      Caffeine.newBuilder().weigher(fileWeigher).maximumWeight(maxSize * 1024).removalListener(removalListener).build()

    new FileCache(cache, fileFolder, UsageTracker())

  def use(fileCache: FileCache, hash: String, extract: Boolean = false)(create: File => Unit): (File, UsedKey) =
    val name =
      if !extract
      then s"file-$hash"
      else s"tgz-$hash"

    fileCache.usageTracker.acquire(name)
    def createFunction(name: String): File =
      val cacheFile = fileCache.fileFolder / name
      (fileCache.fileFolder / removalFile(name)).delete(true)
      create(cacheFile)
      cacheFile

    try
      val f = fileCache.cache.get(name, createFunction)
      (f, name)
    catch
      case e: Exception =>
        fileCache.usageTracker.release(name)
        throw e

  def release(fileCache: FileCache, name: UsedKey) =
    fileCache.usageTracker.release(name)

  def clean(fileCache: FileCache) =
    for
      file <- fileCache.fileFolder.list
      name = file.name
      removal = fileCache.fileFolder / removalFile(name)
      if !isRemovalFile(name)
      if removal.exists
      if !fileCache.usageTracker.isInUse(name)
    do
      def deleteFunction(k: String, v: File): Null =
        v.delete(true)
        removal.delete(true)
        null

      fileCache.cache.asMap().compute(name, deleteFunction)

case class FileCache(
  cache: Cache[String, File],
  fileFolder: File,
  usageTracker: FileCache.UsageTracker)
