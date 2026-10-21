package com.linroid.kiff.delta

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffOptions
import com.linroid.kiff.asBytes
import com.linroid.kiff.asZip
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
 * with no change to the format and no cooperation from the reader.
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
    val patcher = asBytes(KiffOptions(algorithm = LiteralOnlyAlgorithm))
    val patch = patcher.createPatch(source, target)
    assertContentEquals(target, Kiff.applyPatch(source, patch))
  }

  @Test
  fun theAlgorithmIsNotRecordedInThePatch() {
    // Nothing about the search reaches the container, so the ordinary reader reads it back.
    val patch = asBytes(KiffOptions(algorithm = LiteralOnlyAlgorithm)).createPatch(source, target)
    assertContentEquals(target, Kiff.applyPatch(source, patch))
    assertEquals(com.linroid.kiff.PatcherId.BINARY, Kiff.info(patch).patcher)
  }

  @Test
  fun runInstructionsFromOutsideTheLibraryRestore() {
    val patcher = asBytes(KiffOptions(algorithm = RunOnlyAlgorithm))
    val patch = patcher.createPatch(source, target)
    assertContentEquals(target, Kiff.applyPatch(source, patch))
  }

  @Test
  fun anArchiveTakesAnAlgorithmToo() {
    val patcher = asZip(KiffOptions(algorithm = LiteralOnlyAlgorithm))
    val patch = patcher.createPatch(source, target)
    assertContentEquals(target, Kiff.applyPatch(source, patch))
  }

  @Test
  fun theBundledAlgorithmStillBeatsANaiveOne() {
    val smart = asBytes().createPatch(source, target).size
    val naive = asBytes(KiffOptions(algorithm = LiteralOnlyAlgorithm))
      .createPatch(source, target).size
    assertTrue(smart < naive, "rolling hash produced $smart bytes, literal-only $naive")
  }

  @Test
  fun defaultAlgorithmIsTheRollingHash() {
    assertEquals(RollingHashAlgorithm, KiffOptions().algorithm)
  }
}
