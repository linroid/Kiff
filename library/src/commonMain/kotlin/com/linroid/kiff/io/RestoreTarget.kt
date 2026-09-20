package com.linroid.kiff.io

import com.linroid.kiff.format.Crc32

/**
 * Where restored bytes are written, a block at a time.
 *
 * A patch describes its target in order, front to back, so restoring one never needs the whole
 * result in memory - only somewhere to put it. That matters most exactly where patches are applied:
 * a device installing an app update has far less room than the build server that made the patch.
 *
 * Implement this to send a restore straight to a file, a socket, or an installer.
 */
interface RestoreTarget {
  /** Appends `bytes[from, to)` to the end of the restored output. */
  fun write(bytes: ByteArray, from: Int, to: Int)
}

/** Collects a restore into a [ByteArray], for callers that wanted the bytes anyway. */
class ByteArrayRestoreTarget(size: Int) : RestoreTarget {
  private val bytes = ByteArray(size)
  private var at = 0

  override fun write(bytes: ByteArray, from: Int, to: Int) {
    bytes.copyInto(this.bytes, at, from, to)
    at += to - from
  }

  fun toByteArray(): ByteArray = if (at == bytes.size) bytes else bytes.copyOf(at)
}

/**
 * A [RestoreTarget] that checksums what passes through it: once over everything, and once per
 * region while one is open.
 *
 * The per-region checksum has to be taken here rather than afterwards, because afterwards the
 * bytes are gone - which is the whole point of streaming.
 */
internal class CheckedRestoreTarget(private val out: RestoreTarget) : RestoreTarget {

  private val whole = Crc32.Running()
  private var region: Crc32.Running? = null
  var position: Long = 0
    private set

  override fun write(bytes: ByteArray, from: Int, to: Int) {
    if (to <= from) return
    out.write(bytes, from, to)
    whole.update(bytes, from, to)
    region?.update(bytes, from, to)
    position += to - from
  }

  fun startRegion() {
    region = Crc32.Running()
  }

  fun finishRegion(): UInt {
    val done = checkNotNull(region) { "no region was started" }
    region = null
    return done.value()
  }

  fun wholeChecksum(): UInt = whole.value()
}

/** Writes into a slice of an array, for a region that has to be built before it can be emitted. */
internal class ScratchRestoreTarget(private val bytes: ByteArray) : RestoreTarget {
  private var at = 0

  override fun write(bytes: ByteArray, from: Int, to: Int) {
    bytes.copyInto(this.bytes, at, from, to)
    at += to - from
  }

  val written: Int get() = at
}
