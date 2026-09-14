package com.linroid.kiff.delta

import com.linroid.kiff.BinaryDiff
import com.linroid.kiff.Kiff
import com.linroid.kiff.ZipDiff
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.asSource
import com.linroid.kiff.io.readFully
import com.linroid.kiff.structuredBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A third-party algorithm: it never searches, it just hands every byte over as a literal. Useless
 * in practice, but it proves the seam - a patch it produces is read back by the ordinary reader,
 * with no change to the format and no cooperation from the patcher.
 */
private object LiteralOnlyAlgorithm : DeltaAlgorithm {

  override val name: String = "literal-only"

  override fun scanner(source: SeekableSource, from: Long, to: Long): DeltaScanner =
    object : DeltaScanner {
      override fun scan(
        target: SeekableSource,
        from: Long,
        to: Long,
        sink: DeltaSink,
        initialAlignment: Long
      ) {
        if (to <= from) return
        val bytes = ByteArray((to - from).toInt())
        target.readFully(from, bytes)
        sink.add(bytes)
      }
    }
}

/** Emits one RUN per byte, exercising a second instruction from outside the library. */
private object RunOnlyAlgorithm : DeltaAlgorithm {

  override val name: String = "run-only"

  override fun scanner(source: SeekableSource, from: Long, to: Long): DeltaScanner =
    object : DeltaScanner {
      override fun scan(
        target: SeekableSource,
        from: Long,
        to: Long,
        sink: DeltaSink,
        initialAlignment: Long
      ) {
        val bytes = ByteArray((to - from).toInt())
        if (bytes.isEmpty()) return
        target.readFully(from, bytes)
        bytes.forEach { sink.run(it, 1) }
      }
    }
}

class CustomAlgorithmTest {

  private val source = structuredBytes(20_000, seed = 91)
  private val target = structuredBytes(20_000, seed = 91).also { it[5_000] = 7 }

  @Test
  fun aCustomAlgorithmProducesPatchesTheOrdinaryReaderRestores() {
    val patcher = BinaryDiff(LiteralOnlyAlgorithm)
    val patch = patcher.createPatch(source, target)
    assertContentEquals(target, patcher.applyPatch(source, patch))
  }

  @Test
  fun theAlgorithmIsNotRecordedInThePatch() {
    // Nothing about the search reaches the container, so the stock patcher reads it back.
    val patch = BinaryDiff(LiteralOnlyAlgorithm).createPatch(source, target)
    assertContentEquals(target, Kiff.binary.applyPatch(source, patch))
    assertEquals(com.linroid.kiff.PatcherId.BINARY, Kiff.info(patch).patcher)
  }

  @Test
  fun runInstructionsFromOutsideTheLibraryRestore() {
    val patcher = BinaryDiff(RunOnlyAlgorithm)
    val patch = patcher.createPatch(source, target)
    assertContentEquals(target, Kiff.binary.applyPatch(source, patch))
  }

  @Test
  fun theArchivePatchersTakeAnAlgorithmToo() {
    val patcher = ZipDiff(LiteralOnlyAlgorithm)
    val patch = patcher.createPatch(source, target)
    assertContentEquals(target, patcher.applyPatch(source, patch))
  }

  @Test
  fun theBundledAlgorithmStillBeatsANaiveOne() {
    val smart = Kiff.binary.createPatch(source, target).size
    val naive = BinaryDiff(LiteralOnlyAlgorithm).createPatch(source, target).size
    assertTrue(smart < naive, "rolling hash produced $smart bytes, literal-only $naive")
  }

  @Test
  fun defaultAlgorithmIsTheRollingHash() {
    assertEquals(RollingHashAlgorithm, BinaryDiff().algorithm)
    assertEquals("rolling-hash", Kiff.binary.let { (it as BinaryDiff).algorithm.name })
  }
}
