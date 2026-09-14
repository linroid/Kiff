package com.linroid.kiff

import com.linroid.kiff.internal.ByteReader
import com.linroid.kiff.internal.ByteWriter
import com.linroid.kiff.internal.Crc32

/**
 * Patch container shared by every algorithm:
 *
 * ```
 * "KIFF"  4 bytes
 * version 1 byte
 * algo    1 byte
 * flags   1 byte (reserved)
 * varint  source size    u32 source CRC-32
 * varint  target size    u32 target CRC-32
 * payload delta stream
 * ```
 *
 * The checksums are what let [PatchAlgorithm.applyPatch] refuse the wrong source file and prove the
 * restored bytes are exactly the ones the patch was built from.
 */
internal object PatchFormat {

  const val VERSION = 1

  private val magic = byteArrayOf(0x4B, 0x49, 0x46, 0x46)

  fun writeHeader(out: ByteWriter, algorithm: AlgorithmId, source: ByteArray, target: ByteArray) {
    out.writeBytes(magic)
    out.writeByte(VERSION)
    out.writeByte(algorithm.code)
    out.writeByte(0)
    out.writeVarInt(source.size)
    out.writeUInt32(Crc32.compute(source))
    out.writeVarInt(target.size)
    out.writeUInt32(Crc32.compute(target))
  }

  fun readHeader(reader: ByteReader): Header {
    for (expected in magic) {
      if (reader.readByte() != expected.toInt()) {
        throw KiffException.InvalidPatch("Not a Kiff patch: bad magic")
      }
    }
    val version = reader.readByte()
    if (version != VERSION) {
      throw KiffException.InvalidPatch("Unsupported patch version $version (expected $VERSION)")
    }
    val algorithm = AlgorithmId.fromCode(reader.readByte())
    reader.readByte() // flags, reserved
    return Header(
      algorithm = algorithm,
      version = version,
      sourceSize = reader.readVarInt(),
      sourceCrc32 = reader.readUInt32(),
      targetSize = reader.readVarInt(),
      targetCrc32 = reader.readUInt32()
    )
  }

  data class Header(
    val algorithm: AlgorithmId,
    val version: Int,
    val sourceSize: Int,
    val sourceCrc32: UInt,
    val targetSize: Int,
    val targetCrc32: UInt
  )
}
