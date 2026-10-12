package com.linroid.kiff.io

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffException
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.PatchFormat
import com.linroid.kiff.structuredBytes
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamingRestoreTest {

  /** Collects what it is handed, and remembers how it was handed over. */
  private class Recording : RestoreTarget {
    val bytes = mutableListOf<Byte>()
    var writes = 0
      private set
    var largestWrite = 0
      private set

    override fun write(bytes: ByteArray, from: Int, to: Int) {
      writes++
      largestWrite = maxOf(largestWrite, to - from)
      for (i in from until to) this.bytes.add(bytes[i])
    }
  }

  /**
   * A source that never hands over more than [limit] bytes at once, whatever it is asked for.
   *
   * Nothing forbids this - [SeekableSource.read] is documented to return fewer bytes than
   * requested - and a restore that assumed otherwise would fail against a socket or a slow file.
   */
  private class Trickling(private val bytes: ByteArray, private val limit: Int) : SeekableSource {
    override val size: Long get() = bytes.size.toLong()
    var reads = 0
      private set

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
      if (position >= bytes.size) return -1
      reads++
      val count = minOf(limit, length, bytes.size - position.toInt())
      bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
      return count
    }
  }

  private val source = TestZipBuilder()
    .entry("a.bin", structuredBytes(40_000, seed = 1))
    .entry("notes.txt", (1..80).joinToString("\n") { "line $it" } + "\n")
    .build()

  private val target = TestZipBuilder()
    .entry("a.bin", structuredBytes(40_000, seed = 1).also { bytes ->
      for (at in bytes.indices step 32) bytes[at] = (bytes[at] + 5).toByte()
    })
    .entry("notes.txt", (1..80).joinToString("\n") { if (it == 40) "changed" else "line $it" } + "\n")
    .entry("added.bin", structuredBytes(3_000, seed = 2))
    .build()

  @Test
  fun streamingProducesExactlyWhatCollectingDoes() {
    val patch = Kiff.zip.createPatch(source, target)
    val recorded = Recording()
    Kiff.zip.applyPatch(ByteArraySource(source), patch, recorded)
    assertContentEquals(target, recorded.bytes.toByteArray())
    assertContentEquals(target, Kiff.zip.applyPatch(source, patch))
  }

  @Test
  fun theTargetArrivesInOrderAndInPieces() {
    val patch = Kiff.zip.createPatch(source, target)
    val recorded = Recording()
    Kiff.zip.applyPatch(ByteArraySource(source), patch, recorded)
    assertEquals(target.size, recorded.bytes.size)
    assertTrue(recorded.writes > 1, "a restore of this shape should arrive in pieces")
    assertTrue(
      recorded.largestWrite <= target.size,
      "no single write should exceed the target"
    )
  }

  @Test
  fun aSourceThatHandsOverOneByteAtATimeStillWorks() {
    val patch = Kiff.zip.createPatch(source, target)
    val trickling = Trickling(source, limit = 1)
    val recorded = Recording()
    Kiff.zip.applyPatch(trickling, patch, recorded)
    assertContentEquals(target, recorded.bytes.toByteArray())
    assertTrue(trickling.reads > source.size / 2, "should have read in small pieces")
  }

  @Test
  fun aSourceThatIsNotTheRightOneIsStillRefused() {
    val patch = Kiff.zip.createPatch(source, target)
    val wrong = source.copyOf().also { it[100] = (it[100] + 1).toByte() }
    assertFailsWith<KiffException.SourceMismatch> {
      Kiff.zip.applyPatch(ByteArraySource(wrong), patch, Recording())
    }
  }

  @Test
  fun aCorruptedPatchIsStillCaughtWhileStreaming() {
    // The bytes are already gone by the time a whole-file check could run, so the region checksums
    // are what stop a streamed restore from quietly emitting the wrong thing.
    val patch = Kiff.zip.createPatch(source, target)
    val corrupted = patch.copyOf().also { it[it.size - 60] = (it[it.size - 60] + 1).toByte() }
    val failure = assertFailsWith<KiffException> {
      Kiff.zip.applyPatch(ByteArraySource(source), corrupted, Recording())
    }
    assertTrue(
      failure is KiffException.VerificationFailed || failure is KiffException.InvalidPatch,
      "expected a refusal, got ${failure::class.simpleName}: ${failure.message}"
    )
  }

  /** Keeps a copy of what it is lent, then scribbles over the array it was handed. */
  private class Scrambling : RestoreTarget {
    val bytes = mutableListOf<Byte>()

    override fun write(bytes: ByteArray, from: Int, to: Int) {
      for (i in from until to) this.bytes.add(bytes[i])
      bytes.fill(0x5A, from, to)
    }
  }

  @Test
  fun aTargetMayChangeWhatItWasLent() {
    // The checksums were taken after the hand-off, so a target that reused or cleared the array
    // turned a good restore into a VerificationFailed; and a long run was filled once and handed
    // out again, so the second chunk was whatever the target left.
    val plain = structuredBytes(300_000, seed = 3)
    val updated = plain.copyOf().also { it.fill(7, 100_000, 300_000) }
    val patch = Kiff.binary.createPatch(plain, updated)
    val scrambled = Scrambling()
    Kiff.binary.applyPatch(plain.asSource(), patch, scrambled)
    assertContentEquals(updated, scrambled.bytes.toByteArray())
  }

  @Test
  fun aRefusedRestoreHasAlreadyStreamedWhatItWrote() {
    // The contract a streaming target has to live with: the final checksum is compared after the
    // last byte, so a refusal can come once everything has been handed over.
    val patch = Kiff.zip.createPatch(source, target)
    val header = ByteReader(patch)
    PatchFormat.readHeader(header)
    patch[header.offset - 1] = (patch[header.offset - 1] + 1).toByte()

    val recording = Recording()
    assertFailsWith<KiffException.VerificationFailed> {
      Kiff.zip.applyPatch(source.asSource(), patch, recording)
    }
    assertEquals(target.size, recording.bytes.size)
  }
}
