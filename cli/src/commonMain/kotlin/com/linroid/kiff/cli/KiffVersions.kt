package com.linroid.kiff.cli

import kotlin.random.Random
import okio.Buffer
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/** Why `kiff --use` could not hand a command to the version it names. */
internal class UnavailableVersionException(message: String) : Exception(message)

/**
 * The version a leading `--use X` or `--use=X` names, and the arguments after it, which are left
 * for that version to read. Only the first argument counts: anywhere else `--use` is the command's.
 */
internal fun leadingVersion(args: Array<String>): Pair<String, List<String>>? {
  val first = args.firstOrNull() ?: return null
  return when {
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
 * The released Kiff builds that `kiff --use` hands a command to, each downloaded once and kept in
 * [home]`/versions/<version>`, whose `bin` holds the launcher.
 *
 * A release attaches an archive for each platform it is built for, `kiff-<version>-<platform>` with
 * `.tar.gz`, or `.zip` for Windows, holding the executable, `LICENSE` and `README.md` in a
 * directory of that name, and a zip of the JVM build, `kiff-<version>.zip`, which runs anywhere
 * Java 17 does. 0.2.0 attached each executable bare instead, as `kiff-<version>-<platform>`, and
 * 0.1.0 has only the JVM zip. So the archive for this machine is tried first, then the bare
 * executable, then the JVM zip. Whichever is taken is checked against the `.sha256` beside it
 * before it is unpacked or kept, and the executable is installed as `bin/kiff` from either kind.
 *
 * Downloads go through [fetcher], by default [Curl]. A `.tar.gz` is unpacked with the system's
 * `tar`, and a zip in this process.
 */
internal class KiffVersions(
  private val home: Path,
  private val baseUrl: String,
  /** Where progress and failures go, so standard output stays the command's own. */
  val log: (String) -> Unit,
  /** This machine, as release asset names give it, or null to take the JVM build. */
  private val platform: String? = nativePlatform,
  /** The Java this kiff runs on, or null when it is native and a JVM release needs another. */
  private val javaHome: String? = runningJavaHome,
  private val fetcher: Fetcher = Curl
) {

  private val fileSystem = systemFileSystem

  /** Runs [args] with Kiff [version], installed first if need be, and answers its exit status. */
  fun run(version: String, args: List<String>): Int {
    val launcher = install(version)
    val added = mutableMapOf<String, String>()
    // A JVM release's launcher tries JAVA_HOME before java on the PATH, and the Java this kiff
    // runs on, when it runs on one, is the one sure to exist.
    if (javaHome != null && environment("JAVA_HOME").isNullOrBlank()) added["JAVA_HOME"] = javaHome
    return try {
      handOver(listOf(launcher.toString()) + args, added)
    } catch (e: IOException) {
      throw UnavailableVersionException("Could not run $launcher: ${reason(e)}")
    }
  }

  /** Answers the launcher of Kiff [version], downloading it unless it is already installed. */
  fun install(version: String): Path {
    val versions = home / "versions"
    val dir = versions / version
    // Only a complete install is ever moved into place, so one that is there needs no network.
    if (fileSystem.exists(dir)) {
      val launcher = launcherIn(dir) ?: throw UnavailableVersionException(
        "$dir holds no bin/${LAUNCHERS.first()}: delete it to download kiff $version again"
      )
      if (fileSystem.exists(dir / "lib")) requireJava("kiff $version in $dir")
      return launcher
    }
    val release = "${baseUrl.trimEnd('/')}/v$version"
    val scratch = temporaryDirectory / "kiff-${randomName()}"
    try {
      fileSystem.createDirectories(scratch)
      val download = Download(scratch, version)
      if (platform != null) {
        val name = "kiff-$version-$platform"
        val windows = platform.startsWith("windows-")
        val archive = name + if (windows) ".zip" else ".tar.gz"
        download.checksum("$release/$archive", archive, missingOk = true)?.let { sha256 ->
          return installFrom(versions, dir) { unpacked ->
            val url = "$release/$archive"
            val file = unpacked.parent!! / archive
            log("Downloading kiff $version from $url")
            download.file(url, file, sha256)
            unpack(file, unpacked, name, if (windows) "kiff.exe" else "kiff", url)
          }
        }
        // As 0.2.0 attached it.
        val bare = name + if (windows) ".exe" else ""
        download.checksum("$release/$bare", bare, missingOk = true)?.let { sha256 ->
          return installFrom(versions, dir) { unpacked ->
            val url = "$release/$bare"
            val file = unpacked / "bin" / LAUNCHERS.first()
            fileSystem.createDirectories(unpacked / "bin")
            log("Downloading kiff $version from $url")
            download.file(url, file, sha256)
            makeExecutable(file)
          }
        }
      }
      val zipName = "kiff-$version.zip"
      val url = "$release/$zipName"
      val zipSum = download.checksum(url, zipName, missingOk = false)!!
      if (platform != null && javaHome == null) {
        log("kiff $version has no build for $platform, so it runs on Java")
      }
      requireJava("kiff $version")
      return installFrom(versions, dir) { unpacked ->
        val zip = unpacked.parent!! / zipName
        log("Downloading kiff $version from $url")
        download.file(url, zip, zipSum)
        unzip(zip, unpacked, "kiff-$version", url)
        if (launcherIn(unpacked) == null) {
          throw UnavailableVersionException("$url holds no kiff-$version/bin/${LAUNCHERS.last()}")
        }
      }
    } catch (e: IOException) {
      throw UnavailableVersionException("Could not install kiff $version in $dir: ${reason(e)}")
    } finally {
      fileSystem.deleteRecursively(scratch, mustExist = false)
    }
  }

  /**
   * Fills a new directory beside [dir] with [fill] and moves it to [dir], so that only a complete
   * install is ever there, and answers its launcher.
   */
  private fun installFrom(versions: Path, dir: Path, fill: (unpacked: Path) -> Unit): Path {
    fileSystem.createDirectories(versions)
    // Beside the target, so the move that installs it is a rename within one directory.
    val temp = versions / ".${dir.name}-${randomName()}"
    fileSystem.createDirectory(temp, mustCreate = true)
    try {
      val unpacked = temp / "kiff"
      fileSystem.createDirectory(unpacked)
      fill(unpacked)
      try {
        fileSystem.atomicMove(unpacked, dir)
      } catch (e: IOException) {
        // Another kiff installed it first, and its copy is as good as this one.
        if (launcherIn(dir) == null) throw e
      }
    } finally {
      fileSystem.deleteRecursively(temp, mustExist = false)
    }
    return launcherIn(dir) ?: throw UnavailableVersionException("$dir holds no bin/kiff")
  }

  /** Downloads for [version], keeping checksums in [scratch]. */
  private inner class Download(private val scratch: Path, private val version: String) {

    /**
     * The hash in [url]`.sha256`, the line `sha256sum` writes for [name], or null when there is
     * no such file and [missingOk].
     */
    fun checksum(url: String, name: String, missingOk: Boolean): String? {
      val sumUrl = "$url.sha256"
      val file = scratch / "$name.sha256"
      if (!fetch(sumUrl, file, missingOk)) return null
      val size = fileSystem.metadata(file).size ?: 0
      val fields = if (size > MAX_CHECKSUM) {
        emptyList()
      } else {
        fileSystem.read(file) { readUtf8() }.trim().split(Regex("""\s+"""))
      }
      val hash = fields.firstOrNull()?.lowercase().orEmpty()
      val named = fields.getOrNull(1)?.removePrefix("*")
      if (fields.size > 2 || !SHA256.matches(hash) || (named != null && named != name)) {
        throw UnavailableVersionException("$sumUrl is not a SHA-256 checksum of $name")
      }
      return hash
    }

    /** Downloads [url] to [file] and holds it to [sha256]. */
    fun file(url: String, file: Path, sha256: String) {
      fetch(url, file, missingOk = false)
      val actual = fingerprint(file).sha256
      if (actual != sha256) {
        throw UnavailableVersionException("$url has SHA-256 $actual, but $url.sha256 says $sha256")
      }
    }

    /** Downloads [url] to [file], answering false for a 404 when [missingOk]. */
    private fun fetch(url: String, file: Path, missingOk: Boolean): Boolean {
      if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw UnavailableVersionException(
          "Could not download kiff $version: $url is not an http URL"
        )
      }
      val fetched = try {
        fetcher.fetch(url, file)
      } catch (e: ProgramNotFoundException) {
        throw UnavailableVersionException(
          "Could not download kiff $version: it is downloaded with ${e.program}, which is not " +
            "installed. Install ${e.program}, or download $url yourself"
        )
      }
      return when (fetched.status) {
        200 -> true
        404 -> if (missingOk) {
          false
        } else {
          throw UnavailableVersionException("No release of kiff $version at $url (HTTP 404)")
        }
        0 -> throw UnavailableVersionException(
          "Could not download kiff $version from $url: ${fetched.failure ?: "no response"}"
        )
        else -> throw UnavailableVersionException(
          "Could not download kiff $version from $url: HTTP ${fetched.status}"
        )
      }
    }
  }

  /**
   * Unpacks [archive], downloaded from [url], into [dir] as a version is kept: its [executable] as
   * `bin/kiff`, where a bare executable goes too, and `LICENSE` and `README.md` beside `bin`. The
   * archive holds them in its [root] directory, and anything outside that refuses it.
   */
  private fun unpack(archive: Path, dir: Path, root: String, executable: String, url: String) {
    val files = if (archive.name.endsWith(".zip")) {
      (dir.parent!! / "unzipped").also {
        fileSystem.createDirectory(it)
        unzip(archive, it, root, url)
      }
    } else {
      untar(archive, dir.parent!! / "untarred", root, url) / root
    }
    // A link, which tar may unpack, is no executable either.
    if (fileSystem.metadataOrNull(files / executable)?.isRegularFile != true) {
      throw UnavailableVersionException("$url holds no $root/$executable")
    }
    val launcher = dir / "bin" / LAUNCHERS.first()
    fileSystem.createDirectory(dir / "bin")
    fileSystem.atomicMove(files / executable, launcher)
    makeExecutable(launcher)
    for (name in listOf("LICENSE", "README.md")) {
      if (fileSystem.metadataOrNull(files / name)?.isRegularFile == true) {
        fileSystem.atomicMove(files / name, dir / name)
      }
    }
  }

  /**
   * Unpacks the `.tar.gz` [archive] into the new directory [dir] with the system's tar, which keeps
   * each file's mode, and answers [dir]. It is refused first if any entry is outside [root], and
   * tar itself does not write through a link it has unpacked.
   */
  private fun untar(archive: Path, dir: Path, root: String, url: String): Path {
    val listing = archive.parent!! / ".entries"
    tar(url, listing, "-tzf", archive.toString())
    fileSystem.read(listing) { readUtf8() }.lines()
      .filter { it.isNotEmpty() }
      .forEach { checkEntry(it, root, url) }
    fileSystem.createDirectory(dir)
    tar(url, listing, "-xzf", archive.toString(), "-C", dir.toString())
    return dir
  }

  /** Runs tar with [args] and its output in [output], refusing [url] when tar fails. */
  private fun tar(url: String, output: Path, vararg args: String) {
    val errors = output.parent!! / ".tar-errors"
    val status = try {
      runCommand(listOf("tar") + args, stdout = output, stderr = errors)
    } catch (e: ProgramNotFoundException) {
      throw UnavailableVersionException(
        "Could not unpack $url: it is unpacked with ${e.program}, which is not installed. " +
          "Install ${e.program}"
      )
    }
    if (status != 0) {
      val said = fileSystem.read(errors) { readUtf8() }.lines().lastOrNull { it.isNotBlank() }
      throw UnavailableVersionException("Could not unpack $url: ${said ?: "tar exited $status"}")
    }
  }

  /** Refuses [url] for its entry [name] unless the entry is its [root] directory or inside it. */
  private fun checkEntry(name: String, root: String, url: String) {
    val path = name.replace('\\', '/')
    if (path.startsWith("/") || ".." in path.split('/') || !"$path/".startsWith("$root/")) {
      throw UnavailableVersionException("$url holds $name, which is outside its $root directory")
    }
  }

  /**
   * Unpacks [zip] into [dir], leaving out the [root] directory a distribution keeps its files in,
   * and refusing anything outside it.
   */
  private fun unzip(zip: Path, dir: Path, root: String, url: String) {
    // okio reads each entry as a path within the zip, so none could be written outside dir; one
    // that names a place outside root is still no distribution, and is refused rather than missed.
    for (name in zipEntryNames(zip)) checkEntry(name, root, url)
    val archive = fileSystem.openZip(zip)
    val top = "/".toPath() / root
    for (entry in archive.listRecursively(top).toList()) {
      val target = dir / entry.relativeTo(top)
      if (archive.metadata(entry).isDirectory) {
        fileSystem.createDirectories(target)
      } else {
        target.parent?.let { fileSystem.createDirectories(it) }
        archive.source(entry).use { source ->
          fileSystem.sink(target).buffer().use { it.writeAll(source) }
        }
      }
    }
    // A zip's file modes are not read back, and the launchers have to be runnable.
    fileSystem.listOrNull(dir / "bin")?.forEach(::makeExecutable)
  }

  /** The name of every entry in [zip]'s central directory, as it is written there. */
  private fun zipEntryNames(zip: Path): List<String> = fileSystem.openReadOnly(zip).use { file ->
    // The end record is the last 22 bytes, unless a comment of up to 64 KiB follows it.
    val size = file.size()
    val tailSize = minOf(size, 22L + 0xFFFF).toInt()
    val tail = ByteArray(tailSize)
    file.read(size - tailSize, tail, 0, tailSize)
    val end = (tailSize - 22 downTo 0).firstOrNull { tail.intLe(it) == 0x06054B50 }
      ?: throw IOException("$zip is not a zip")
    val count = tail.shortLe(end + 10)
    val directorySize = tail.intLe(end + 12).toLong() and 0xFFFFFFFFL
    val offset = tail.intLe(end + 16).toLong() and 0xFFFFFFFFL
    val directory = Buffer()
    file.source(offset).buffer().use { it.readFully(directory, directorySize) }
    List(count) {
      if (directory.readIntLe() != 0x02014B50) throw IOException("$zip has a broken directory")
      directory.skip(24)
      val nameSize = directory.readShortLe().toInt() and 0xFFFF
      val extraSize = directory.readShortLe().toInt() and 0xFFFF
      val commentSize = directory.readShortLe().toInt() and 0xFFFF
      directory.skip(12)
      val name = directory.readUtf8(nameSize.toLong())
      directory.skip((extraSize + commentSize).toLong())
      name
    }
  }

  private fun ByteArray.intLe(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
      ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

  private fun ByteArray.shortLe(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

  /**
   * Refuses a JVM build, [what], when there is no Java for it: a native kiff has none of its own,
   * and the launcher would otherwise fail with less to say.
   */
  private fun requireJava(what: String) {
    if (javaHome != null) return
    val java = if (isWindows) "java.exe" else "java"
    val home = environment("JAVA_HOME")?.takeIf { it.isNotBlank() }
    val found = if (home != null) {
      fileSystem.exists(platformPath(home) / "bin" / java)
    } else {
      onPath(java)
    }
    if (found) return
    val where = if (home != null) "JAVA_HOME is $home, which has no bin/$java" else
      "set JAVA_HOME, or put java on the PATH"
    throw UnavailableVersionException("$what runs on Java 17 or newer, and there is none: $where")
  }

  private fun onPath(program: String): Boolean =
    environment("PATH").orEmpty().split(if (isWindows) ';' else ':')
      .any { it.isNotEmpty() && fileSystem.exists(platformPath(it) / program) }

  private fun launcherIn(dir: Path): Path? = LAUNCHERS.map { dir / "bin" / it }
    .firstOrNull { fileSystem.metadataOrNull(it)?.isRegularFile == true }

  /** The first message along [e]'s causes. */
  private fun reason(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }
    return causes.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
      ?: e::class.simpleName.orEmpty()
  }

  private fun randomName() = Random.nextLong().toULong().toString(16)

  companion object {
    const val RELEASES = "https://github.com/linroid/kiff/releases/download"

    /** A `.sha256` holds one line of about 100 bytes; anything far longer is not one. */
    private const val MAX_CHECKSUM = 4096
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** What a version's bin may hold, the native build's first, then the JVM build's. */
    private val LAUNCHERS = if (isWindows) listOf("kiff.exe", "kiff.bat") else listOf("kiff")

    /**
     * The versions under `KIFF_HOME`, by default `~/.kiff`, downloaded from `KIFF_DOWNLOAD_URL`, by
     * default [RELEASES], which a mirror or a test replaces.
     */
    fun fromEnvironment(log: (String) -> Unit = ::printError): KiffVersions {
      val home = environment("KIFF_HOME")?.takeIf { it.isNotBlank() }
        ?: (platformPath(homeDirectory()) / ".kiff").toString()
      val baseUrl = environment("KIFF_DOWNLOAD_URL")?.takeIf { it.isNotBlank() } ?: RELEASES
      return KiffVersions(absolute(home), baseUrl, log)
    }
  }
}

/** What fetching a URL came to: its last response's HTTP status, or 0 and why there was none. */
internal class Fetched(val status: Int, val failure: String? = null)

/** Fetches a URL into a file, which holds the body when the status is 200. */
internal fun interface Fetcher {
  fun fetch(url: String, file: Path): Fetched
}

/**
 * Fetches with the system's curl rather than a client of Kiff's own, which would more than double
 * the size of a native kiff for a command most runs never use.
 */
internal object Curl : Fetcher {
  override fun fetch(url: String, file: Path): Fetched {
    val directory = file.parent!!
    val status = directory / ".${file.name}.status"
    val errors = directory / ".${file.name}.errors"
    try {
      runCommand(
        listOf(
          "curl", "--fail", "--silent", "--show-error", "--location",
          // A release's download answers with a redirect to wherever GitHub stores the file, and
          // nowhere it could send curl but another web address is one to follow.
          "--proto", "=http,https", "--proto-redir", "=http,https",
          "--connect-timeout", "30",
          "--output", file.toString(),
          "--write-out", "%{http_code}",
          url
        ),
        stdout = status,
        stderr = errors
      )
      // The status of the last response, after any redirect, or 000 when there was none at all.
      val code = systemFileSystem.read(status) { readUtf8() }.trim().toIntOrNull() ?: 0
      if (code != 0) return Fetched(code)
      // curl's own words, such as "Failed to connect to ... port 80", without its prefix.
      val said = systemFileSystem.read(errors) { readUtf8() }.lines()
        .lastOrNull { it.isNotBlank() }
        ?.replace(Regex("""^curl: \(\d+\) """), "")
      return Fetched(0, said ?: "curl failed")
    } finally {
      systemFileSystem.delete(status, mustExist = false)
      systemFileSystem.delete(errors, mustExist = false)
    }
  }
}
