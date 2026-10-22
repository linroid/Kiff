package com.example.tar

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffOptions
import com.linroid.kiff.KiffReports
import com.linroid.kiff.NestedContainers
import com.linroid.kiff.container.ContainerFormatTiling
import com.linroid.kiff.io.asSource
import com.linroid.kiff.region.RegionKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TarFormat], tested as its author outside Kiff would: through Kiff's public API alone. */
class TarFormatTest {

  private val format = TarFormat()
  private val options = KiffOptions(containers = NestedContainers + TarFormat())

  @Test
  fun itTilesEveryShapeOfArchive() {
    val shapes = mapOf(
      "plain" to TarBuilder().file("a.txt", "alpha").file("b.bin", bytes(5_000, seed = 1)),
      "with a member a whole number of blocks long" to TarBuilder().file("c.bin", bytes(1_024, 2)),
      "with an empty member" to TarBuilder().file("empty", ByteArray(0)).file("d.txt", "delta"),
      "with a directory" to TarBuilder().directory("dir/").file("dir/e.txt", "echo"),
      "with a long path" to TarBuilder().file("x/".repeat(60) + "f.txt", "foxtrot")
    )
    for ((shape, builder) in shapes) {
      for ((ending, archive) in endings(builder)) {
        assertTrue(format.detect(archive.asSource(), 0, archive.size.toLong()), "$shape, $ending")
        assertNull(ContainerFormatTiling.describe(format, archive.asSource()), "$shape, $ending")
      }
    }
  }

  private fun endings(builder: TarBuilder) = mapOf(
    "as tar writes it" to builder.build(),
    "with no record padding" to builder.build(recordSize = 0),
    "with bytes after its end" to builder.build(trailer = bytes(700, seed = 3)),
    "with no end blocks" to builder.build(endBlocks = 0, recordSize = 0)
  )

  @Test
  fun itTilesWhereverItSitsInsideSomethingElse() {
    // Kiff hands a format the input a container sits in, not a copy of it, so a tar found at 100
    // has to read its headers from 100 on and answer in the same coordinates.
    val archive = TarBuilder().file("a.txt", "alpha").file("b.bin", bytes(3_000, seed = 4)).build()
    val wrapped = bytes(100, seed = 5) + archive + bytes(60, seed = 6)
    val from = 100L
    val to = from + archive.size
    assertNull(ContainerFormatTiling.describe(format, wrapped.asSource(), from, to))
    val children = format.decompose(wrapped.asSource(), from, to)
    assertEquals(listOf("a.txt", "b.bin", TarFormat.END_OF_ARCHIVE), children.map { it.name })
    assertEquals(from + TarFormat.BLOCK, children[0].contentFrom, "data follows the header")
  }

  @Test
  fun anythingThatIsNotATarIsLeftAlone() {
    val noise = bytes(2_000, seed = 7)
    assertFalse(format.detect(noise.asSource(), 0, noise.size.toLong()))
    assertTrue(format.decompose(noise.asSource(), 0, noise.size.toLong()).isEmpty())

    // A header whose checksum does not add up is not a header.
    val archive = TarBuilder().file("a.txt", "alpha").build()
    archive[10] = 'Z'.code.toByte()
    assertFalse(format.detect(archive.asSource(), 0, archive.size.toLong()))

    // Nor is a member whose size runs past the end: describing it would leave bytes to no child.
    val truncated = TarBuilder().file("b.bin", bytes(4_000, seed = 8)).build(recordSize = 0)
      .copyOf(2_000)
    assertTrue(format.decompose(truncated.asSource(), 0, truncated.size.toLong()).isEmpty())
  }

  @Test
  fun aTarIsTakenApartAsTheWholeFile() {
    val source = release(edited = false)
    val target = release(edited = true)

    val report = KiffReports.explain(source, target, options)
    assertEquals(
      listOf("bin/app", "etc/app.conf", "share/data.bin", TarFormat.END_OF_ARCHIVE),
      report.regions.map { it.name }
    )
    assertEquals(RegionKind.GAP, report.regions.last().kind)

    val patch = Kiff.createPatch(source, target, options)
    assertEquals(report.patchSize, patch.size.toLong())
    assertTrue(patch.size < Kiff.createPatch(source, target).size, "taken apart, it is smaller")
    // Applying needs no idea that tar exists.
    assertContentEquals(target, Kiff.applyPatch(source, patch))
  }

  @Test
  fun aTarInsideATarIsTakenApartToo() {
    val source = TarBuilder().file("release.tar", release(edited = false)).build()
    val target = TarBuilder().file("release.tar", release(edited = true)).build()

    val names = KiffReports.explain(source, target, options).allRegions.map { it.name }
    assertTrue("etc/app.conf" in names, names.toString())
    assertContentEquals(target, Kiff.applyPatch(source, Kiff.createPatch(source, target, options)))
  }

  @Test
  fun anArchiveLargerThanFourGigabytesIsDescribedFromItsHeaders() {
    // Over 14 GiB of archive, none of it in memory: the source makes zeros up as it is read, except
    // where a header or a small member is. Octal holds a size up to 8 GiB; past that GNU tar writes
    // base-256, so the archive has a member of each.
    val gib = 1L shl 30
    val members = listOf(
      Triple("notes.txt", 280L, "release notes\n".repeat(20).encodeToByteArray()),
      Triple("disk.img", 5 * gib, null),
      Triple("backup.img", 9 * gib + 100, null),
      Triple("after.bin", 3_000L, bytes(3_000, seed = 9))
    )
    // Placed past 4 GiB in the input, at an offset that is no multiple of anything, so a position
    // that lost its high bits, or assumed the archive starts a block, would read the wrong bytes.
    val base = 4 * gib + 7
    val blocks = LinkedHashMap<Long, ByteArray>()
    val starts = ArrayList<Long>()
    var at = base
    for ((name, size, data) in members) {
      starts += at
      blocks[at] = TarBuilder.header(name, size)
      if (data != null) blocks[at + TarFormat.BLOCK] = data
      at += TarFormat.BLOCK + size + TarBuilder.padding(size)
    }
    val end = at + 2 * TarFormat.BLOCK
    val input = SparseSource(end + 4_096, blocks)

    assertTrue(format.detect(input, base, end))
    assertNull(ContainerFormatTiling.describe(format, input, base, end))

    val before = input.bytesRead
    val children = format.decompose(input, base, end)
    assertTrue(
      input.bytesRead - before <= 8 * TarFormat.BLOCK,
      "read ${input.bytesRead - before} bytes of $end; only its headers had to be"
    )
    assertEquals(members.map { it.first } + TarFormat.END_OF_ARCHIVE, children.map { it.name })
    for ((i, member) in members.withIndex()) {
      val child = children[i]
      assertEquals(starts[i], child.from, member.first)
      assertEquals(starts[i] + TarFormat.BLOCK, child.contentFrom, member.first)
      assertEquals(member.second, child.contentTo - child.contentFrom, member.first)
    }
    assertTrue(children.last().from > base + 14 * gib, "positions keep their high bits")
  }

  /**
   * A small release: a binary, a config file of lines, and data, with every seventh line of the
   * config changed when [edited] - what a line diff of one member describes better than a byte
   * search over the whole archive does.
   */
  private fun release(edited: Boolean): ByteArray {
    val random = Random(10)
    val config = buildString {
      repeat(3_000) {
        append("key").append(it).append('=').append(random.nextInt(1_000))
        if (edited && it % 7 == 0) append(" changed")
        append('\n')
      }
    }
    return TarBuilder()
      .file("bin/app", bytes(20_000, seed = 11))
      .file("etc/app.conf", config)
      .file("share/data.bin", bytes(20_000, seed = 12))
      .build()
  }

  /** Bytes with some repetition in them, as files have. */
  private fun bytes(size: Int, seed: Int): ByteArray {
    val random = Random(seed)
    val words = List(32) { "word$it ".encodeToByteArray() }
    val out = ByteArray(size)
    var at = 0
    while (at < size) {
      val chunk = if (random.nextBoolean()) words.random(random) else random.nextBytes(16)
      val count = minOf(chunk.size, size - at)
      chunk.copyInto(out, at, 0, count)
      at += count
    }
    return out
  }
}
