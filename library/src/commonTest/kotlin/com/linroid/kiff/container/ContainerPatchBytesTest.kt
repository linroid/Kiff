package com.linroid.kiff.container

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffOptions
import com.linroid.kiff.NestedContainers
import com.linroid.kiff.apk.TestApkBuilder
import com.linroid.kiff.format.Crc32
import com.linroid.kiff.format.withStoredTree
import com.linroid.kiff.region.RegionAlgorithm
import com.linroid.kiff.structuredBytes
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Patches built through every path a [ContainerFormat] takes part in, pinned by size and CRC-32.
 *
 * `StructureDetectionTest` pins what each kind of file is taken to be. This pins what taking it
 * apart produces: formats found inside other formats, a registered format weighed against the
 * whole file, grouped dex files, columns, pairing by ordinal, nesting as deep as a reader goes, and
 * the malformed inputs a format has to leave whole. Equal here means a change left every patch byte
 * for byte as it was, as the format API's move from arrays and `Int` offsets to sources and `Long`
 * ones did. Two writer changes did not, by design, and the values were captured again for each:
 * the delta writer copying the long exact stretches inside a DIFF rather than storing zeroes for
 * them, and the content codec matching lazily, which packs the same regions tighter. The id table
 * gained an entry with the first: shifted alone, it no longer made taking the dex apart pay, and so
 * no longer reached columns.
 */
class ContainerPatchBytesTest {

  private class Case(
    val name: String,
    val source: ByteArray,
    val target: ByteArray,
    val options: KiffOptions = KiffOptions()
  )

  private class Pinned(val size: Int, val crc32: UInt)

  private val pinned = mapOf(
    "a registered format, whole" to Pinned(4716, 0x322a2f7eu),
    "a registered format inside a zip" to Pinned(4834, 0xa1790ebau),
    "a dex, whole" to Pinned(2871, 0x745d0795u),
    "a dex whose id table shifts" to Pinned(348, 0xe9b9dd78u),
    "a dex with an offset near Int.MAX_VALUE" to Pinned(163, 0x3526ff37u),
    "an apk whose dex files are grouped" to Pinned(2741, 0x4a208fc0u),
    "an apk that loses a dex" to Pinned(1136, 0xe89d86e7u),
    "an apk with ApkFormat registered" to Pinned(44526, 0x3f345dd2u),
    "an apk without DexFormat" to Pinned(43302, 0x6443f734u),
    "a reordered inner archive" to Pinned(323, 0x56148f92u),
    "an inner archive with edited text" to Pinned(307, 0x59589686u),
    "a deflated inner archive" to Pinned(318, 0xe5948d61u),
    "archives nested eight deep" to Pinned(760, 0x0d6950adu),
    "archives nested twelve deep" to Pinned(11975, 0xeb071b38u),
    "a zip with a gap before its directory" to Pinned(1158, 0x2629c453u)
  )

  @Test
  fun everyPatchIsByteForByteWhatItWas() {
    assertEquals(pinned.keys, cases.map { it.name }.toSet())
    val actual = cases.map { case ->
      val patch = Kiff.createPatch(case.source, case.target, case.options)
      assertContentEquals(case.target, Kiff.applyPatch(case.source, patch), case.name)
      // Packing the tree came later, and only where it makes the patch smaller; with its tree
      // stored again, a packed patch is the one written before.
      val stored = if (Kiff.info(patch).treePacked) withStoredTree(patch) else patch
      case.name to Pinned(stored.size, Crc32.compute(stored))
    }
    // Every case is listed before any is asserted, so one run says what all of them are now.
    val report = actual.joinToString("\n") { (name, it) ->
      "\"$name\" to Pinned(${it.size}, 0x${it.crc32.toString(16)}u),"
    }
    for ((name, now) in actual) {
      val expected = pinned.getValue(name)
      assertEquals(expected.size, now.size, "size of $name; now:\n$report")
      assertEquals(expected.crc32, now.crc32, "CRC-32 of $name; now:\n$report")
    }
  }

  private val cases: List<Case> by lazy {
    val withSections = KiffOptions(containers = NestedContainers + TestSectionFormat())
    listOf(
      Case(
        "a registered format, whole",
        sections(seed = 10, edited = false),
        sections(seed = 10, edited = true),
        withSections
      ),
      Case(
        "a registered format inside a zip",
        zip { entry("payload.sect", sections(seed = 20, edited = false)) },
        zip { entry("payload.sect", sections(seed = 20, edited = true)) },
        withSections
      ),
      Case("a dex, whole", dex(seed = 1, codeSize = 60_000), dex(seed = 1, codeSize = 63_000)),
      Case(
        "a dex whose id table shifts",
        zip { entry("classes.dex", tableDex()) },
        zip { entry("classes.dex", tableDex(insertAt = 20)) }
      ),
      Case(
        "a dex with an offset near Int.MAX_VALUE",
        zip { entry("classes.dex", dex(seed = 9, codeSize = 4_000)) },
        zip { entry("classes.dex", withMapOffset(dex(seed = 9, codeSize = 4_000))) }
      ),
      Case(
        "an apk whose dex files are grouped",
        apk {
          manifest()
          dex("classes.dex", dexWith(structuredBytes(50_000, seed = 60), seed = 62))
          dex("classes2.dex", dexWith(structuredBytes(50_000, seed = 61), seed = 63))
        },
        apk {
          manifest()
          dex("classes.dex", dexWith(structuredBytes(50_000, seed = 60), seed = 64))
          dex("classes2.dex", dexWith(structuredBytes(50_000, seed = 61), seed = 65))
        }
      ),
      Case(
        "an apk that loses a dex",
        apk {
          manifest()
          dex("classes.dex", structuredBytes(40_000, seed = 70))
          dex("classes2.dex", structuredBytes(30_000, seed = 71))
          dex("classes3.dex", structuredBytes(40_000, seed = 72))
        },
        apk {
          manifest()
          dex("classes.dex", structuredBytes(40_000, seed = 70))
          dex("classes2.dex", edited(structuredBytes(40_000, seed = 72), seed = 73))
        }
      ),
      Case(
        "an apk with ApkFormat registered",
        sampleApk(seed = 80),
        sampleApk(seed = 81),
        KiffOptions(containers = ContainerRegistry(ApkFormat(), DexFormat()))
      ),
      Case(
        "an apk without DexFormat",
        sampleApk(seed = 82),
        sampleApk(seed = 83),
        KiffOptions(containers = ContainerRegistry(ZipFormat()))
      ),
      Case(
        "a reordered inner archive",
        zip { entry("bundle.zip", innerArchive(listOf(0, 1, 2))) },
        zip { entry("bundle.zip", innerArchive(listOf(2, 0, 1))) }
      ),
      Case(
        "an inner archive with edited text",
        zip { entry("bundle.zip", configArchive(edited = false)) },
        zip { entry("bundle.zip", configArchive(edited = true)) }
      ),
      Case(
        "a deflated inner archive",
        zip { entry("bundle.zip", innerArchive(listOf(0, 1)), method = 8) },
        zip { entry("bundle.zip", innerArchive(listOf(1, 0)), method = 8) }
      ),
      Case(
        "archives nested eight deep",
        nested(levels = 8, data = structuredBytes(3_000, seed = 99)),
        nested(levels = 8, data = flipped(structuredBytes(3_000, seed = 99))),
        storesArchives
      ),
      Case(
        "archives nested twelve deep",
        nested(levels = 12, data = structuredBytes(3_000, seed = 99)),
        nested(levels = 12, data = flipped(structuredBytes(3_000, seed = 99))),
        storesArchives
      ),
      Case(
        "a zip with a gap before its directory",
        zip {
          entry("a.bin", structuredBytes(20_000, seed = 90))
          beforeDirectory(structuredBytes(3_000, seed = 91))
        },
        zip {
          entry("a.bin", edited(structuredBytes(20_000, seed = 90), seed = 92))
          beforeDirectory(structuredBytes(3_100, seed = 91))
        }
      )
    )
  }

  /** Stores archives outright, so taking each one apart is the smaller choice at every level. */
  private val storesArchives = KiffOptions(
    planner = { if (it.name.endsWith(".zip")) RegionAlgorithm.RAW else RegionAlgorithm.BINARY }
  )

  private fun zip(block: TestZipBuilder.() -> Unit): ByteArray =
    TestZipBuilder().apply(block).build()

  private fun apk(block: TestApkBuilder.() -> Unit): ByteArray =
    TestApkBuilder().apply(block).build()

  private fun sampleApk(seed: Int): ByteArray = apk {
    manifest()
    dex("classes.dex", dex(seed = seed, codeSize = 50_000))
    dex("classes2.dex", dex(seed = seed + 1, codeSize = 30_000))
    nativeLibrary("arm64-v8a/libapp.so", structuredBytes(60_000, 7))
    resourceTable()
    signatureFiles(seed + 2)
    signingBlock(structuredBytes(2_000, seed + 3))
  }

  private fun dex(seed: Int, codeSize: Int): ByteArray = TestDexBuilder()
    .section(0x0001, structuredBytes(800, seed))
    .section(0x0002, structuredBytes(400, seed + 1))
    .section(0x0006, structuredBytes(600, seed + 2))
    .section(0x2001, structuredBytes(codeSize, seed + 3))
    .section(0x2002, structuredBytes(1_200, seed + 4))
    .build()

  private fun dexWith(code: ByteArray, seed: Int): ByteArray = TestDexBuilder()
    .section(0x0001, structuredBytes(600, seed))
    .section(0x2001, code)
    .section(0x2002, structuredBytes(900, seed + 100))
    .build()

  /** A dex whose `string_ids` is a table of offsets that all shift when [firstOffset] does. */
  private fun tableDex(insertAt: Int? = null): ByteArray = TestDexBuilder()
    .section(0x0001, stringIdTable(400, insertAt))
    .section(0x2001, structuredBytes(3_000, 1))
    .build()

  private fun withMapOffset(dex: ByteArray): ByteArray = dex.copyOf().also {
    for (i in 0 until 4) it[0x34 + i] = (Int.MAX_VALUE ushr (8 * i)).toByte()
  }

  private fun innerArchive(order: List<Int>): ByteArray = zip {
    for (i in order) entry("$i.bin", structuredBytes(20_000, seed = 100 + i))
  }

  private fun configArchive(edited: Boolean): ByteArray {
    val original = (1..500).joinToString("\n") { "config entry $it = ${it * 7 % 31}" } + "\n"
    val text =
      if (edited) original.replace("entry 250 ", "inserted\nconfig entry 250 ") else original
    return zip {
      entry("app.config", text)
      entry("d.bin", structuredBytes(5_000, seed = 75))
    }
  }

  private fun nested(levels: Int, data: ByteArray): ByteArray {
    var archive = zip {
      entry("pad.bin", structuredBytes(3_000, seed = 0))
      entry("data.bin", data)
    }
    for (level in 1 until levels) {
      val inner = archive
      archive = zip {
        entry("pad.bin", structuredBytes(3_000, seed = level))
        entry("inner$level.zip", inner)
      }
    }
    return archive
  }

  private fun flipped(bytes: ByteArray): ByteArray =
    bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }

  private fun edited(bytes: ByteArray, seed: Int): ByteArray {
    val random = Random(seed)
    val out = bytes.copyOf()
    repeat(out.size / 2_000) { out[random.nextInt(out.size)] = random.nextInt(256).toByte() }
    val cut = out.size / 3
    return out.copyOfRange(0, cut) + random.nextBytes(700) + out.copyOfRange(cut, out.size)
  }

  /** Bytes, then lines of text, then bytes, with every seventh line changed when [edited]. */
  private fun sections(seed: Int, edited: Boolean): ByteArray {
    val random = Random(seed)
    val lines = buildString {
      repeat(3_000) {
        append("key").append(it).append('=').append(random.nextInt(1_000))
        if (edited && it % 7 == 0) append(" changed")
        append('\n')
      }
    }
    return TestSectionFormat.build(
      listOf(
        structuredBytes(20_000, seed),
        lines.encodeToByteArray(),
        structuredBytes(20_000, seed + 1)
      )
    )
  }
}
