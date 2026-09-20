package com.linroid.kiff.format

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffException
import com.linroid.kiff.Patcher
import com.linroid.kiff.container.TestDexBuilder
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.structuredBytes
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A patch arrives over a network. It is untrusted input, and the failure that matters is not a
 * crash but a *quiet* one: a malformed patch producing bytes that are merely wrong.
 *
 * So the property, over every corruption these can think of: applying it either reproduces the
 * target exactly or raises a [KiffException]. Never anything else, and never a plausible-looking
 * file that is not the one that was asked for.
 */
class MalformedPatchTest {

  private fun table(count: Int, from: Int): ByteArray {
    val out = ByteArray(count * 4)
    var value = from
    for (row in 0 until count) {
      for (i in 0 until 4) out[row * 4 + i] = ((value shr (8 * i)) and 0xFF).toByte()
      value += 11 + (row % 7)
    }
    return out
  }

  /** One case per encoding, so a corruption can land anywhere the format goes. */
  private fun cases(): List<Triple<String, Patcher, Pair<ByteArray, ByteArray>>> = listOf(
    Triple(
      "binary", Kiff.binary,
      structuredBytes(400, 1) to structuredBytes(400, 1).also {
        for (at in it.indices step 16) it[at] = (it[at] + 3).toByte()
      }
    ),
    Triple(
      "zip with text", Kiff.zip,
      TestZipBuilder().entry("a.txt", (1..30).joinToString("\n") { "line $it" } + "\n").build() to
        TestZipBuilder().entry(
          "a.txt",
          (1..30).joinToString("\n") { if (it == 7) "changed" else "line $it" } + "\n"
        ).build()
    ),
    Triple(
      "zip with an added entry", Kiff.zip,
      TestZipBuilder().entry("a.bin", structuredBytes(150, 2)).build() to
        TestZipBuilder().entry("a.bin", structuredBytes(150, 2))
          .entry("b.bin", structuredBytes(120, 3)).build()
    ),
    Triple(
      "dex columns", Kiff.zip,
      TestZipBuilder().entry(
        "classes.dex",
        TestDexBuilder().section(0x0001, table(80, 300))
          .section(0x2001, structuredBytes(180, 4)).build()
      ).build() to
        TestZipBuilder().entry(
          "classes.dex",
          TestDexBuilder().section(0x0001, table(80, 344))
            .section(0x2001, structuredBytes(180, 4)).build()
        ).build()
    )
  )

  /** Discards its bytes: what matters is whether the restore is refused, not what it produced. */
  private object Discard : RestoreTarget {
    override fun write(bytes: ByteArray, from: Int, to: Int) = Unit
  }

  private fun check(label: String, patcher: Patcher, source: ByteArray, target: ByteArray, patch: ByteArray) {
    val restored = try {
      patcher.applyPatch(source, patch)
    } catch (e: KiffException) {
      return
    } catch (e: Throwable) {
      fail("$label raised ${e::class.simpleName} rather than a KiffException: ${e.message}")
    }
    if (!restored.contentEquals(target)) {
      fail("$label restored $${restored.size} bytes that are not the target, without complaining")
    }
    // The streaming path has to refuse the same things, and cannot check afterwards.
    try {
      patcher.applyPatch(ByteArraySource(source), patch, Discard)
    } catch (e: KiffException) {
      return
    } catch (e: Throwable) {
      fail("$label streaming raised ${e::class.simpleName}: ${e.message}")
    }
  }

  @Test
  fun everySingleByteCorruptionIsRefusedOrExact() {
    for ((label, patcher, pair) in cases()) {
      val (source, target) = pair
      val patch = patcher.createPatch(source, target)
      for (at in patch.indices) {
        for (delta in listOf(1, 0x7F, 0x80)) {
          val broken = patch.copyOf().also { it[at] = (it[at] + delta).toByte() }
          check("$label byte $at +$delta", patcher, source, target, broken)
        }
      }
    }
  }

  @Test
  fun everyTruncationIsRefused() {
    for ((label, patcher, pair) in cases()) {
      val (source, target) = pair
      val patch = patcher.createPatch(source, target)
      for (length in 0 until patch.size) {
        check("$label truncated to $length", patcher, source, target, patch.copyOfRange(0, length))
      }
    }
  }

  @Test
  fun trailingRubbishIsRefusedOrIgnored() {
    for ((label, patcher, pair) in cases()) {
      val (source, target) = pair
      val patch = patcher.createPatch(source, target)
      for (extra in listOf(1, 7, 64)) {
        check("$label plus $extra bytes", patcher, source, target, patch + ByteArray(extra) { 0x5A })
      }
    }
  }

  @Test
  fun manyRandomCorruptionsAtOnceAreRefusedOrExact() {
    // Single flips are the easy case. Real corruption arrives in runs, and a patch mangled in
    // several places at once is where a reader that checks one thing at a time comes apart.
    for ((label, patcher, pair) in cases()) {
      val (source, target) = pair
      val patch = patcher.createPatch(source, target)
      for (seed in 1..300) {
        val random = Random(seed)
        val broken = patch.copyOf()
        repeat(random.nextInt(1, 8)) {
          broken[random.nextInt(broken.size)] = random.nextInt(256).toByte()
        }
        check("$label mangled with seed $seed", patcher, source, target, broken)
      }
    }
  }

  @Test
  fun aPatchCannotAskForAnArbitraryAllocation() {
    // The content length is unpacked into an array of exactly that size, and it arrives from the
    // patch. Every content byte becomes at most one target byte, so anything longer is a patch
    // describing more than it could use - and, left unchecked, a way to exhaust memory.
    val source = structuredBytes(400, 11)
    val target = structuredBytes(400, 12)
    val patch = Kiff.binary.createPatch(source, target)

    val reader = ByteReader(patch)
    val header = PatchFormat.readHeader(reader)
    val payloadAt = reader.offset
    val payload = ByteReader(patch, payloadAt)
    val treeLength = payload.readVarInt()
    payload.readVarInt() // the honest content length, about to be replaced

    val out = ByteWriter(patch.size + 16)
    out.writeBytes(patch, 0, payloadAt)
    out.writeVarInt(treeLength)
    out.writeVarLong(1L shl 30) // a gigabyte of content for four hundred bytes of target
    out.writeBytes(patch, payload.offset, patch.size)

    val failure = assertFailsWith<KiffException.InvalidPatch> {
      Kiff.binary.applyPatch(source, out.toByteArray())
    }
    assertTrue("content" in failure.message.orEmpty(), failure.message.orEmpty())
    assertTrue(header.targetSize == 400L)
  }

  @Test
  fun rubbishThatIsNotAPatchAtAllIsRefused() {
    val source = structuredBytes(200, 9)
    for (size in listOf(0, 1, 4, 11, 64, 500)) {
      for (fill in listOf(0, 0xFF, 0x4B)) {
        val rubbish = ByteArray(size) { fill.toByte() }
        try {
          Kiff.binary.applyPatch(source, rubbish)
          fail("accepted $size bytes of ${fill.toString(16)} as a patch")
        } catch (e: KiffException) {
          // expected
        } catch (e: Throwable) {
          fail("$size bytes of rubbish raised ${e::class.simpleName}: ${e.message}")
        }
      }
    }
  }
}
