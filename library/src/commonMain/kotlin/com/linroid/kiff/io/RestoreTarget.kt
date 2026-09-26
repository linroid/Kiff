package com.linroid.kiff.io

import com.linroid.kiff.KiffException
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
 * A [RestoreTarget] that counts what it is handed, and refuses anything past [limit].
 *
 * Every decoder checks its own lengths, but a malformed patch only has to find one check that is
 * wrong to write more than it declared - and a streamed restore cannot take bytes back. This is the
 * bound none of them can get past: a restore never produces more than [limit], and [position] is
 * what each region's output is measured against once it is done.
 */
internal abstract class CountingRestoreTarget(private val limit: Long) : RestoreTarget {

  var position: Long = 0
    private set

  final override fun write(bytes: ByteArray, from: Int, to: Int) {
    if (to == from) return
    if (to < from || to - from > limit - position) {
      throw KiffException.InvalidPatch("Patch writes past the end of its target")
    }
    accept(bytes, from, to)
    position += to - from
  }

  /** Takes `bytes[from, to)`, already known to fit. */
  protected abstract fun accept(bytes: ByteArray, from: Int, to: Int)
}

/**
 * A [RestoreTarget] that checksums what passes through it: once over everything, and once per
 * region while one is open.
 *
 * The per-region checksum has to be taken here rather than afterwards, because afterwards the
 * bytes are gone - which is the whole point of streaming.
 */
internal class CheckedRestoreTarget(
  private val out: RestoreTarget,
  targetSize: Long
) : CountingRestoreTarget(targetSize) {

  private val whole = Crc32.Running()
  private var region: Crc32.Running? = null

  override fun accept(bytes: ByteArray, from: Int, to: Int) {
    out.write(bytes, from, to)
    whole.update(bytes, from, to)
    region?.update(bytes, from, to)
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

/** Writes into an array, for a region that has to be built before it can be emitted. */
internal class ScratchRestoreTarget(
  private val bytes: ByteArray
) : CountingRestoreTarget(bytes.size.toLong()) {

  override fun accept(bytes: ByteArray, from: Int, to: Int) {
    bytes.copyInto(this.bytes, position.toInt(), from, to)
  }
}
