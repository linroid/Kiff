package com.linroid.kiff.format

import com.linroid.kiff.KiffException
import com.linroid.kiff.PatcherId

/**
 * Patch container shared by every patcher:
 *
 * ```
 * "KIFF"  4 bytes
 * version 1 byte
 * patcher 1 byte
 * flags   1 byte
 * varint  source size    u32 source CRC-32
 * varint  target size    u32 target CRC-32
 * payload delta stream
 * ```
 *
 * Sizes and the source offsets inside the delta are 64-bit varints, so a patch can describe
 * inputs beyond 2 GB.
 *
 * The checksums are what let [Patcher.applyPatch] refuse the wrong source file and prove the
 * restored bytes are exactly the ones the patch was built from.
 */
internal object PatchFormat {

  const val VERSION = 1

  /** Versions this build can read. */
  private val SUPPORTED = setOf(VERSION)

  private val magic = byteArrayOf(0x4B, 0x49, 0x46, 0x46)

  /**
   * Every region that produces target bytes carries a checksum of them.
   *
   * Two whole-file checksums can say a patch did not restore; they cannot say where. On a package
   * of seventy megabytes and several hundred regions that is the difference between a bug you can
   * find and one you can only stare at.
   */
  const val FLAG_REGION_CHECKSUMS = 1

  fun writeHeader(
    out: ByteWriter,
    patcher: PatcherId,
    sourceSize: Long,
    sourceCrc32: UInt,
    targetSize: Long,
    targetCrc32: UInt,
    flags: Int = 0
  ) {
    out.writeBytes(magic)
    out.writeByte(VERSION)
    out.writeByte(patcher.code)
    out.writeByte(flags)
    out.writeVarLong(sourceSize)
    out.writeUInt32(sourceCrc32)
    out.writeVarLong(targetSize)
    out.writeUInt32(targetCrc32)
  }

  fun readHeader(reader: ByteReader): Header {
    for (expected in magic) {
      if (reader.readByte() != expected.toInt()) {
        throw KiffException.InvalidPatch("Not a Kiff patch: bad magic")
      }
    }
    val version = reader.readByte()
    if (version !in SUPPORTED) {
      throw KiffException.InvalidPatch(
        "Unsupported patch version $version (this build reads ${SUPPORTED.joinToString(", ")})"
      )
    }
    val patcher = PatcherId.fromCode(reader.readByte())
    val flags = reader.readByte()
    if (flags and UNKNOWN_FLAGS != 0) {
      // A flag this build does not know changes how the payload is laid out, so reading on would
      // produce bytes rather than an error, which is the worse failure.
      throw KiffException.InvalidPatch("Patch uses features this build does not know (flags $flags)")
    }
    return Header(
      patcher = patcher,
      version = version,
      flags = flags,
      sourceSize = reader.readVarLong(),
      sourceCrc32 = reader.readUInt32(),
      targetSize = reader.readVarLong(),
      targetCrc32 = reader.readUInt32()
    )
  }

  private const val UNKNOWN_FLAGS = FLAG_REGION_CHECKSUMS.inv()

  data class Header(
    val patcher: PatcherId,
    val version: Int,
    val flags: Int,
    val sourceSize: Long,
    val sourceCrc32: UInt,
    val targetSize: Long,
    val targetCrc32: UInt
  )
}
