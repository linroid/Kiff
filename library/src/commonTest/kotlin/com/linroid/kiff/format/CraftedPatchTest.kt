package com.linroid.kiff.format

import com.linroid.kiff.KiffException
import com.linroid.kiff.delta.DeltaOp
import com.linroid.kiff.io.ByteArrayRestoreTarget
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.CheckedRestoreTarget
import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.io.ScratchRestoreTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Patches written by hand to reach the lengths no corruption of a real patch does.
 *
 * [MalformedPatchTest] mutates patches Kiff built, whose lengths are a byte or two long, so it can
 * never produce a count of Int.MAX_VALUE or a ten-byte varint. Those are exactly the values that
 * wrap a bounds check written as a sum, and a check that wraps passes. Each case here aims one at
 * a different check, and each has to be refused as an [KiffException.InvalidPatch] - never another
 * exception, and never more bytes than the target declared.
 */
class CraftedPatchTest {

  /** Counts what a streamed restore hands over, keeping none of it. */
  private class Counting : RestoreTarget {
    var count = 0L
      private set

    override fun write(bytes: ByteArray, from: Int, to: Int) {
      count += to - from
    }
  }

  private val source = "a\nb\nc\nd\n".encodeToByteArray()

  private fun bytes(build: ByteWriter.() -> Unit): ByteArray =
    ByteWriter(64).apply(build).toByteArray()

  private fun payload(
    tree: ByteArray,
    content: ByteArray = ByteArray(0),
    unpacked: Int = content.size,
    encoding: Int = PatchPayload.CONTENT_STORED,
    treeLength: Int = tree.size
  ): ByteArray = bytes {
    writeVarInt(treeLength)
    writeVarInt(unpacked)
    writeByte(encoding)
    writeVarInt(content.size)
    writeBytes(tree)
    writeBytes(content)
  }

  /** Any 64 bits as a varint, including the ones [ByteWriter] refuses to write. */
  private fun ByteWriter.anyVarLong(value: Long) {
    var remaining = value
    while (remaining and 0x7FL.inv() != 0L) {
      writeByte(((remaining and 0x7F) or 0x80).toInt())
      remaining = remaining ushr 7
    }
    writeByte(remaining.toInt())
  }

  private fun ByteWriter.instruction(opcode: Int, length: Long) =
    writeVarLong((length shl DeltaOp.SHIFT) or opcode.toLong())

  private fun ByteWriter.delta(length: Long, instructions: ByteArray) {
    writeVarLong(length)
    writeByte(RegionEncoding.DELTA.code)
    writeVarInt(instructions.size)
    writeBytes(instructions)
  }

  private fun ByteWriter.text(
    length: Long,
    sourceFrom: Long,
    sourceLength: Long,
    edits: ByteArray
  ) {
    writeVarLong(length)
    writeByte(RegionEncoding.TEXT.code)
    writeVarLong(sourceFrom)
    writeVarLong(sourceLength)
    writeVarInt(edits.size)
    writeBytes(edits)
  }

  private fun ByteWriter.raw(length: Long) {
    writeVarLong(length)
    writeByte(RegionEncoding.RAW.code)
  }

  /** Four bytes already consumed, then a region of Int.MAX_VALUE: the shape that wraps a cursor. */
  private fun afterFourRawBytes(region: ByteWriter.() -> Unit): ByteArray = bytes {
    writeVarLong(4L + Int.MAX_VALUE)
    writeByte(RegionEncoding.COMPOSITE.code)
    writeVarInt(2)
    raw(4)
    region()
  }

  /**
   * Applies [payload] on both paths and asserts each refuses it, without either one handing over
   * more than [targetSize] bytes first.
   */
  private fun assertRefused(payload: ByteArray, targetSize: Long) {
    if (targetSize <= 1 shl 20) {
      assertFailsWith<KiffException.InvalidPatch>("collecting") {
        PatchPayload.apply(
          ByteArraySource(source), payload, 0, targetSize,
          checksums = false, out = ByteArrayRestoreTarget(targetSize.toInt())
        )
      }
    }
    val streamed = Counting()
    assertFailsWith<KiffException.InvalidPatch>("streaming") {
      PatchPayload.apply(ByteArraySource(source), payload, 0, targetSize, false, streamed)
    }
    assertTrue(
      streamed.count <= targetSize,
      "wrote ${streamed.count} bytes for a target of $targetSize before refusing"
    )
  }

  @Test
  fun aContentLengthThatDecodesNegativeIsRefused() {
    val payload = bytes {
      writeVarInt(0)
      anyVarLong(-1)
      writeByte(PatchPayload.CONTENT_LZ_HUFFMAN)
      writeVarInt(0)
    }
    assertRefused(payload, targetSize = 1)
  }

  @Test
  fun aTreeLengthNearIntMaxIsRefused() {
    assertRefused(payload(bytes { raw(1) }, ByteArray(1), treeLength = Int.MAX_VALUE), 1)
  }

  @Test
  fun aRunThatWouldWrapItsRegionIsRefused() {
    // One byte of run, then Int.MAX_VALUE more: the sum wraps negative, which is not past the end.
    val instructions = bytes {
      instruction(DeltaOp.RUN, 1)
      writeByte(0x41)
      instruction(DeltaOp.RUN, Int.MAX_VALUE.toLong())
      writeByte(0x42)
      instruction(DeltaOp.END, 0)
    }
    assertRefused(payload(bytes { delta(2, instructions) }), targetSize = 2)
  }

  @Test
  fun anAddThatWouldWrapTheContentCursorIsRefused() {
    val instructions = bytes {
      instruction(DeltaOp.ADD, Int.MAX_VALUE.toLong())
      instruction(DeltaOp.END, 0)
    }
    val tree = afterFourRawBytes { delta(Int.MAX_VALUE.toLong(), instructions) }
    assertRefused(payload(tree, "abcd".encodeToByteArray()), targetSize = 4L + Int.MAX_VALUE)
  }

  @Test
  fun aRawRegionThatWouldWrapTheContentCursorIsRefused() {
    val tree = afterFourRawBytes { raw(Int.MAX_VALUE.toLong()) }
    assertRefused(payload(tree, "abcd".encodeToByteArray()), targetSize = 4L + Int.MAX_VALUE)
  }

  @Test
  fun aCopyFromTheFarEndOfTheAddressSpaceIsRefused() {
    val instructions = bytes {
      instruction(DeltaOp.COPY, 8)
      anyVarLong((Long.MAX_VALUE - 3) shl 1) // zig-zag: an offset of Long.MAX_VALUE - 3
      instruction(DeltaOp.END, 0)
    }
    assertRefused(payload(bytes { delta(8, instructions) }), targetSize = 8)
  }

  @Test
  fun aTextRegionCountingLinesPastItsSourceIsRefused() {
    val edits = bytes {
      writeByte(1) // equal
      writeVarInt(1)
      writeByte(1)
      writeVarInt(Int.MAX_VALUE)
      writeByte(0) // end
    }
    assertRefused(payload(bytes { text(4, 0, source.size.toLong(), edits) }), targetSize = 4)
  }

  @Test
  fun aTextInsertThatWouldWrapTheContentCursorIsRefused() {
    val edits = bytes {
      writeByte(3) // insert
      writeVarInt(Int.MAX_VALUE)
      writeByte(0)
    }
    val tree = afterFourRawBytes { text(Int.MAX_VALUE.toLong(), 0, 0, edits) }
    assertRefused(payload(tree, "abcd".encodeToByteArray()), targetSize = 4L + Int.MAX_VALUE)
  }

  @Test
  fun aSourceRangeFromTheFarEndOfTheAddressSpaceIsRefused() {
    val edits = bytes { writeByte(0) }
    assertRefused(payload(bytes { text(0, Long.MAX_VALUE - 1, 8, edits) }), targetSize = 0)
  }

  @Test
  fun anLzssBackReferenceThatWouldWrapIsRefused() {
    // Four literal bytes, then a match whose stored distance is Int.MAX_VALUE: one more wraps it.
    val content = bytes {
      writeVarInt(4 shl 1)
      writeBytes("abcd".encodeToByteArray())
      writeVarInt(1)
      writeVarInt(Int.MAX_VALUE)
      writeVarInt(0)
    }
    val payload = payload(
      bytes { raw(8) }, content, unpacked = 8, encoding = PatchPayload.CONTENT_LZSS
    )
    assertRefused(payload, targetSize = 8)
  }

  @Test
  fun aFewPackedBytesCannotDeclareAGigabyte() {
    // The declared target is the only other bound, and it is the patch's own word: a patch can
    // declare a gigabyte of both, and four stored bytes used to be enough to have it allocated.
    val payload = payload(
      bytes { raw(1L shl 30) },
      content = ByteArray(4),
      unpacked = 1 shl 30,
      encoding = PatchPayload.CONTENT_LZ_HUFFMAN
    )
    assertRefused(payload, targetSize = 1L shl 30)
  }

  @Test
  fun storedContentIsExactlyTheSizeItDeclares() {
    // Declaring less than is stored restored the whole region and returned normally.
    val content = "abcd".encodeToByteArray()
    for (declared in listOf(2, 8)) {
      assertRefused(payload(bytes { raw(4) }, content, unpacked = declared), targetSize = 4)
    }
  }

  @Test
  fun aHeaderDeclaringANegativeSizeIsRefused() {
    val header = bytes {
      writeBytes("KIFF".encodeToByteArray())
      writeByte(PatchFormat.VERSION)
      writeByte(1)
      writeByte(0)
      writeVarLong(0)
      writeUInt32(0u)
      anyVarLong(-1)
      writeUInt32(0u)
    }
    assertFailsWith<KiffException.InvalidPatch> { PatchFormat.readHeader(ByteReader(header)) }
  }

  @Test
  fun noTargetTakesMoreThanItWasPromised() {
    // The bound behind every check above, which holds whatever a decoder got wrong.
    val scratch = ScratchRestoreTarget(ByteArray(4))
    scratch.write(ByteArray(3), 0, 3)
    assertFailsWith<KiffException.InvalidPatch> { scratch.write(ByteArray(2), 0, 2) }

    val streamed = Counting()
    val checked = CheckedRestoreTarget(streamed, targetSize = 4)
    checked.write(ByteArray(4), 0, 4)
    assertFailsWith<KiffException.InvalidPatch> { checked.write(ByteArray(1), 0, 1) }
    assertFailsWith<KiffException.InvalidPatch> { checked.write(ByteArray(4), 3, 1) }
    assertEquals(4L, streamed.count)
  }
}
