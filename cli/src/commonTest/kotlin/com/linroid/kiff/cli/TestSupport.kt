package com.linroid.kiff.cli

import kotlin.random.Random
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.Path
import okio.Path.Companion.toPath

/** A directory of its own under the system's temporary one, for a test to fill and delete. */
internal class TestDirectory {
  val path: Path = temporaryDirectory / "kiff-cli-test-${Random.nextLong().toULong().toString(16)}"

  init {
    systemFileSystem.createDirectories(path)
  }

  fun resolve(name: String): Path = path / name

  /** Writes [bytes] to [name] in this directory, and answers its path as a command takes it. */
  fun file(name: String, bytes: ByteArray): String {
    val file = path / name
    file.parent?.let { systemFileSystem.createDirectories(it) }
    systemFileSystem.write(file) { write(bytes) }
    return file.toString()
  }

  fun file(name: String, text: String): String = file(name, text.encodeToByteArray())

  fun delete() {
    systemFileSystem.deleteRecursively(path, mustExist = false)
  }
}

internal fun readBytes(path: String): ByteArray = readBytes(path.toPath())

internal fun readBytes(path: Path): ByteArray = systemFileSystem.read(path) { readByteArray() }

internal fun exists(path: Path): Boolean = systemFileSystem.exists(path)

internal fun sha256(bytes: ByteArray): String = Buffer().write(bytes).sha256().hex()

/** What a run of kiff left: its status, the bytes it wrote raw, and what it echoed to each. */
internal class Run(val status: Int, val bytes: ByteArray, val out: String, val err: String)

/** Runs kiff in this process, with what it writes kept for the test, and [versions] for --use. */
internal fun capture(vararg args: String, versions: (log: (String) -> Unit) -> KiffVersions): Run {
  val raw = Buffer()
  val out = StringBuilder()
  val err = StringBuilder()
  val status = runKiff(
    arrayOf(*args),
    raw,
    versions { err.append(it).append('\n') },
    echo = { text, toErr -> (if (toErr) err else out).append(text) }
  )
  return Run(status, raw.readByteArray(), out.toString(), err.toString())
}

/** Runs kiff with a --use that would fail the test if it went to the network. */
internal fun capture(vararg args: String): Run = capture(*args) { log ->
  KiffVersions("/nonexistent/kiff-home".toPath(), "https://example.invalid", log) { url, _ ->
    error("Fetched $url")
  }
}

/** A zip of [entries], stored rather than deflated, as any zip reader reads. */
internal fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
  val out = Buffer()
  val directory = Buffer()
  for ((name, data) in entries) {
    val offset = out.size.toInt()
    val crc = crc32(data)
    val encoded = name.encodeUtf8()
    out.writeIntLe(0x04034B50)
    out.header(crc, data.size, encoded.size)
    out.write(encoded)
    out.write(data)
    directory.writeIntLe(0x02014B50)
    directory.writeShortLe(20)
    directory.header(crc, data.size, encoded.size)
    // Comment length, disk, internal and external attributes, then the local header's offset.
    directory.writeShortLe(0)
    directory.writeShortLe(0)
    directory.writeShortLe(0)
    directory.writeIntLe(0)
    directory.writeIntLe(offset)
    directory.write(encoded)
  }
  val directoryStart = out.size.toInt()
  val directorySize = directory.size.toInt()
  out.writeAll(directory)
  out.writeIntLe(0x06054B50)
  out.writeShortLe(0)
  out.writeShortLe(0)
  out.writeShortLe(entries.size)
  out.writeShortLe(entries.size)
  out.writeIntLe(directorySize)
  out.writeIntLe(directoryStart)
  out.writeShortLe(0)
  return out.readByteArray()
}

/** One entry of a tar: a directory when [bytes] is null, else a file, or a link to [link]. */
internal class TarEntry(
  val name: String,
  val bytes: ByteArray?,
  val mode: Int = if (bytes == null) 0b111_101_101 else 0b110_100_100,
  val link: String? = null
)

/** A gzipped ustar archive of [entries], which any tar reads, its blocks stored, not deflated. */
internal fun tarGzBytes(entries: List<TarEntry>): ByteArray {
  val tar = Buffer()
  for (entry in entries) {
    val data = entry.bytes ?: ByteArray(0)
    val header = ByteArray(512)
    fun put(at: Int, text: String) = text.encodeToByteArray().copyInto(header, at)
    fun octal(at: Int, width: Int, value: Int) = put(at, value.toString(8).padStart(width - 1, '0'))
    put(0, entry.name)
    octal(100, 8, entry.mode)
    octal(108, 8, 0)
    octal(116, 8, 0)
    octal(124, 12, if (entry.link == null) data.size else 0)
    octal(136, 12, 0)
    put(148, "        ")
    header[156] = when {
      entry.link != null -> '2'
      entry.bytes == null -> '5'
      else -> '0'
    }.code.toByte()
    entry.link?.let { put(157, it) }
    put(257, "ustar\u000000")
    // Six digits, a NUL and a space, summed while the field was all spaces.
    octal(148, 7, header.sumOf { it.toInt() and 0xFF })
    header[154] = 0
    tar.write(header)
    if (entry.link == null) {
      tar.write(data)
      tar.write(ByteArray((512 - data.size % 512) % 512))
    }
  }
  tar.write(ByteArray(1024))
  val bytes = tar.readByteArray()

  val out = Buffer()
  out.write(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xFF.toByte()))
  var at = 0
  do {
    val size = minOf(0xFFFF, bytes.size - at)
    val last = at + size == bytes.size
    out.writeByte(if (last) 1 else 0)
    out.writeShortLe(size)
    out.writeShortLe(size.inv())
    out.write(bytes, at, size)
    at += size
  } while (!last)
  out.writeIntLe(crc32(bytes))
  out.writeIntLe(bytes.size)
  return out.readByteArray()
}

/** What local and central headers share: version, flags, method, time, sizes and name length. */
private fun Buffer.header(crc: Int, size: Int, nameSize: Int) {
  writeShortLe(20)
  writeShortLe(0)
  writeShortLe(0)
  // 1 January 1980, the earliest a zip can say.
  writeShortLe(0)
  writeShortLe(0x21)
  writeIntLe(crc)
  writeIntLe(size)
  writeIntLe(size)
  writeShortLe(nameSize)
  writeShortLe(0)
}

private val CRC_TABLE = IntArray(256) { index ->
  var c = index
  repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
  c
}

private fun crc32(data: ByteArray): Int {
  var crc = -1
  for (byte in data) crc = CRC_TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
  return crc.inv()
}
