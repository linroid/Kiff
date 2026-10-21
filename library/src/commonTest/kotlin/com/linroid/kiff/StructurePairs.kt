package com.linroid.kiff

import com.linroid.kiff.apk.TestApkBuilder
import com.linroid.kiff.container.TestDexBuilder
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.random.Random

/** A source, a target, and what Kiff 0.1.0's patchers would have written the patch with. */
class StructurePair(
  val name: String,
  val source: ByteArray,
  val target: ByteArray,
  val expected: PatcherId
)

/**
 * Pairs of every structure Kiff detects, and of the inputs that sit at its edges: an archive whose
 * other side is not one, a zip that does not start with a local header, bytes that only look like
 * a zip.
 */
object StructurePairs {

  private fun apk(block: TestApkBuilder.() -> Unit): ByteArray =
    TestApkBuilder().apply(block).build()

  private fun zip(block: TestZipBuilder.() -> Unit): ByteArray =
    TestZipBuilder().apply(block).build()

  private fun sampleApk(seed: Int): ByteArray = apk {
    manifest()
    dex("classes.dex", structuredBytes(120_000, seed))
    dex("classes2.dex", structuredBytes(90_000, seed + 1))
    nativeLibrary("arm64-v8a/libapp.so", structuredBytes(200_000, seed + 2))
    resource("aa.png", structuredBytes(20_000, seed + 3))
    resourceTable()
    signatureFiles(seed + 4)
    signingBlock(structuredBytes(6_000, seed + 5))
  }

  private fun edited(bytes: ByteArray, seed: Int): ByteArray {
    val random = Random(seed)
    val out = bytes.copyOf()
    repeat(out.size / 2_000) { out[random.nextInt(out.size)] = random.nextInt(256).toByte() }
    val cut = out.size / 3
    return out.copyOfRange(0, cut) + random.nextBytes(700) + out.copyOfRange(cut, out.size)
  }

  private fun text(lines: Int, seed: Int): ByteArray {
    val random = Random(seed)
    return buildString {
      repeat(lines) {
        append("line ").append(it).append(" value=").append(random.nextInt(9)).append('\n')
      }
    }.encodeToByteArray()
  }

  private fun dex(seed: Int, codeSize: Int): ByteArray = TestDexBuilder()
    .section(0x0001, structuredBytes(4_000, seed))
    .section(0x0002, structuredBytes(2_000, seed + 1))
    .section(0x2001, structuredBytes(codeSize, seed + 2))
    .build()

  val all: List<StructurePair> by lazy {
    val shared = structuredBytes(80_000, seed = 60)
    val plainZip = zip {
      entry("readme.txt", "a zip with no manifest")
      entry("data.bin", structuredBytes(40_000, seed = 61))
    }
    listOf(
      StructurePair(
        "a rebuilt apk",
        sampleApk(seed = 2),
        apk {
          manifest()
          dex("classes.dex", structuredBytes(120_000, 2))
          dex("classes2.dex", structuredBytes(95_000, 3))
          nativeLibrary("arm64-v8a/libapp.so", structuredBytes(200_000, 4))
          resource("aa.png", structuredBytes(20_000, 5))
          resourceTable()
          signatureFiles(seed = 90)
          signingBlock(structuredBytes(6_000, 7))
        },
        PatcherId.APK
      ),
      StructurePair(
        "an apk whose classes move between dex files",
        apk {
          manifest()
          dex("classes.dex", structuredBytes(60_000, 40) + structuredBytes(60_000, 41))
          dex("classes2.dex", structuredBytes(60_000, 42))
        },
        apk {
          manifest()
          dex("classes.dex", structuredBytes(60_000, 40))
          dex("classes2.dex", structuredBytes(60_000, 41) + structuredBytes(60_000, 42))
        },
        PatcherId.APK
      ),
      StructurePair(
        "an apk that gains a dex",
        apk {
          manifest()
          dex("classes.dex", structuredBytes(70_000, 50))
          dex("classes2.dex", structuredBytes(70_000, 51))
        },
        apk {
          manifest()
          dex("classes.dex", structuredBytes(70_000, 50))
          dex("classes2.dex", structuredBytes(30_000, 52))
          dex("classes3.dex", edited(structuredBytes(70_000, 51), seed = 53))
        },
        PatcherId.APK
      ),
      StructurePair("an apk from a plain zip", plainZip, sampleApk(seed = 9), PatcherId.APK),
      StructurePair(
        "an apk from bytes that are no archive",
        structuredBytes(100_000, seed = 62),
        sampleApk(seed = 10),
        PatcherId.APK
      ),
      StructurePair(
        "an edited zip",
        zip {
          entry("keep.bin", shared)
          entry("notes.txt", text(400, seed = 63))
          entry("packed.bin", structuredBytes(20_000, seed = 64), method = 8)
          entry("drop.txt", "removed in the new version")
        },
        zip {
          entry("keep.bin", shared)
          entry("notes.txt", edited(text(400, seed = 63), seed = 65))
          entry("packed.bin", structuredBytes(21_000, seed = 64), method = 8)
          entry("described.bin", structuredBytes(9_000, seed = 66), dataDescriptor = true)
          comment("rebuilt")
        },
        PatcherId.ZIP
      ),
      StructurePair(
        "a zip with a preamble",
        zip {
          preamble(structuredBytes(5_000, seed = 67))
          entry("payload.bin", structuredBytes(60_000, seed = 68))
        },
        zip {
          preamble(structuredBytes(5_200, seed = 67))
          entry("payload.bin", edited(structuredBytes(60_000, seed = 68), seed = 69))
        },
        PatcherId.ZIP
      ),
      StructurePair(
        "a zip that renames an entry",
        zip { entry("classes2.dex", shared) },
        zip {
          entry("classes3.dex", shared)
          entry("extra.txt", "new")
        },
        PatcherId.ZIP
      ),
      StructurePair(
        "a zip holding a zip and a dex",
        zip {
          entry("inner.zip", zip { entry("a.bin", structuredBytes(30_000, seed = 70)) })
          entry("code.dex", dex(seed = 71, codeSize = 40_000))
        },
        zip {
          entry(
            "inner.zip",
            zip { entry("a.bin", edited(structuredBytes(30_000, seed = 70), seed = 72)) }
          )
          entry("code.dex", dex(seed = 71, codeSize = 42_000))
        },
        PatcherId.ZIP
      ),
      StructurePair(
        "an empty zip that gains an entry",
        zip { },
        zip { entry("new.txt", "added") },
        PatcherId.ZIP
      ),
      StructurePair(
        "a zip from bytes that are no archive",
        structuredBytes(50_000, seed = 73),
        plainZip,
        PatcherId.ZIP
      ),
      StructurePair("a zip from an apk", sampleApk(seed = 11), plainZip, PatcherId.ZIP),
      StructurePair(
        "edited bytes",
        structuredBytes(150_000, seed = 74),
        edited(structuredBytes(150_000, seed = 74), seed = 75),
        PatcherId.BINARY
      ),
      StructurePair(
        "edited text",
        text(2_000, seed = 76),
        edited(text(2_000, seed = 76), seed = 77),
        PatcherId.BINARY
      ),
      StructurePair(
        "bytes from nothing",
        ByteArray(0),
        structuredBytes(5_000, 78),
        PatcherId.BINARY
      ),
      StructurePair(
        "nothing from bytes",
        structuredBytes(5_000, 79),
        ByteArray(0),
        PatcherId.BINARY
      ),
      StructurePair("nothing from nothing", ByteArray(0), ByteArray(0), PatcherId.BINARY),
      StructurePair(
        "unrelated bytes",
        Random(80).nextBytes(20_000),
        Random(81).nextBytes(20_000),
        PatcherId.BINARY
      ),
      StructurePair(
        "bytes that only start like a zip",
        structuredBytes(30_000, seed = 82),
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + structuredBytes(30_000, seed = 82),
        PatcherId.BINARY
      ),
      StructurePair(
        "bytes from a zip",
        plainZip,
        edited(plainZip, seed = 83).copyOfRange(0, plainZip.size - 30),
        PatcherId.BINARY
      )
    )
  }
}
