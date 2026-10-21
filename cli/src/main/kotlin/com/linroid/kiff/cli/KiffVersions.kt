package com.linroid.kiff.cli

import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.zip.ZipFile

/** Why `kiff +VERSION` could not hand a command to the version it names. */
internal class UnavailableVersionException(message: String) : Exception(message)

/**
 * The version a leading `+X`, `--use X` or `--use=X` names, and the arguments after it, which are
 * left for that version to read. Like `cargo +nightly`, only the first argument counts: anywhere
 * else a `+` is an ordinary argument.
 */
internal fun leadingVersion(args: Array<String>): Pair<String, List<String>>? {
  val first = args.firstOrNull() ?: return null
  return when {
    first.startsWith("+") -> first.substring(1) to args.drop(1)
    // Without a value this is left to Clikt, which says the option needs one.
    first == "--use" -> if (args.size > 1) args[1] to args.drop(2) else null
    first.startsWith("--use=") -> first.removePrefix("--use=") to args.drop(1)
    else -> null
  }
}

/** Refuses a [version] that no release can have, saying why. */
internal fun checkReleased(version: String) {
  val problem = when {
    version.endsWith("-SNAPSHOT", ignoreCase = true) ->
      "$version is a snapshot, and no snapshot has a release to download"
    version.startsWith("v") && RELEASED.matches(version.substring(1)) ->
      "$version is a tag, and its version is ${version.substring(1)}"
    !RELEASED.matches(version) ->
      "\"$version\" is not a released Kiff version, such as 1.2.3"
    else -> return
  }
  throw UnavailableVersionException(problem)
}

/** A release's version: three numbers and an optional suffix, which also keeps it one path name. */
private val RELEASED = Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z][0-9A-Za-z.-]*)?""")

/**
 * The released Kiff builds that `kiff +VERSION` hands a command to. Each is downloaded once, from
 * the zip its GitHub release attaches, checked against the `.sha256` beside it, and kept in
 * [home]`/versions/<version>`.
 */
internal class KiffVersions(
  private val home: Path,
  private val baseUrl: String,
  /** Where progress and failures go, so standard output stays the command's own. */
  val log: PrintStream
) {

  private val client by lazy {
    // A release download answers with a redirect to wherever GitHub stores the file.
    HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(30))
      .build()
  }

  /** Runs [args] with Kiff [version], installed first if need be, and answers its exit status. */
  fun run(version: String, args: List<String>): Int {
    val launcher = install(version)
    val command = ProcessBuilder(listOf(launcher.toString()) + args).inheritIO()
    // The launcher tries JAVA_HOME before java on the PATH, and only this Java is sure to exist.
    if (command.environment()["JAVA_HOME"].isNullOrBlank()) {
      command.environment()["JAVA_HOME"] = System.getProperty("java.home")
    }
    val process = try {
      command.start()
    } catch (e: IOException) {
      throw UnavailableVersionException("Could not run $launcher: ${reason(e)}")
    }
    return process.waitFor()
  }

  /** Answers the launcher of Kiff [version], downloading it unless it is already installed. */
  fun install(version: String): Path {
    val versions = home.resolve("versions")
    val dir = versions.resolve(version)
    // Only a complete install is ever moved into place, so one that is there needs no network.
    if (Files.exists(dir)) {
      return launcherIn(dir) ?: throw UnavailableVersionException(
        "$dir holds no bin/$LAUNCHER: delete it to download kiff $version again"
      )
    }
    val zipName = "kiff-$version.zip"
    val zipUrl = "${baseUrl.trimEnd('/')}/v$version/$zipName"
    val sumUrl = "$zipUrl.sha256"
    val sum = fetch(sumUrl, version) { it.readNBytes(MAX_CHECKSUM) }
    val expected = checksumIn(sum, zipName, sumUrl)
    try {
      Files.createDirectories(versions)
      // Beside the target, so the move that installs it is a rename within one directory.
      val temp = Files.createTempDirectory(versions, ".$version-")
      try {
        val zip = temp.resolve(zipName)
        log.println("Downloading kiff $version from $zipUrl")
        val actual = fetch(zipUrl, version) { saveHashing(it, zip) }
        if (actual != expected) {
          throw UnavailableVersionException(
            "$zipUrl has SHA-256 $actual, but $sumUrl says $expected"
          )
        }
        val unpacked = temp.resolve("kiff")
        unzip(zip, unpacked, "kiff-$version/", zipUrl)
        if (launcherIn(unpacked) == null) {
          throw UnavailableVersionException("$zipUrl holds no kiff-$version/bin/$LAUNCHER")
        }
        try {
          Files.move(unpacked, dir, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: FileSystemException) {
          // Another kiff installed it first, and its copy is as good as this one.
          if (launcherIn(dir) == null) throw e
        }
      } finally {
        temp.toFile().deleteRecursively()
      }
    } catch (e: IOException) {
      throw UnavailableVersionException("Could not install kiff $version in $dir: ${reason(e)}")
    }
    return launcherIn(dir) ?: throw UnavailableVersionException("$dir holds no bin/$LAUNCHER")
  }

  private fun <T> fetch(url: String, version: String, read: (InputStream) -> T): T {
    val request = try {
      HttpRequest.newBuilder(URI.create(url)).build()
    } catch (e: IllegalArgumentException) {
      throw UnavailableVersionException("Could not download kiff $version: $url is not an http URL")
    }
    try {
      val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
      response.body().use { body ->
        when (val status = response.statusCode()) {
          200 -> return read(body)
          404 -> throw UnavailableVersionException("No release of kiff $version at $url (HTTP 404)")
          else -> throw UnavailableVersionException(
            "Could not download kiff $version from $url: HTTP $status"
          )
        }
      }
    } catch (e: IOException) {
      throw UnavailableVersionException("Could not download kiff $version from $url: ${reason(e)}")
    }
  }

  /** The hash in the line `sha256sum` writes, `<hash>  kiff-<version>.zip`, as a release has. */
  private fun checksumIn(bytes: ByteArray, zipName: String, url: String): String {
    val fields = bytes.decodeToString().trim().split(Regex("""\s+"""))
    val hash = fields[0].lowercase()
    val name = fields.getOrNull(1)?.removePrefix("*")
    if (fields.size > 2 || !SHA256.matches(hash) || (name != null && name != zipName)) {
      throw UnavailableVersionException("$url is not a SHA-256 checksum of $zipName")
    }
    return hash
  }

  private fun saveHashing(body: InputStream, file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newOutputStream(file).use { DigestInputStream(body, digest).copyTo(it) }
    return HexFormat.of().formatHex(digest.digest())
  }

  /** Unpacks [zip] into [dir], dropping the [root] directory a distribution keeps its files in. */
  private fun unzip(zip: Path, dir: Path, root: String, url: String) {
    val into = dir.toAbsolutePath().normalize()
    Files.createDirectories(into)
    ZipFile(zip.toFile()).use { archive ->
      for (entry in archive.entries()) {
        val target = into.resolve(entry.name.removePrefix(root)).normalize()
        if (!target.startsWith(into)) {
          throw UnavailableVersionException(
            "$url holds ${entry.name}, which would unpack outside its directory"
          )
        }
        if (target == into) continue
        if (entry.isDirectory) {
          Files.createDirectories(target)
        } else {
          Files.createDirectories(target.parent)
          archive.getInputStream(entry).use { Files.copy(it, target) }
        }
      }
    }
    // java.util.zip drops the file modes the zip was made with.
    into.resolve("bin").toFile().listFiles()?.forEach { it.setExecutable(true, false) }
  }

  private fun launcherIn(dir: Path): Path? =
    dir.resolve("bin").resolve(LAUNCHER).takeIf { Files.isRegularFile(it) }

  /** The first message along [e]'s causes. A refused connection or unknown host has none. */
  private fun reason(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }
    return causes.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
      ?: if (causes.any { it is ConnectException }) "could not connect" else e.javaClass.simpleName
  }

  companion object {
    const val RELEASES = "https://github.com/linroid/Kiff/releases/download"

    /** A `.sha256` holds one 81-byte line; anything far longer is not one. */
    private const val MAX_CHECKSUM = 4096
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val LAUNCHER =
      if (System.getProperty("os.name").startsWith("Windows")) "kiff.bat" else "kiff"

    /**
     * The versions under `KIFF_HOME`, by default `~/.kiff`, downloaded from `KIFF_DOWNLOAD_URL`, by
     * default [RELEASES], which a mirror or a test replaces.
     */
    fun fromEnvironment(log: PrintStream = System.err): KiffVersions {
      val home = System.getenv("KIFF_HOME")?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".kiff")
      val baseUrl = System.getenv("KIFF_DOWNLOAD_URL")?.takeIf { it.isNotBlank() } ?: RELEASES
      return KiffVersions(home.toAbsolutePath(), baseUrl, log)
    }
  }
}
