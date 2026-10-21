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
