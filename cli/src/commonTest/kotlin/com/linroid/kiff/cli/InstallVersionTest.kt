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
  fun prefersTheBuildForThisPlatform() {
    native("9.9.9", NATIVE)
    release("9.9.9", distribution("9.9.9"))
    val launcher = versions().install("9.9.9")

    assertEquals(home / "versions" / "9.9.9" / "bin" / LAUNCHER, launcher)
    assertContentEquals(NATIVE, readBytes(launcher))
    val name = "v9.9.9/kiff-9.9.9-$PLATFORM$EXE"
    assertEquals(listOf("$name.sha256", name), requests)
    assertEquals(listOf("Downloading kiff 9.9.9 from $BASE/$name"), logged)
    assertEquals(listOf("9.9.9"), installed())
  }

  @Test
  fun takesTheJvmBuildWhenTheReleaseHasNoneForThisPlatform() {
    release("9.9.9", distribution("9.9.9"))
    val launcher = versions().install("9.9.9")

    val zip = "v9.9.9/kiff-9.9.9.zip"
    assertEquals(listOf("v9.9.9/kiff-9.9.9-$PLATFORM$EXE.sha256", "$zip.sha256", zip), requests)
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
    native("9.9.9", NATIVE)
    val first = versions().install("9.9.9")
    val asked = requests.toList()

    assertEquals(first, versions().install("9.9.9"))
    assertEquals(asked, requests)
  }

  @Test
  fun takesTheCopyOfAnotherRunThatInstalledItFirst() {
    native("9.9.9", NATIVE)
    val theirs = home / "versions" / "9.9.9" / "bin"
    before = { path ->
      if (path.endsWith(EXE) && !path.endsWith(".sha256")) {
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
    native("9.9.9", NATIVE, sha256 = sha256("another build".encodeToByteArray()))
    val failure = assertFailsWith<UnavailableVersionException> { versions().install("9.9.9") }

    val url = "$BASE/v9.9.9/kiff-9.9.9-$PLATFORM$EXE"
    assertEquals(
      "$url has SHA-256 ${sha256(NATIVE)}, but $url.sha256 says " +
        sha256("another build".encodeToByteArray()),
      failure.message
    )
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
      "Could not download kiff 9.9.9: file:///releases/v9.9.9/kiff-9.9.9-$PLATFORM$EXE.sha256 " +
        "is not an http URL",
      failure.message
    )
    assertEquals(emptyList(), requests)
  }

  private fun versions(
    platform: String? = PLATFORM,
    fetcher: Fetcher = this.fetcher
  ) = KiffVersions(home, BASE, { logged += it }, platform, javaHome = "/jdk", fetcher = fetcher)

  private fun native(version: String, bytes: ByteArray, sha256: String = sha256(bytes)) {
    val name = "kiff-$version-$PLATFORM$EXE"
    assets["v$version/$name"] = bytes
    assets["v$version/$name.sha256"] = "$sha256  $name\n".encodeToByteArray()
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

  private fun installed(): List<String> =
    systemFileSystem.listOrNull(home / "versions")?.map { it.name }.orEmpty()

  private val Path.parent get() = this.parent!!

  private companion object {
    const val BASE = "https://mirror.test/releases"
    const val PLATFORM = "test-x64"
    val EXE = if (isWindows) ".exe" else ""
    val LAUNCHER = if (isWindows) "kiff.exe" else "kiff"
    val NATIVE = "a native kiff".encodeToByteArray()
  }
}
