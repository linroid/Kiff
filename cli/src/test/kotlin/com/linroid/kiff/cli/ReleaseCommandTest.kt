package com.linroid.kiff.cli

import com.linroid.kiff.Kiff
import com.linroid.kiff.PatcherId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class ReleaseCommandTest {

  private val dir: File = Files.createTempDirectory("kiff-release").toFile()

  private val out = File(dir, "out")

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun everyPatchRestoresItsTargetByteForByte() {
    val target = zip("app-1.2.zip", app(2))
    val sources = mapOf(
      "v1.0" to zip("app-1.0.zip", app(0)),
      "v1.1" to zip("app-1.1.zip", app(1))
    )

    val status = kiff(
      "release", "--to", "v1.2=$target",
      "--from", "v1.0=${sources.getValue("v1.0")}",
      "--from", "v1.1=${sources.getValue("v1.1")}",
      "--out", out.path
    )

    assertEquals(0, status)
    val patches = manifest().files().single().patches()
    assertEquals(listOf("v1.0", "v1.1"), patches.map { it.string("from") })
    for (patch in patches) {
      val restored = File(dir, "restored")
      Kiff.applyPatch(
        sources.getValue(patch.string("from")),
        File(out, patch.string("name")).path,
        restored.path
      )
      assertContentEquals(File(target).readBytes(), restored.readBytes())
    }
  }

  @Test
  fun theManifestDescribesTheFilesExactly() {
    val target = zip("app-1.2.zip", app(2))
    val source = zip("app-1.1.zip", app(1))

    val status = kiff(
      "release", "--to", "v1.2=$target", "--from", "v1.1=$source", "--out", out.path,
      "--base-url", "https://example.com/releases/v1.2"
    )

    assertEquals(0, status)
    val manifest = manifest()
    assertEquals(1, manifest.getValue("schema").jsonPrimitive.int)
    assertEquals("kiff $kiffVersion", manifest.string("generator"))
    // Stamped by the build, not left as the resource's placeholder or the fallback.
    assertFalse("$" in kiffVersion || kiffVersion == "unknown", kiffVersion)
    val file = manifest.files().single()
    assertEquals("app-1.2.zip", file.string("name"))
    assertEquals("v1.2", file.string("version"))
    assertEquals("https://example.com/releases/v1.2/app-1.2.zip", file.string("url"))
    assertDescribes(File(target), file)

    val patch = file.patches().single()
    val patchFile = File(out, "app-1.2.zip.from-v1.1.kiff")
    assertEquals(patchFile.name, patch.string("name"))
    assertEquals("https://example.com/releases/v1.2/${patchFile.name}", patch.string("url"))
    assertDescribes(patchFile, patch)
    val sourceEntry = patch.getValue("source").jsonObject
    assertEquals("app-1.1.zip", sourceEntry.string("name"))
    assertDescribes(File(source), sourceEntry)
    val info = Kiff.info(patchFile.path)
    assertEquals(info.patcher.name.lowercase(), patch.string("patcher"))
    assertEquals(info.formatVersion, patch.getValue("format").jsonPrimitive.int)
    assertTrue(info.formatVersion in Kiff.readableFormats)
  }

  @Test
  fun withoutABaseUrlTheManifestGivesNoUrls() {
    val status = kiff(
      "release", "--to", zip("app-1.2.zip", app(2)), "--from", zip("app-1.1.zip", app(1)),
      "--out", out.path
    )

    assertEquals(0, status)
    val file = manifest().files().single()
    assertFalse("url" in file)
    assertFalse("url" in file.patches().single())
  }

  @Test
  fun aPatchLargerThanTheMaxRatioIsDroppedAndDeleted() {
    val targetBytes = Random(1).nextBytes(40_000)
    val target = file("data-2.bin", targetBytes)
    val close = file("data-1.bin", targetBytes.copyOf().also { it[100] = 7; it[30_000] = 9 })
    val unrelated = file("data-0.bin", Random(2).nextBytes(40_000))

    val status = kiff(
      "release", "--to", target, "--from", "near=$close", "--from", "far=$unrelated",
      "--out", out.path
    )

    // Dropping is the job done, not a failure: the far release downloads the whole file instead.
    assertEquals(0, status)
    assertEquals(listOf("near"), manifest().files().single().patches().map { it.string("from") })
    assertTrue(File(out, "data-2.bin.from-near.kiff").exists())
    assertFalse(File(out, "data-2.bin.from-far.kiff").exists())
  }

  @Test
  fun theMaxRatioDecidesWhatIsDropped() {
    val target = file("data-2.bin", Random(1).nextBytes(40_000))
    val unrelated = file("data-0.bin", Random(2).nextBytes(40_000))

    val status = kiff(
      "release", "--to", target, "--from", "far=$unrelated", "--out", out.path,
      "--max-ratio", "2"
    )

    assertEquals(0, status)
    assertEquals(1, manifest().files().single().patches().size)
    assertTrue(File(out, "data-2.bin.from-far.kiff").exists())
  }

  @Test
  fun aSourceThatFailsFailsTheRunWhileTheOthersAreStillWritten() {
    val target = zip("app-1.2.zip", app(2))
    val good = zip("app-1.1.zip", app(1))
    val unreadable = File(dir, "app-1.0.zip").apply { mkdirs() }.path
    // Sparse, so it costs nothing on disk, and over the 2 GB the bundled search can index.
    val huge = File(dir, "app-0.9.zip").also {
      RandomAccessFile(it, "rw").use { file -> file.setLength(3L shl 30) }
    }.path

    val status = kiff(
      "release", "--to", "v1.2=$target",
      "--from", "v0.9=$huge", "--from", "v1.0=$unreadable", "--from", "v1.1=$good",
      "--out", out.path
    )

    assertEquals(1, status)
    assertEquals(listOf("v1.1"), manifest().files().single().patches().map { it.string("from") })
    // Nothing of the failures is left where a script uploading the directory would publish it.
    assertEquals(
      setOf("app-1.2.zip.from-v1.1.kiff", ReleaseManifest.FILE_NAME),
      out.list()!!.toSet()
    )
  }

  @Test
  fun aSecondRunMergesIntoTheManifestReplacingOnlyItsOwnFile() {
    val target = zip("app-1.2.zip", app(2))
    val tool = file("tool-2.bin", Random(5).nextBytes(10_000))
    val toolSource = file("tool-1.bin", File(tool).readBytes().copyOf().also { it[0] = 1 })
    val newer = "v1.1=${zip("app-1.1.zip", app(1))}"
    val manifestFile = File(out, ReleaseManifest.FILE_NAME)

    val older = "v1.0=${zip("app-1.0.zip", app(0))}"
    kiff("release", "--to", target, "--from", older, "--out", out.path)
    kiff("release", "--to", tool, "--from", "v1=$toolSource", "--out", out.path)
    // Fields this version does not write, as a later one or a hand edit might add, are kept.
    val written = manifest()
    val marked = written.files().map {
      if (it.string("name") == "tool-2.bin") JsonObject(it + ("x" to JsonPrimitive(1))) else it
    }
    val edited = written + ("files" to JsonArray(marked)) + ("note" to JsonPrimitive("kept"))
    manifestFile.writeText(JsonObject(edited).toString())
    val status = kiff("release", "--to", target, "--from", newer, "--out", out.path)

    assertEquals(0, status)
    val manifest = manifest()
    assertEquals("kept", manifest.string("note"))
    val files = manifest.files()
    assertEquals(listOf("app-1.2.zip", "tool-2.bin"), files.map { it.string("name") })
    assertEquals(listOf("v1.1"), files[0].patches().map { it.string("from") })
    assertEquals(listOf("v1"), files[1].patches().map { it.string("from") })
    assertEquals(1, files[1].getValue("x").jsonPrimitive.int)

    // The same inputs write the same manifest, byte for byte.
    val first = manifestFile.readBytes()
    kiff("release", "--to", target, "--from", newer, "--out", out.path)
    assertContentEquals(first, manifestFile.readBytes())
  }

  @Test
  fun aManifestThatCannotBeMergedIntoIsRefusedBeforeAnyPatchIsMade() {
    val target = zip("app-1.2.zip", app(2))
    val source = zip("app-1.1.zip", app(1))
    val manifest = File(dir, "manifest.json")
    for (text in listOf("""{"schema":2,"files":[]}""", "not json", """{"schema":1}""")) {
      manifest.writeText(text)

      val status = kiff(
        "release", "--to", target, "--from", source, "--out", out.path,
        "--manifest", manifest.path
      )

      assertEquals(1, status, text)
      assertEquals(text, manifest.readText())
      assertFalse(out.exists(), text)
    }
  }

  @Test
  fun patchesAreNamedFromTheTemplate() {
    val target = zip("app-1.2.zip", app(2))
    val status = kiff(
      "release", "--to", "v1.2=$target",
      "--from", "v1.0=${zip("app-1.0.zip", app(0))}",
      "--from", "v1.1=${zip("app-1.1.zip", app(1))}",
      "--out", out.path, "--name", "{to}-from-{from}-of-{target}.patch"
    )

    assertEquals(0, status)
    val names = listOf(
      "v1.2-from-v1.0-of-app-1.2.zip.patch",
      "v1.2-from-v1.1-of-app-1.2.zip.patch"
    )
    assertEquals(names, manifest().files().single().patches().map { it.string("name") })
    for (name in names) assertTrue(File(out, name).exists(), name)
  }

  @Test
  fun aTemplateThatDoesNotNameEachPatchApartIsAUsageError() {
    val target = zip("app-1.2.zip", app(2))
    val first = "v1.0=${zip("app-1.0.zip", app(0))}"
    val second = "v1.1=${zip("app-1.1.zip", app(1))}"
    for (template in listOf("{version}.kiff", "same.kiff", "{to}.kiff", "{from}/{target}", "..")) {
      val status = kiff(
        "release", "--to", target, "--from", first, "--from", second, "--out", out.path,
        "--name", template
      )

      assertEquals(1, status, template)
      assertFalse(out.exists(), template)
    }
  }

  @Test
  fun aLabelIsTheTextBeforeTheFirstEqualsUnlessThatIsAPath() {
    assertLabeled("app.apk", "app.apk", "app.apk")
    assertLabeled("v1", "app.apk", "v1=app.apk")
    assertLabeled("v1", "a=b.apk", "v1=a=b.apk")
    assertLabeled("app.apk", "builds/v=2/app.apk", "builds/v=2/app.apk")
    assertLabeled("a=b.apk", "./a=b.apk", "./a=b.apk")
    // A Windows path, whatever its file name comes to on the platform reading it.
    assertEquals("builds\\v=2\\app.apk", Labeled.parse("builds\\v=2\\app.apk").path)
    assertFailsWith<IllegalArgumentException> { Labeled.parse("=app.apk") }
    assertFailsWith<IllegalArgumentException> { Labeled.parse("v1=") }
  }

  @Test
  fun labelsReachTheManifestAndTheNames() {
    val target = zip("app-1.2.zip", app(2))
    val unlabelled = File(dir, "builds/v=1/app-1.0.zip").also { it.parentFile.mkdirs() }
    zip(unlabelled, app(0))
    val equalsInName = zip("a=b.zip", app(1))

    val status = kiff(
      "release", "--to", target, "--from", unlabelled.path, "--from", "v1.1=$equalsInName",
      "--out", out.path
    )

    assertEquals(0, status)
    val file = manifest().files().single()
    assertEquals("app-1.2.zip", file.string("version"))
    val patches = file.patches()
    assertEquals(listOf("app-1.0.zip", "v1.1"), patches.map { it.string("from") })
    assertEquals(
      listOf("app-1.2.zip.from-app-1.0.zip.kiff", "app-1.2.zip.from-v1.1.kiff"),
      patches.map { it.string("name") }
    )
    assertEquals("a=b.zip", patches[1].getValue("source").jsonObject.string("name"))
  }

  @Test
  fun anEmptyOrRepeatedLabelIsAUsageError() {
    val target = zip("app-1.2.zip", app(2))
    val source = zip("app-1.1.zip", app(1))
    assertEquals(1, kiff("release", "--to", target, "--from", "=$source", "--out", out.path))
    val twice = arrayOf("--from", "v=$source", "--from", "v=$target")
    assertEquals(1, kiff("release", "--to", target, *twice, "--out", out.path))
    assertFalse(out.exists())
  }

  @Test
  fun whatAFileIsComesFromItsContentNotItsName() {
    val manifest = mapOf("AndroidManifest.xml" to text(50, seed = 9))
    assertDetects(PatcherId.APK, "app-2.zip", zipBytes(app(2) + manifest), zipBytes(app(1)))
    assertDetects(PatcherId.ZIP, "app-2.apk", zipBytes(app(2)), zipBytes(app(1)))
    assertDetects(PatcherId.ZIP, "bundle-2.jar", zipBytes(app(2)), zipBytes(app(1)))
    val data = Random(3).nextBytes(20_000)
    assertDetects(PatcherId.BINARY, "data-2.bin", data, data.copyOf().also { it[0] = 1 })
  }

  @Test
  fun aPatcherIsIgnoredWithAWarning() {
    val target = zip("app-1.2.zip", app(2))
    val source = zip("app-1.1.zip", app(1))
    for (option in listOf("--patcher", "-p")) {
      out.deleteRecursively()
      val run = capturingErr {
        kiff("release", "--to", target, "--from", source, "--out", out.path, option, "binary")
      }

      assertEquals(0, run.status, option)
      assertTrue("Warning: --patcher binary is ignored" in run.err, run.err)
      assertEquals("zip", manifest().files().single().patches().single().string("patcher"))
    }

    val created = File(dir, "created.kiff")
    val run = capturingErr { kiff("create", "-p", "apk", source, target, created.path) }
    assertEquals(0, run.status)
    assertTrue("Warning: --patcher apk is ignored" in run.err, run.err)
    assertEquals(PatcherId.ZIP, Kiff.info(created.path).patcher)

    val quiet = capturingErr { kiff("create", source, target, created.path) }
    assertEquals(0, quiet.status)
    assertEquals("", quiet.err)
  }

  @Test
  fun thePatcherIsNotOfferedInHelp() {
    for (command in listOf("create", "release")) {
      val help = ByteArrayOutputStream()
      val original = System.out
      System.setOut(PrintStream(help, true, Charsets.UTF_8))
      try {
        kiff(command, "--help")
      } finally {
        System.setOut(original)
      }
      val text = help.toString(Charsets.UTF_8)
      assertTrue("AndroidManifest.xml" in text, text)
      assertFalse("--patcher" in text, text)
    }
  }

  @Test
  fun noVerifyStillWritesARestorablePatch() {
    val target = zip("app-1.2.zip", app(2))
    val source = zip("app-1.1.zip", app(1))

    val status = kiff("release", "--to", target, "--from", source, "--out", out.path, "--no-verify")

    assertEquals(0, status)
    val restored = File(dir, "restored")
    Kiff.applyPatch(source, File(out, "app-1.2.zip.from-app-1.1.zip.kiff").path, restored.path)
    assertContentEquals(File(target).readBytes(), restored.readBytes())
  }

  @Test
  fun urlsEscapeWhatAPathSegmentCannotHold() {
    assertEquals("app-1.2.zip.from-v1.0.kiff", encodePathSegment("app-1.2.zip.from-v1.0.kiff"))
    assertEquals("a%20b%2Bc%3Fd%23%C3%A9~", encodePathSegment("a b+c?d#é~"))
  }

  private fun assertLabeled(label: String, path: String, text: String) {
    val labeled = Labeled.parse(text)
    assertEquals(label, labeled.label, text)
    assertEquals(path, labeled.path, text)
  }

  private fun assertDetects(
    expected: PatcherId,
    name: String,
    target: ByteArray,
    source: ByteArray
  ) {
    out.deleteRecursively()
    val targetPath = file(name, target)
    val sourcePath = file("old-$name", source)
    val status = kiff("release", "--to", targetPath, "--from", sourcePath, "--out", out.path)

    assertEquals(0, status, name)
    val patch = manifest().files().single().patches().single()
    assertEquals(expected.name.lowercase(), patch.string("patcher"), name)
    assertEquals(expected, Kiff.info(File(out, patch.string("name")).path).patcher, name)
  }

  /** The size and SHA-256 [entry] gives are [file]'s own. */
  private fun assertDescribes(file: File, entry: JsonObject) {
    assertEquals(file.length(), entry.getValue("size").jsonPrimitive.long, file.name)
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    assertEquals(HexFormat.of().formatHex(digest), entry.string("sha256"), file.name)
  }

  private fun kiff(vararg args: String): Int = runKiff(arrayOf(*args))

  private class Run(val status: Int, val err: String)

  /** Runs [block] with standard error collected, which is where a warning goes. */
  private fun capturingErr(block: () -> Int): Run {
    val err = ByteArrayOutputStream()
    val original = System.err
    System.setErr(PrintStream(err, true, Charsets.UTF_8))
    val status = try {
      block()
    } finally {
      System.setErr(original)
    }
    return Run(status, err.toString(Charsets.UTF_8))
  }

  private fun manifest(): JsonObject =
    Json.parseToJsonElement(File(out, ReleaseManifest.FILE_NAME).readText()).jsonObject

  private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

  private fun JsonObject.files(): List<JsonObject> =
    getValue("files").jsonArray.map { it.jsonObject }

  private fun JsonObject.patches(): List<JsonObject> =
    getValue("patches").jsonArray.map { it.jsonObject }

  private fun file(name: String, bytes: ByteArray): String =
    File(dir, name).apply { writeBytes(bytes) }.path

  private fun zip(name: String, entries: Map<String, ByteArray>): String =
    File(dir, name).also { zip(it, entries) }.path

  private fun zip(file: File, entries: Map<String, ByteArray>) = file.writeBytes(zipBytes(entries))

  private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      for ((name, content) in entries) {
        zip.putNextEntry(ZipEntry(name).apply { time = ENTRY_TIME })
        zip.write(content)
        zip.closeEntry()
      }
    }
    return bytes.toByteArray()
  }

  /** Release [version] of an archive that changes a little from one release to the next. */
  private fun app(version: Int): Map<String, ByteArray> = mapOf(
    "README" to text(200, seed = 1),
    "lib/core.txt" to text(400, seed = 2) + text(20 * (version + 1), seed = 100 + version),
    "assets/data.bin" to Random(3).nextBytes(8_000).also { it[version] = version.toByte() }
  )

  /** Lines drawn from a small vocabulary, which compress and differ the way source does. */
  private fun text(lines: Int, seed: Int): ByteArray {
    val random = Random(seed)
    return buildString {
      repeat(lines) { line ->
        repeat(1 + random.nextInt(8)) { append(WORDS[random.nextInt(WORDS.size)]).append(' ') }
        append(line).append('\n')
      }
    }.encodeToByteArray()
  }

  private companion object {
    const val ENTRY_TIME = 1_600_000_000_000L
    val WORDS = listOf("val", "fun", "return", "patch", "source", "target", "region", "if", "else")
  }
}
