package com.linroid.kiff

import com.linroid.kiff.internal.ByteReader
import com.linroid.kiff.internal.ByteWriter

/**
 * Patch container shared by every patcher:
 *
 * ```
 * "KIFF"  4 bytes
 * version 1 byte
 * patcher 1 byte
 * flags   1 byte (reserved)
 * varint  source size    u32 source CRC-32
 * varint  target size    u32 target CRC-32
 * payload delta stream
 * ```
 *
 * Sizes and the source offsets inside the delta are 64-bit varints as of v2, so a patch can
 * describe inputs beyond 2 GB. The encoding of any value in `Int` range is unchanged, which is why
 * a v1 patch is still read correctly; only the declared ceiling moved.
 *
 * The checksums are what let [Patcher.applyPatch] refuse the wrong source file and prove the
 * restored bytes are exactly the ones the patch was built from.
 */
internal object PatchFormat {

  const val VERSION = 2

  /** Versions this build can read. v1 differs only in that it never wrote a value above 2 GB. */
  private val SUPPORTED = setOf(1, 2)

  private val magic = byteArrayOf(0x4B, 0x49, 0x46, 0x46)

  fun writeHeader(
    out: ByteWriter,
    patcher: PatcherId,
    sourceSize: Long,
    sourceCrc32: UInt,
    targetSize: Long,
    targetCrc32: UInt
  ) {
    out.writeBytes(magic)
    out.writeByte(VERSION)
    out.writeByte(patcher.code)
    out.writeByte(0)
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
    reader.readByte() // flags, reserved
    return Header(
      patcher = patcher,
      version = version,
      sourceSize = reader.readVarLong(),
      sourceCrc32 = reader.readUInt32(),
      targetSize = reader.readVarLong(),
      targetCrc32 = reader.readUInt32()
    )
  }

  data class Header(
    val patcher: PatcherId,
    val version: Int,
    val sourceSize: Long,
    val sourceCrc32: UInt,
    val targetSize: Long,
    val targetCrc32: UInt
  )
}
