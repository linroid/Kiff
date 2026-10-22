package com.linroid.kiff.format

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffException
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.structuredBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The region tree packed with the content's codec: written only where it makes a patch smaller,
 * and held by a reader to the same bounds as the content it sits beside.
 */
class PackedTreeTest {

  /** Bytes with something inserted every few dozen, which takes an instruction each to describe. */
  private fun shifted(source: ByteArray, step: Int): ByteArray {
    val out = ByteWriter(source.size + source.size / step * 2)
    var at = 0
    var i = 0
    while (at < source.size) {
      out.writeBytes(source, at, minOf(at + step, source.size))
      out.writeByte(0x41 + i % 26)
      at += step
      i++
    }
    return out.toByteArray()
  }

  @Test
  fun aLongInstructionStreamIsPackedWhereThatIsSmaller() {
    val source = structuredBytes(40_000, seed = 31)
    val target = shifted(source, step = 40)
    val patch = Kiff.createPatch(source, target)
    val info = Kiff.info(patch)
    assertTrue(info.treePacked, "a thousand instructions were left as they are")
    assertEquals(1, info.formatVersion)
    val stored = withStoredTree(patch)
    assertTrue(patch.size < stored.size, "${patch.size} packed against ${stored.size} stored")

    assertContentEquals(target, Kiff.applyPatch(source, patch))
    val streamed = Collected()
    Kiff.applyPatch(ByteArraySource(source), patch, streamed)
    assertContentEquals(target, streamed.bytes())
  }

  @Test
  fun aPatchThatGainsNothingFromPackingIsWrittenWithoutIt() {
    // Without the flag, the bytes are the ones Kiff 0.1 and 0.2 wrote and still read: a patch that
    // packing would not shrink costs those readers nothing.
    val source = structuredBytes(8_000, seed = 32)
    val target = source.copyOf().also { for (at in it.indices step 500) it[at] = 7 }
    val patch = Kiff.createPatch(source, target)
    assertFalse(Kiff.info(patch).treePacked)
    assertEquals(PatchFormat.FLAG_REGION_CHECKSUMS, patch[FLAGS_AT].toInt())
    assertTrue(withPackedTree(patch).size >= patch.size, "packing would have paid after all")
    assertContentEquals(target, Kiff.applyPatch(source, patch))
  }

  @Test
  fun aPackedTreeNoSmallerThanItIsStoredIsStillRead() {
    // A writer is entitled to pack a tree that gains nothing, and a reader to read it.
    val source = structuredBytes(2_000, seed = 33)
    val target = structuredBytes(2_000, seed = 33).also { it[100] = 1 }
    val patch = withPackedTree(Kiff.createPatch(source, target))
    assertTrue(withStoredTree(patch).size < patch.size)
    assertContentEquals(target, Kiff.applyPatch(source, patch))
  }

  @Test
  fun theInfoSaysWhetherTheTreeIsPacked() {
    val source = structuredBytes(2_000, seed = 34)
    val target = structuredBytes(2_100, seed = 35)
    val patch = Kiff.createPatch(source, target)
    assertFalse(Kiff.info(patch).treePacked)
    assertTrue(Kiff.info(withPackedTree(patch)).treePacked)
  }

  // ---- what a reader refuses -------------------------------------------------------------

  private val source = "a\nb\nc\nd\n".encodeToByteArray()

  private fun bytes(build: ByteWriter.() -> Unit): ByteArray =
    ByteWriter(64).apply(build).toByteArray()

  /** A tree of one stored region of [length] bytes, which takes all its content. */
  private fun rawTree(length: Long) = bytes {
    writeVarLong(length)
    writeByte(RegionEncoding.RAW.code)
  }

  /** A payload whose tree is the packed [storedTree], declared to unpack to [treeLength]. */
  private fun payload(
    storedTree: ByteArray,
    treeLength: Int,
    content: ByteArray = ByteArray(0),
    storedLength: Int = storedTree.size
  ): ByteArray = bytes {
    writeVarInt(treeLength)
    writeVarInt(storedLength)
    writeVarInt(content.size)
    writeByte(PatchPayload.CONTENT_STORED)
    writeVarInt(content.size)
    writeBytes(storedTree)
    writeBytes(content)
  }

  private fun apply(payload: ByteArray, targetSize: Long, out: RestoreTarget = Collected()) =
    PatchPayload.apply(
      ByteArraySource(source), payload, 0, targetSize,
      checksums = false, out = out, treePacked = true
    )

  private fun assertRefused(payload: ByteArray, targetSize: Long, expected: String) {
    val failure = assertFailsWith<KiffException.InvalidPatch> { apply(payload, targetSize) }
    val message = failure.message.orEmpty()
    assertTrue(expected.lowercase() in message.lowercase(), message)
  }

  @Test
  fun aWellFormedPackedTreeRestores() {
    val tree = rawTree(3)
    val out = Collected()
    apply(payload(LzHuffman.compress(tree), tree.size, "xyz".encodeToByteArray()), 3, out)
    assertContentEquals("xyz".encodeToByteArray(), out.bytes())
  }

  @Test
  fun aPackedTreeLongerThanTheTargetIsRefused() {
    // Content is bounded by the target because each byte of it becomes at most one target byte. A
    // tree is not, but one that is packed is held to the same bound, so a reader allocates no more
    // than the target it was told to expect.
    val tree = rawTree(1)
    val content = byteArrayOf(9)
    assertRefused(payload(LzHuffman.compress(tree), tree.size, content), 1, "for a target of 1")
  }

  @Test
  fun aPackedTreeLongerThanItsStoredBytesCouldHoldIsRefusedBeforeItIsAllocated() {
    // Half a gigabyte of tree from a few hundred bytes: refused on the arithmetic alone, so the
    // allocation that would have been the attack never happens.
    val stored = LzHuffman.compress(rawTree(1L shl 30))
    assertRefused(payload(stored, 1 shl 29), 1L shl 30, "stored bytes")
  }

  @Test
  fun aPackedTreeThatUnpacksShortOfItsDeclaredSizeIsRefused() {
    val tree = rawTree(3)
    val stored = LzHuffman.compress(tree)
    assertRefused(payload(stored, tree.size + 1, "xyz".encodeToByteArray()), 3, "Region tree")
  }

  @Test
  fun aPackedTreeThatUnpacksPastItsDeclaredSizeIsRefused() {
    val tree = rawTree(3)
    val stored = LzHuffman.compress(tree)
    assertRefused(payload(stored, tree.size - 1, "xyz".encodeToByteArray()), 3, "overflows")
  }

  @Test
  fun aPackedTreeWithAnOverSubscribedHuffmanTreeIsRefused() {
    // Every code length 1: hundreds of symbols claiming the two codes of one bit.
    val stored = ByteArray(165) { 0x11 }
    assertRefused(payload(stored, 2), 2, "impossible Huffman tree")
  }

  @Test
  fun aPackedTreeWithBitsAfterItsEndIsRefused() {
    val tree = rawTree(3)
    val stored = LzHuffman.compress(tree) + byteArrayOf(0)
    assertRefused(payload(stored, tree.size, "xyz".encodeToByteArray()), 3, "after its end")
  }

  @Test
  fun aPackedTreeThatEndsMidSymbolIsRefused() {
    val tree = rawTree(3)
    val stored = LzHuffman.compress(tree)
    val cut = stored.copyOf(stored.size - 1)
    assertRefused(payload(cut, tree.size, "xyz".encodeToByteArray()), 3, "Region tree")
  }

  @Test
  fun aPackedTreeWhoseStoredBytesRunPastThePatchIsRefused() {
    val tree = rawTree(3)
    val stored = LzHuffman.compress(tree)
    val longer = payload(stored, tree.size, "xyz".encodeToByteArray(), stored.size + 8)
    assertRefused(longer, 3, "truncated")
  }

  @Test
  fun aPackedTreeIsHeldToTheTreeRulesOnceUnpacked() {
    // Unpacking is not a way around the rest: a tree with bytes left over is refused as one.
    val tree = rawTree(3) + byteArrayOf(0)
    val stored = LzHuffman.compress(tree)
    assertRefused(payload(stored, tree.size, "xyz".encodeToByteArray()), 3, "Region tree")
  }

  /** Keeps what a streamed restore hands over. */
  private class Collected : RestoreTarget {
    private val out = ByteWriter(64)

    override fun write(bytes: ByteArray, from: Int, to: Int) = out.writeBytes(bytes, from, to)

    fun bytes(): ByteArray = out.toByteArray()
  }

  private companion object {
    /** After the magic, the version and the patcher. */
    const val FLAGS_AT = 6
  }
}
