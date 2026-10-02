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
 * What a target receives is not yet verified. A region's checksum is compared when the region
 * ends and the target's when its last byte has been written, so until `applyPatch` returns,
 * everything written is provisional, and if it throws, the output must be discarded - it may hold
 * part of the target and some bytes that are wrong. Write somewhere that can be thrown away, and
 * leave anything that cannot be undone, such as committing an install, overwriting the source or
 * telling a peer the transfer is complete, until `applyPatch` has returned.
 * [Kiff.applyPatch][com.linroid.kiff.Kiff.applyPatch] does this for a file, writing to a temporary
 * file it moves into place only once the restore verified.
 */
interface RestoreTarget {
  /**
   * Appends `bytes[from, to)` to the end of the restored output.
   *
   * [bytes] is lent for the length of the call: Kiff reuses the array for later writes, so a
   * target that needs the bytes afterwards copies them. Kiff has already checksummed them, so a
   * target may change or clear the array in place.
   */
  fun write(bytes: ByteArray, from: Int, to: Int)
}

/**
 * Collects a restore into a [ByteArray] of [size] bytes, for callers that wanted the bytes anyway.
 *
 * The array is allocated when the first byte arrives rather than up front, so a restore refused
 * before writing anything costs nothing, however large a size it declared.
 */
class ByteArrayRestoreTarget(private val size: Int) : RestoreTarget {
  private var bytes: ByteArray? = null
  private var at = 0

  init {
    require(size >= 0) { "Size must not be negative: $size" }
  }

  override fun write(bytes: ByteArray, from: Int, to: Int) {
    if (to == from) return
    val into = this.bytes ?: ByteArray(size).also { this.bytes = it }
    bytes.copyInto(into, at, from, to)
    at += to - from
  }

  /** The bytes written so far: the array itself once it is full, otherwise a copy of them. */
  fun toByteArray(): ByteArray {
    val collected = bytes ?: return ByteArray(0)
    return if (at == collected.size) collected else collected.copyOf(at)
  }
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
    // Checksummed before they are handed on, so the checksums describe what the restore produced
    // rather than whatever a target left in the array.
    whole.update(bytes, from, to)
    region?.update(bytes, from, to)
    out.write(bytes, from, to)
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
