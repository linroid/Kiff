package com.linroid.kiff.cli

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UseVersionTest {

  private val dir: Path = Files.createTempDirectory("kiff-use")
  private val home: Path = dir.resolve("home")

  /** The files each release attaches, by name, and every path the server was asked for. */
  private val assets = ConcurrentHashMap<String, ByteArray>()
  private val requests = CopyOnWriteArrayList<String>()

  /** Runs before the server answers with a zip, while the kiff asking for it has nothing yet. */
  @Volatile
  private var beforeZip: () -> Unit = {}

  // Like GitHub, a release's download address redirects to where the file is stored.
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/") { exchange ->
      try {
        val path = exchange.requestURI.path
        requests += path
        val name = path.substringAfterLast('/')
        val bytes = assets[name]
        when {
          bytes == null -> exchange.sendResponseHeaders(404, -1)
          path.startsWith("/releases/") -> {
            exchange.responseHeaders.add("Location", "/storage/$name")
            exchange.sendResponseHeaders(302, -1)
          }
          else -> {
            if (name.endsWith(".zip")) beforeZip()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
          }
        }
      } finally {
        exchange.close()
      }
    }
    start()
  }
  private val base = "http://127.0.0.1:${server.address.port}/releases"

  @AfterTest
  fun cleanUp() {
    server.stop(0)
    dir.toFile().deleteRecursively()
  }

  @Test
  fun handsTheArgumentsToThatVersionUnreadAndAnswersItsStatus() {
    if (WINDOWS) return
    release("9.9.9", distribution("9.9.9"))
    val run = kiffProcess("-v", "9.9.9", "create", "two words", "", "--no-such-flag", "-v", "1")

    assertEquals(3, run.status, run.err)
    assertEquals("[create]\n[two words]\n[]\n[--no-such-flag]\n[-v]\n[1]\n", run.out)
    assertTrue("Downloading kiff 9.9.9 from $base/v9.9.9/kiff-9.9.9.zip\n" in run.err, run.err)
    // The test runs it with no JAVA_HOME, which the launcher needs to find this Java.
    assertTrue("JAVA_HOME=${System.getProperty("java.home")}\n" in run.err, run.err)
    assertTrue(Files.isExecutable(home.resolve("versions/9.9.9/bin/kiff")))
  }

  @Test
  fun reusesAnInstalledVersionWithoutAskingTheServerAgain() {
    if (WINDOWS) return
    release("9.9.9", distribution("9.9.9"))
    assertEquals(3, kiffProcess("-v", "9.9.9").status)
    val first = requests.toList()
    assertEquals(
      listOf(
        "/releases/v9.9.9/kiff-9.9.9.zip.sha256",
        "/storage/kiff-9.9.9.zip.sha256",
        "/releases/v9.9.9/kiff-9.9.9.zip",
        "/storage/kiff-9.9.9.zip"
      ),
      first
    )

    val again = kiffProcess("--use", "9.9.9", "info", "x.kiff")
    assertEquals(3, again.status, again.err)
    assertEquals("[info]\n[x.kiff]\n", again.out)
    assertFalse("Downloading" in again.err, again.err)
    assertEquals(first, requests.toList())
  }

  @Test
  fun takesTheCopyOfAnotherRunThatInstalledItFirst() {
    release("9.9.9", distribution("9.9.9"))
    val theirs = home.resolve("versions/9.9.9/bin")
    beforeZip = {
      Files.createDirectories(theirs)
      for (name in listOf("kiff", "kiff.bat")) Files.writeString(theirs.resolve(name), "theirs")
    }
    val launcher = KiffVersions(home, base, PrintStream(ByteArrayOutputStream())).install("9.9.9")

    assertEquals(theirs, launcher.parent)
    assertEquals("theirs", Files.readString(launcher))
    assertEquals(listOf("9.9.9"), installed())
  }

  @Test
  fun refusesAZipThatDoesNotMatchItsChecksumAndKeepsNothing() {
    release("9.9.9", distribution("9.9.9"), sha256 = sha256("another zip".encodeToByteArray()))
    val run = kiff("-v", "9.9.9", "create", "a", "b", "c")

    assertEquals(1, run.status)
    val zip = "$base/v9.9.9/kiff-9.9.9.zip"
    assertTrue(run.err.startsWith("Downloading kiff 9.9.9 from $zip\n"), run.err)
    assertTrue("Error: $zip has SHA-256 " in run.err, run.err)
    assertTrue("but $zip.sha256 says ${sha256("another zip".encodeToByteArray())}" in run.err)
    assertEquals("", run.out)
    assertEquals(emptyList(), installed())
  }

  @Test
  fun namesTheAddressThatHasNoRelease() {
    val run = kiff("-v", "9.9.8", "create", "a", "b", "c")
    assertEquals(1, run.status)
    assertEquals(
      "Error: No release of kiff 9.9.8 at $base/v9.9.8/kiff-9.9.8.zip.sha256 (HTTP 404)\n",
      run.err
    )
    // diff keeps 1 for "the files differ", so its trouble is 2 here too.
    assertEquals(2, kiff("-v", "9.9.8", "diff", "a", "b").status)
    assertFalse(Files.exists(home))
  }

  @Test
  fun namesTheAddressItCouldNotReach() {
    val closed = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val unreachable = "http://127.0.0.1:$closed"
    val run = kiff("-v", "9.9.9", "create", "a", "b", "c", baseUrl = unreachable)
    assertEquals(1, run.status)
    val url = "$unreachable/v9.9.9/kiff-9.9.9.zip.sha256"
    // The reason after it is the platform's, such as "Connection refused".
    assertTrue(run.err.startsWith("Error: Could not download kiff 9.9.9 from $url: "), run.err)
    assertFalse(Files.exists(home))
  }

  @Test
  fun refusesAnEntryThatWouldUnpackOutsideItsDirectory() {
    release("9.9.9", distribution("9.9.9", "kiff-9.9.9/../../escaped"))
    val run = kiff("-v", "9.9.9", "create", "a", "b", "c")

    assertEquals(1, run.status)
    assertTrue(
      "Error: $base/v9.9.9/kiff-9.9.9.zip holds kiff-9.9.9/../../escaped, which would unpack " +
        "outside its directory\n" in run.err,
      run.err
    )
    assertEquals(emptyList(), installed())
    assertFalse(Files.walk(dir).use { files -> files.anyMatch { it.endsWith("escaped") } })
  }

  @Test
  fun runsTheCommandItselfWhenItIsThisVersion() {
    val a = Files.writeString(dir.resolve("a"), "x\n").toString()
    val b = Files.writeString(dir.resolve("b"), "y\n").toString()
    val run = kiff("-v", kiffVersion, "diff", a, b)

    assertEquals(1, run.status, run.err)
    assertTrue(run.out.startsWith("--- $a\n+++ $b\n"), run.out)
    assertEquals("", run.err)
    assertEquals(emptyList(), requests.toList())
  }

  @Test
  fun refusesASnapshotOrAnythingElseNoReleaseHas() {
    val refusals = listOf(
      "9.9.9-SNAPSHOT" to "9.9.9-SNAPSHOT is a snapshot, and no snapshot has a release to download",
      "v9.9.9" to "v9.9.9 is a tag: -v takes its version, 9.9.9",
      "latest" to "\"latest\" is not a version",
      "../9.9.9" to "\"../9.9.9\" is not a version",
      "" to "\"\" is not a version"
    )
    for ((version, reason) in refusals) {
      val run = kiff("--use=$version", "create", "a", "b", "c")
      assertEquals(1, run.status, version)
      assertTrue(run.err.startsWith("Error: $reason"), run.err)
    }
    assertEquals(emptyList(), requests.toList())
    assertFalse(Files.exists(home))
  }

  @Test
  fun readsTheVersionInEveryFormCliktWould() {
    val rest = listOf("create", "-v", "a")
    for (form in listOf(listOf("-v", "1.2.3"), listOf("--use", "1.2.3"), listOf("--use=1.2.3"))) {
      assertEquals("1.2.3" to rest, leadingVersion((form + rest).toTypedArray()), "$form")
    }
    assertEquals("1.2.3" to rest, leadingVersion(arrayOf("-v1.2.3") + rest))
    // After the command a -v is the command's, and without a value Clikt says what is missing.
    assertNull(leadingVersion(arrayOf("create", "-v", "1.2.3")))
    assertNull(leadingVersion(arrayOf("--use")))
    assertNull(leadingVersion(arrayOf("--version")))
    assertNull(leadingVersion(emptyArray()))
  }

  @Test
  fun printsItsOwnVersionAndOffersTheOptionInItsHelp() {
    val version = kiffProcess("--version")
    assertEquals(0, version.status, version.err)
    assertEquals("kiff $kiffVersion", version.out.trim())

    val help = kiffProcess("--help")
    assertEquals(0, help.status, help.err)
    assertTrue("-v, --use=<version>  Run the command with Kiff <version>" in help.out, help.out)
  }

  private class Run(val status: Int, val out: String, val err: String)

  /** Runs kiff in this JVM, with its versions kept in [home] and fetched from [baseUrl]. */
  private fun kiff(vararg args: String, baseUrl: String = base): Run {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val versions = KiffVersions(home, baseUrl, PrintStream(err, true, Charsets.UTF_8))
    val status = runKiff(arrayOf(*args), out, versions)
    return Run(status, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
  }

  /**
   * Runs kiff's `main` in a JVM of its own: the one way to give it an environment, and to see what
   * the version it hands over to prints through the output it inherits.
   */
  private fun kiffProcess(vararg args: String): Run {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val classpath = System.getProperty("java.class.path")
    val command = ProcessBuilder(listOf(java, "-cp", classpath, MAIN) + args)
    command.environment().apply {
      remove("JAVA_HOME")
      put("KIFF_HOME", home.toString())
      put("KIFF_DOWNLOAD_URL", base)
    }
    val out = dir.resolve("stdout")
    val err = dir.resolve("stderr")
    val process = command.redirectOutput(out.toFile()).redirectError(err.toFile()).start()
    assertTrue(process.waitFor(2, TimeUnit.MINUTES), "kiff ${args.joinToString(" ")} hung")
    return Run(process.exitValue(), Files.readString(out), Files.readString(err))
  }

  private fun release(version: String, zip: ByteArray, sha256: String = sha256(zip)) {
    assets["kiff-$version.zip"] = zip
    // The line sha256sum writes, which is what the release workflow attaches.
    assets["kiff-$version.zip.sha256"] = "$sha256  kiff-$version.zip\n".encodeToByteArray()
  }

  /** What `:cli:distZip` makes, with a launcher that echoes its arguments and exits 3. */
  private fun distribution(version: String, vararg extra: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      fun put(name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.encodeToByteArray())
        zip.closeEntry()
      }
      zip.putNextEntry(ZipEntry("kiff-$version/"))
      zip.closeEntry()
      for (name in extra) put(name, "outside")
      put("kiff-$version/bin/kiff", LAUNCHER)
      put("kiff-$version/bin/kiff.bat", "@exit /b 3\r\n")
      put("kiff-$version/lib/kiff-cli-$version.jar", "")
    }
    return bytes.toByteArray()
  }

  private fun installed(): List<String> {
    val versions = home.resolve("versions")
    if (!Files.exists(versions)) return emptyList()
    return Files.list(versions).use { files -> files.map { it.fileName.toString() }.toList() }
  }

  private fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private companion object {
    const val MAIN = "com.linroid.kiff.cli.MainKt"
    val WINDOWS = System.getProperty("os.name").startsWith("Windows")
    val LAUNCHER = """
      #!/bin/sh
      for arg in "${'$'}@"; do printf '[%s]\n' "${'$'}arg"; done
      echo "JAVA_HOME=${'$'}JAVA_HOME" >&2
      exit 3
    """.trimIndent() + "\n"
  }
}
