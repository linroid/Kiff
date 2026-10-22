package com.linroid.kiff.cli

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toOkioPath

/**
 * `kiff --use` against a web server: downloads through curl, with GitHub's redirect, and the
 * release run in a process of its own. The install itself is tested on every platform by
 * [InstallVersionTest].
 */
class DownloadVersionTest {

  private val dir: Path = Files.createTempDirectory("kiff-use")
  private val home: Path = dir.resolve("home")

  /** The files each release attaches, by name, and every path the server was asked for. */
  private val assets = ConcurrentHashMap<String, ByteArray>()
  private val requests = CopyOnWriteArrayList<String>()

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
    val run = kiffProcess(
      "--use", "9.9.9",
      "create", "two words", "", "--no-such-flag", "--use=1", "-v"
    )

    assertEquals(3, run.status, run.err)
    assertEquals("[create]\n[two words]\n[]\n[--no-such-flag]\n[--use=1]\n[-v]\n", run.out)
    assertTrue("Downloading kiff 9.9.9 from $base/v9.9.9/kiff-9.9.9.zip\n" in run.err, run.err)
    // The test runs it with no JAVA_HOME, which the launcher needs to find this Java.
    assertTrue("JAVA_HOME=${System.getProperty("java.home")}\n" in run.err, run.err)
    assertTrue(Files.isExecutable(home.resolve("versions/9.9.9/bin/kiff")))
  }

  @Test
  fun runsTheBuildForThisPlatformWhenTheReleaseHasOne() {
    val platform = nativePlatform
    if (WINDOWS || platform == null) return
    native("9.9.9", platform, LAUNCHER.replace("exit 3", "exit 5").encodeToByteArray())
    release("9.9.9", distribution("9.9.9"))
    val run = kiffProcess("--use", "9.9.9", "info", "x.kiff")

    assertEquals(5, run.status, run.err)
    assertEquals("[info]\n[x.kiff]\n", run.out)
    val name = "kiff-9.9.9-$platform"
    assertEquals(
      listOf(
        "/releases/v9.9.9/$name.sha256",
        "/storage/$name.sha256",
        "/releases/v9.9.9/$name",
        "/storage/$name"
      ),
      requests.toList()
    )
    assertTrue(Files.isExecutable(home.resolve("versions/9.9.9/bin/kiff")))
  }

  @Test
  fun reusesAnInstalledVersionWithoutAskingTheServerAgain() {
    if (WINDOWS) return
    release("9.9.9", distribution("9.9.9"))
    assertEquals(3, kiffProcess("--use=9.9.9").status)
    val first = requests.toList()
    // A release without a build for this machine is found out before its zip is fetched.
    val native = nativePlatform?.let { listOf("/releases/v9.9.9/kiff-9.9.9-$it.sha256") }
    assertEquals(
      native.orEmpty() + listOf(
        "/releases/v9.9.9/kiff-9.9.9.zip.sha256",
        "/storage/kiff-9.9.9.zip.sha256",
        "/releases/v9.9.9/kiff-9.9.9.zip",
        "/storage/kiff-9.9.9.zip"
      ),
      first
    )

    // Either spelling hands over to the copy now installed.
    for (use in listOf(arrayOf("--use", "9.9.9"), arrayOf("--use=9.9.9"))) {
      val again = kiffProcess(*use, "info", "x.kiff")
      assertEquals(3, again.status, again.err)
      assertEquals("[info]\n[x.kiff]\n", again.out, use.joinToString(" "))
      assertFalse("Downloading" in again.err, again.err)
    }
    assertEquals(first, requests.toList())
  }

  @Test
  fun refusesAZipThatDoesNotMatchItsChecksumAndKeepsNothing() {
    release("9.9.9", distribution("9.9.9"), sha256 = sha256("another zip".encodeToByteArray()))
    val run = kiff("--use", "9.9.9", "create", "a", "b", "c")

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
    val run = kiff("--use", "9.9.8", "create", "a", "b", "c")
    assertEquals(1, run.status)
    assertEquals(
      "Error: No release of kiff 9.9.8 at $base/v9.9.8/kiff-9.9.8.zip.sha256 (HTTP 404)\n",
      run.err
    )
    // diff keeps 1 for "the files differ", so its trouble is 2 here too.
    assertEquals(2, kiff("--use", "9.9.8", "diff", "a", "b").status)
    assertFalse(Files.exists(home))
  }

  @Test
  fun namesTheAddressItCouldNotReach() {
    val closed = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val unreachable = "http://127.0.0.1:$closed"
    val run = kiff("--use", "9.9.9", "create", "a", "b", "c", baseUrl = unreachable)
    assertEquals(1, run.status)
    val url = "$unreachable/v9.9.9/kiff-9.9.9.zip.sha256"
    // The reason after it is curl's, such as "Failed to connect to 127.0.0.1 port ...".
    assertTrue(run.err.startsWith("Error: Could not download kiff 9.9.9 from $url: "), run.err)
    assertFalse("curl: (" in run.err, run.err)
    assertFalse(Files.exists(home))
  }

  @Test
  fun asksForTheReleaseOfItsOwnNumbersWhenItIsASnapshot() {
    // A build without -Pversion is 0.0.0-SNAPSHOT, which no release is, not even 0.0.0.
    val release = kiffVersion.removeSuffix("-SNAPSHOT")
    if (release == kiffVersion) return
    val run = kiff("--use", release, "create", "a", "b", "c")

    assertEquals(1, run.status, run.err)
    assertEquals(listOf("/releases/v$release/kiff-$release.zip.sha256"), requests.toList())
  }

  /** Runs kiff in this JVM, taking its JVM build from [baseUrl] through curl. */
  private fun kiff(vararg args: String, baseUrl: String = base): Run = capture(*args) { log ->
    KiffVersions(home.toOkioPath(), baseUrl, log, platform = null, fetcher = Curl)
  }

  private class Process(val status: Int, val out: String, val err: String)

  /**
   * Runs kiff's `main` in a JVM of its own: the one way to give it an environment, and to see what
   * the version it hands over to prints through the output it inherits.
   */
  private fun kiffProcess(vararg args: String): Process {
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
    return Process(process.exitValue(), Files.readString(out), Files.readString(err))
  }

  private fun native(version: String, platform: String, bytes: ByteArray) {
    val name = "kiff-$version-$platform"
    assets[name] = bytes
    assets["$name.sha256"] = "${sha256(bytes)}  $name\n".encodeToByteArray()
  }

  private fun release(version: String, zip: ByteArray, sha256: String = sha256(zip)) {
    assets["kiff-$version.zip"] = zip
    // The line sha256sum writes, which is what the release workflow attaches.
    assets["kiff-$version.zip.sha256"] = "$sha256  kiff-$version.zip\n".encodeToByteArray()
  }

  /** What `:cli:distZip` makes, deflated, with a launcher that echoes its arguments and exits 3. */
  private fun distribution(version: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      fun put(name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.encodeToByteArray())
        zip.closeEntry()
      }
      zip.putNextEntry(ZipEntry("kiff-$version/"))
      zip.closeEntry()
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
