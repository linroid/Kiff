package com.linroid.kiff.cli

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path

/**
 * How `kiff --use` installs a release, from a mirror held in memory: which file it takes, how it is
 * checked, and what is left when it is refused. The download itself is curl's, tested on the JVM.
 */
class InstallVersionTest {

  private val dir = TestDirectory()
  private val home = dir.resolve("home")

  /** The files each release attaches, by their path under [BASE], and every path asked for. */
  private val assets = mutableMapOf<String, ByteArray>()
  private val requests = mutableListOf<String>()
  private val logged = mutableListOf<String>()

  /** Runs as the fetch of [path] begins, while the kiff asking for it has nothing yet. */
  private var before: (path: String) -> Unit = {}

  private val fetcher = Fetcher { url, file ->
    val path = url.removePrefix("$BASE/")
    requests += path
    before(path)
    val bytes = assets[path]
    if (bytes == null) {
      Fetched(404)
    } else {
      systemFileSystem.write(file) { write(bytes) }
      Fetched(200)
    }
  }

  @AfterTest
  fun cleanUp() {
    dir.delete()
  }

  @Test
  fun prefersTheArchiveForThisPlatform() {
    archive("9.9.9", NATIVE)
    bare("9.9.9", "a bare kiff".encodeToByteArray())
    release("9.9.9", distribution("9.9.9"))
    val launcher = versions().install("9.9.9")

    val installed = home / "versions" / "9.9.9"
    assertEquals(installed / "bin" / LAUNCHER, launcher)
    assertContentEquals(NATIVE, readBytes(launcher))
    assertEquals("the licence", readBytes(installed / "LICENSE").decodeToString())
    assertEquals("the readme", readBytes(installed / "README.md").decodeToString())
    assertEquals(listOf("LICENSE", "README.md", "bin"), list(installed))
    val name = "v9.9.9/kiff-9.9.9-$PLATFORM.tar.gz"
    assertEquals(listOf("$name.sha256", name), requests)
    assertEquals(listOf("Downloading kiff 9.9.9 from $BASE/$name"), logged)
    assertEquals(listOf("9.9.9"), installed())
  }

  @Test
  fun takesTheBareExecutableOfAReleaseFromBeforeArchives() {
    bare("9.9.9", NATIVE)
    release("9.9.9", distribution("9.9.9"))
    val launcher = versions().install("9.9.9")

    assertEquals(home / "versions" / "9.9.9" / "bin" / LAUNCHER, launcher)
    assertContentEquals(NATIVE, readBytes(launcher))
    val name = "v9.9.9/kiff-9.9.9-$PLATFORM"
    assertEquals(listOf("$name.tar.gz.sha256", "$name.sha256", name), requests)
    assertEquals(listOf("Downloading kiff 9.9.9 from $BASE/$name"), logged)
  }

  @Test
  fun unpacksTheZipOfAWindowsBuild() {
    val name = "kiff-9.9.9-windows-x64"
    zipArchive("9.9.9", "windows-x64", "$name/" to null, "$name/kiff.exe" to NATIVE)
    val launcher = versions(platform = "windows-x64").install("9.9.9")

    // Installed under the name this machine runs, which is kiff.exe on Windows.
    assertEquals(home / "versions" / "9.9.9" / "bin" / LAUNCHER, launcher)
    assertContentEquals(NATIVE, readBytes(launcher))
    assertEquals(listOf("v9.9.9/$name.zip.sha256", "v9.9.9/$name.zip"), requests)
  }

  @Test
  fun takesTheJvmBuildWhenTheReleaseHasNoneForThisPlatform() {
    release("9.9.9", distribution("9.9.9"))
    val launcher = versions().install("9.9.9")

    val name = "v9.9.9/kiff-9.9.9-$PLATFORM"
    val zip = "v9.9.9/kiff-9.9.9.zip"
    assertEquals(
      listOf("$name.tar.gz.sha256", "$name.sha256", "$zip.sha256", zip),
      requests
    )
    assertEquals(if (isWindows) "kiff.bat" else "kiff", launcher.name)
    assertEquals(home / "versions" / "9.9.9" / "bin", launcher.parent)
    assertTrue(exists(home / "versions" / "9.9.9" / "lib" / "kiff-cli.jar"))
  }

  @Test
  fun takesTheJvmBuildOnAPlatformNoReleaseIsBuiltFor() {
    release("9.9.9", distribution("9.9.9"))
    versions(platform = null).install("9.9.9")

    assertEquals(listOf("v9.9.9/kiff-9.9.9.zip.sha256", "v9.9.9/kiff-9.9.9.zip"), requests)
  }

  @Test
  fun reusesAnInstalledVersionWithoutFetchingAgain() {
    archive("9.9.9", NATIVE)
    val first = versions().install("9.9.9")
    val asked = requests.toList()

    assertEquals(first, versions().install("9.9.9"))
    assertEquals(asked, requests)
  }

  @Test
  fun takesTheCopyOfAnotherRunThatInstalledItFirst() {
    archive("9.9.9", NATIVE)
    val theirs = home / "versions" / "9.9.9" / "bin"
    before = { path ->
      if (path.endsWith(".tar.gz")) {
        systemFileSystem.createDirectories(theirs)
        systemFileSystem.write(theirs / LAUNCHER) { writeUtf8("theirs") }
      }
    }
    val launcher = versions().install("9.9.9")

    assertEquals(theirs / LAUNCHER, launcher)
    assertEquals("theirs", readBytes(launcher).decodeToString())
    assertEquals(listOf("9.9.9"), installed())
  }

  @Test
  fun refusesABuildThatDoesNotMatchItsChecksumAndKeepsNothing() {
    val other = sha256("another build".encodeToByteArray())
    val name = "kiff-9.9.9-$PLATFORM"
    for ((asset, add) in listOf<Pair<String, () -> Unit>>(
      "$name.tar.gz" to { archive("9.9.9", NATIVE, sha256 = other) },
      name to { bare("9.9.9", NATIVE, sha256 = other) }
    )) {
      assets.clear()
      add()
      val failure = assertFailsWith<UnavailableVersionException> { versions().install("9.9.9") }

      val url = "$BASE/v9.9.9/$asset"
      val actual = sha256(assets.getValue("v9.9.9/$asset"))
      assertEquals("$url has SHA-256 $actual, but $url.sha256 says $other", failure.message)
      assertEquals(emptyList(), installed())
    }
  }

  @Test
  fun refusesAnArchiveThatHoldsAnythingOutsideItsDirectory() {
    val name = "kiff-9.9.9-$PLATFORM"
    for (outside in listOf("$name/../../escaped", "escaped", "/tmp/kiff-test-escaped")) {
      assets.clear()
      archive("9.9.9", NATIVE, TarEntry(outside, "outside".encodeToByteArray()))
      val failure = assertFailsWith<UnavailableVersionException> { versions().install("9.9.9") }

      assertEquals(
        "$BASE/v9.9.9/$name.tar.gz holds $outside, which is outside its $name directory",
        failure.message
      )
      assertEquals(emptyList(), installed())
      assertFalse(systemFileSystem.listRecursively(dir.path).any { "escaped" in it.name })
    }

    // A Windows build's zip, which this process unpacks, is held to the same.
    val windows = "kiff-9.9.9-windows-x64"
    assets.clear()
    zipArchive(
      "9.9.9", "windows-x64",
      "$windows/kiff.exe" to NATIVE, "$windows/../../escaped" to "outside".encodeToByteArray()
    )
    val failure = assertFailsWith<UnavailableVersionException> {
      versions(platform = "windows-x64").install("9.9.9")
    }
    assertEquals(
      "$BASE/v9.9.9/$windows.zip holds $windows/../../escaped, which is outside its $windows " +
        "directory",
      failure.message
    )
    assertEquals(emptyList(), installed())
    assertFalse(systemFileSystem.listRecursively(dir.path).any { "escaped" in it.name })
  }

  @Test
  fun refusesAnArchiveWithoutItsExecutable() {
    val name = "kiff-9.9.9-$PLATFORM"
    val url = "$BASE/v9.9.9/$name.tar.gz"
    val licence = TarEntry("$name/LICENSE", "the licence".encodeToByteArray())
    // An empty one is none either.
    val empty = TarEntry("$name/kiff", ByteArray(0), mode = 0b111_101_101)
    for (executable in listOf(emptyList(), listOf(empty))) {
      assets.clear()
      put("v9.9.9/$name.tar.gz", tarGzBytes(listOf(TarEntry("$name/", null), licence) + executable))
      val failure = assertFailsWith<UnavailableVersionException> { versions().install("9.9.9") }

      assertEquals("$url holds no $name/kiff", failure.message)
      assertEquals(emptyList(), installed())
    }
  }

  @Test
  fun refusesAnArchiveThatHoldsALink() {
    val name = "kiff-9.9.9-$PLATFORM"
    val url = "$BASE/v9.9.9/$name.tar.gz"
    // As the executable or as any other file, since once Windows's tar has unpacked a link, okio
    // reads it there as a file.
    for (link in listOf("$name/kiff", "$name/README.md")) {
      assets.clear()
      val files = listOf(
        TarEntry("$name/kiff", NATIVE, mode = 0b111_101_101),
        TarEntry("$name/README.md", "the readme".encodeToByteArray())
      ).filter { it.name != link }
      val linked = TarEntry(link, null, link = "/bin/sh")
      put("v9.9.9/$name.tar.gz", tarGzBytes(listOf(TarEntry("$name/", null)) + files + linked))
      val failure = assertFailsWith<UnavailableVersionException> { versions().install("9.9.9") }

      // What comes between is the entry as this tar lists it, in a layout of its own.
      val message = failure.message!!
      assertTrue(message.startsWith("$url holds "), message)
      assertTrue(message.endsWith(", which is neither a file nor a directory"), message)
      assertTrue(link in message, message)
      assertEquals(emptyList(), installed())
    }
  }

  @Test
  fun neverAnswersAnInstalledVersionWithoutItsLauncher() {
    val installed = home / "versions" / "9.9.9"
    val launcher = installed / "bin" / LAUNCHER
    systemFileSystem.createDirectories(installed / "bin")
    val refusal = "$installed holds no bin/$LAUNCHER: delete it to download kiff 9.9.9 again"

    // Empty, as a copy that stopped short leaves it.
    systemFileSystem.write(launcher) {}
    var failure = assertFailsWith<UnavailableVersionException> { versions().install("9.9.9") }
    assertEquals(refusal, failure.message)

    // A link to nothing, which okio reads as a file on Windows.
    systemFileSystem.delete(launcher)
    linkToNothing(launcher)
    failure = assertFailsWith<UnavailableVersionException> { versions().install("9.9.9") }
    assertEquals(refusal, failure.message)
    assertEquals(emptyList(), requests)
  }

  @Test
  fun saysWhyAnArchiveCouldNotBeUnpacked() {
    val name = "kiff-9.9.9-$PLATFORM.tar.gz"
    val bytes = "not a tarball".encodeToByteArray()
    assets["v9.9.9/$name"] = bytes
    assets["v9.9.9/$name.sha256"] = "${sha256(bytes)}  $name\n".encodeToByteArray()
    val failure = assertFailsWith<UnavailableVersionException> { versions().install("9.9.9") }

    // What follows is tar's own complaint, which differs between tars.
    val message = failure.message!!
    assertTrue(message.startsWith("Could not unpack $BASE/v9.9.9/$name: "), message)
    assertTrue(message.length > "Could not unpack $BASE/v9.9.9/$name: ".length, message)
    assertEquals(emptyList(), installed())
  }

  @Test
  fun refusesAChecksumFileThatIsNotOne() {
    assets["v9.9.9/kiff-9.9.9.zip.sha256"] = "<html>Not found</html>".encodeToByteArray()
    val failure = assertFailsWith<UnavailableVersionException> {
      versions(platform = null).install("9.9.9")
    }
    assertEquals(
      "$BASE/v9.9.9/kiff-9.9.9.zip.sha256 is not a SHA-256 checksum of kiff-9.9.9.zip",
      failure.message
    )
  }

  @Test
  fun refusesAZipThatHoldsAnythingOutsideItsDirectory() {
    release("9.9.9", distribution("9.9.9", "kiff-9.9.9/../../escaped"))
    val failure = assertFailsWith<UnavailableVersionException> {
      versions(platform = null).install("9.9.9")
    }

    assertEquals(
      "$BASE/v9.9.9/kiff-9.9.9.zip holds kiff-9.9.9/../../escaped, which is outside its " +
        "kiff-9.9.9 directory",
      failure.message
    )
    assertEquals(emptyList(), installed())
    assertFalse(systemFileSystem.listRecursively(dir.path).any { it.name == "escaped" })
  }

  @Test
  fun namesTheAddressThatHasNoRelease() {
    val failure = assertFailsWith<UnavailableVersionException> {
      versions(platform = null).install("9.9.8")
    }
    assertEquals(
      "No release of kiff 9.9.8 at $BASE/v9.9.8/kiff-9.9.8.zip.sha256 (HTTP 404)",
      failure.message
    )
    assertFalse(exists(home))
  }

  @Test
  fun saysWhyADownloadFailed() {
    val url = "$BASE/v9.9.9/kiff-9.9.9.zip.sha256"
    val answers = listOf(
      Fetched(0, "Failed to connect to mirror.test port 443") to
        "Could not download kiff 9.9.9 from $url: Failed to connect to mirror.test port 443",
      Fetched(503) to "Could not download kiff 9.9.9 from $url: HTTP 503"
    )
    for ((fetched, message) in answers) {
      val versions = versions(platform = null, fetcher = { _, _ -> fetched })
      val failure = assertFailsWith<UnavailableVersionException> { versions.install("9.9.9") }
      assertEquals(message, failure.message)
    }
    val noCurl = versions(platform = null) { _, _ -> throw ProgramNotFoundException("curl") }
    val failure = assertFailsWith<UnavailableVersionException> { noCurl.install("9.9.9") }
    assertTrue("curl, which is not installed. Install curl, or download $url" in failure.message!!)
    assertFalse(exists(home))
  }

  @Test
  fun refusesAnAddressThatIsNotTheWeb() {
    val versions =
      KiffVersions(home, "file:///releases", { logged += it }, PLATFORM, "/jdk", fetcher)
    val failure = assertFailsWith<UnavailableVersionException> { versions.install("9.9.9") }
    assertEquals(
      "Could not download kiff 9.9.9: file:///releases/v9.9.9/kiff-9.9.9-$PLATFORM.tar.gz.sha256 " +
        "is not an http URL",
      failure.message
    )
    assertEquals(emptyList(), requests)
  }

  private fun versions(
    platform: String? = PLATFORM,
    fetcher: Fetcher = this.fetcher
  ) = KiffVersions(home, BASE, { logged += it }, platform, javaHome = "/jdk", fetcher = fetcher)

  /**
   * The archive a release attaches for [PLATFORM]: [executable] as kiff, with LICENSE and
   * README.md, in the directory the archive is named for, and [extra].
   */
  private fun archive(
    version: String,
    executable: ByteArray,
    vararg extra: TarEntry,
    sha256: String? = null
  ) {
    val name = "kiff-$version-$PLATFORM"
    val bytes = tarGzBytes(
      listOf(
        TarEntry("$name/", null),
        TarEntry("$name/kiff", executable, mode = 0b111_101_101),
        TarEntry("$name/LICENSE", "the licence".encodeToByteArray()),
        TarEntry("$name/README.md", "the readme".encodeToByteArray())
      ) + extra
    )
    put("v$version/$name.tar.gz", bytes, sha256)
  }

  /** The zip a release attaches for Windows, of [entries], a directory where one is null. */
  private fun zipArchive(
    version: String,
    platform: String,
    vararg entries: Pair<String, ByteArray?>
  ) {
    val bytes = zipBytes(entries.associate { (name, data) -> name to (data ?: ByteArray(0)) })
    put("v$version/kiff-$version-$platform.zip", bytes, null)
  }

  /** The executable alone, as 0.2.0 attached it. */
  private fun bare(version: String, bytes: ByteArray, sha256: String? = null) {
    put("v$version/kiff-$version-$PLATFORM", bytes, sha256)
  }

  /** Serves [bytes] at [path], with the line sha256sum writes, of [sha256] if given, beside it. */
  private fun put(path: String, bytes: ByteArray, sha256: String? = null) {
    assets[path] = bytes
    assets["$path.sha256"] =
      "${sha256 ?: sha256(bytes)}  ${path.substringAfterLast('/')}\n".encodeToByteArray()
  }

  private fun release(version: String, zip: ByteArray, sha256: String = sha256(zip)) {
    assets["v$version/kiff-$version.zip"] = zip
    // The line sha256sum writes, which is what the release workflow attaches.
    assets["v$version/kiff-$version.zip.sha256"] =
      "$sha256  kiff-$version.zip\n".encodeToByteArray()
  }

  /** What `:cli:distZip` makes, its launchers and a jar under one directory, and [extra]. */
  private fun distribution(version: String, vararg extra: String): ByteArray = zipBytes(
    linkedMapOf(
      "kiff-$version/" to ByteArray(0),
      "kiff-$version/bin/kiff" to "#!/bin/sh\nexit 3\n".encodeToByteArray(),
      "kiff-$version/bin/kiff.bat" to "@exit /b 3\r\n".encodeToByteArray(),
      "kiff-$version/lib/kiff-cli.jar" to ByteArray(0)
    ) + extra.associateWith { "outside".encodeToByteArray() }
  )

  /** Makes [link] a link to a file that is not there: with mklink on Windows, where okio cannot. */
  private fun linkToNothing(link: Path) {
    val target = dir.resolve("nothing")
    if (!isWindows) {
      systemFileSystem.createSymlink(link, target)
      return
    }
    val errors = dir.resolve("mklink.errors")
    val status = runCommand(
      listOf("cmd.exe", "/d", "/c", "mklink", link.toString(), target.toString()),
      stdout = dir.resolve("mklink.out"),
      stderr = errors
    )
    assertEquals(0, status, readBytes(errors).decodeToString())
  }

  private fun installed(): List<String> = list(home / "versions")

  private fun list(dir: Path): List<String> =
    systemFileSystem.listOrNull(dir)?.map { it.name }?.sorted().orEmpty()

  private val Path.parent get() = this.parent!!

  private companion object {
    const val BASE = "https://mirror.test/releases"
    const val PLATFORM = "test-x64"
    val LAUNCHER = if (isWindows) "kiff.exe" else "kiff"
    val NATIVE = "a native kiff".encodeToByteArray()
  }
}
