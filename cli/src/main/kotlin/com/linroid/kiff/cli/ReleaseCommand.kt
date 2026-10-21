package com.linroid.kiff.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.OptionCallTransformContext
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffException
import com.linroid.kiff.PatchInfo
import com.linroid.kiff.io.KiffFiles
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Builds the patches a release ships: one target, patched from each of the releases before it.
 *
 * `kiff create` makes one patch. A release needs several, each of which must restore exactly or
 * not ship at all, and none of which is worth publishing once it costs nearly what the file does.
 * This makes all of them in one JVM, and writes the manifest an updater reads to find the patch for
 * the version it has.
 *
 * One source that cannot be patched does not cost the others theirs: every source is tried, every
 * failure reported, and the manifest lists only the patches that are good.
 */
class ReleaseCommand : KiffCommand(name = "release") {
  override fun help(context: Context) =
    "Create, verify and list the patches from earlier releases to a new one"

  override fun helpEpilog(context: Context) = """
    Files are given as LABEL=FILE, or as FILE alone to be labelled by their name. The text before
    the first '=' is a label only when it holds no '/' or '\', so builds/v=2/app.apk is a file;
    give a file whose name has an '=' a label (v1=a=b.apk) or a directory (./a=b.apk).

    Exit status is 0 when every source gave a patch or was dropped for its size, and 1 when any
    could not be created or verified. The others are still written and listed.
  """.trimIndent() + "\n\n" + DETECTION_HELP

  private val target by option("--to", help = "The release's file, as FILE or LABEL=FILE")
    .convert("FILE") { labeled(it) }
    .required()
  private val sources by option(
    "--from",
    help = "An earlier release's file, as FILE or LABEL=FILE; repeat for each"
  ).convert("FILE") { labeled(it) }
    .multiple(required = true)
  private val out by option(
    "--out",
    metavar = "DIR",
    help = "Directory to write the patches to (default: .)"
  ).default(".")
  private val manifestPath by option(
    "--manifest",
    metavar = "FILE",
    help = "Manifest to write, merged into when it exists " +
      "(default: OUT/${ReleaseManifest.FILE_NAME})"
  )
  private val nameTemplate by option(
    "--name",
    metavar = "TEMPLATE",
    help = "Patch file name: {target} is the target's file name, {from} and {to} the labels " +
      "(default: $DEFAULT_NAME)"
  ).default(DEFAULT_NAME)
  private val baseUrl by option(
    "--base-url",
    metavar = "URL",
    help = "URL the patches are published under; without it the manifest gives no URLs"
  )
  private val patcherName by ignoredPatcherOption()
  private val maxRatio by option(
    "--max-ratio",
    metavar = "RATIO",
    help = "Drop a patch larger than this fraction of the target (default: $DEFAULT_MAX_RATIO)"
  ).double().restrictTo(min = 0.0).default(DEFAULT_MAX_RATIO)
  private val noVerify by option(
    "--no-verify",
    help = "Trust each patch rather than restoring it and comparing the result with the target"
  ).flag()

  override fun execute() {
    warnIgnoredPatcher(patcherName)
    requireFile(target.path)
    sources.forEach { requireFile(it.path) }
    val manifest = manifestPath ?: join(out, ReleaseManifest.FILE_NAME)
    val names = patchNames(manifest)
    // Read before any patch is made: a manifest that cannot be merged into fails at once rather
    // than after every patch is built.
    val existing = ReleaseManifest.read(manifest)
    Files.createDirectories(Paths.get(out))

    val labelWidth = sources.maxOf { it.label.length }
    val patches = mutableListOf<ReleasePatch>()
    var failures = 0
    val elapsed = measureMillis {
      val targetPrint = fingerprint(target.path)
      val labelled = if (target.label == target.fileName) "" else " (${target.label})"
      echo("${target.fileName}$labelled, ${formatBytes(targetPrint.size)}")
      for ((source, name) in sources.zip(names)) {
        val label = "  ${source.label.padEnd(labelWidth)}  "
        when (val outcome = release(source, name, targetPrint)) {
          is Outcome.Kept -> {
            patches += outcome.patch
            val status = if (noVerify) "created" else "restored"
            echo("$label${costOf(outcome.info)}  ${status.padEnd(8)}  $name")
          }
          is Outcome.Dropped ->
            echo("$label${costOf(outcome.info)}  dropped, over --max-ratio $maxRatio")
          is Outcome.Failed -> {
            failures++
            echo("${label}failed: ${outcome.reason}")
          }
        }
      }
      val file = ReleaseFile(
        name = target.fileName,
        version = target.label,
        url = urlOf(target.fileName),
        size = targetPrint.size,
        sha256 = targetPrint.sha256,
        patches = patches
      )
      Paths.get(manifest).toAbsolutePath().parent?.let { Files.createDirectories(it) }
      val text = ReleaseManifest.merge(existing, file, "kiff $kiffVersion")
      KiffFiles.writeBytes(manifest, text.encodeToByteArray())
      echo("Wrote $manifest (${count(patches.size, "patch", "patches")})")
    }
    echo("  Took:        $elapsed ms")
    if (failures > 0) {
      throw reported("$failures of ${count(sources.size, "source", "sources")} failed")
    }
  }

  private sealed interface Outcome {
    class Kept(val patch: ReleasePatch, val info: PatchInfo) : Outcome
    class Dropped(val info: PatchInfo) : Outcome
    class Failed(val reason: String) : Outcome
  }

  /** Answers a failure the library reports as the outcome it is, as [KiffCommand] would. */
  private fun release(source: Labeled, name: String, targetPrint: Fingerprint): Outcome = try {
    releaseOrThrow(source, name, targetPrint)
  } catch (e: KiffException) {
    Outcome.Failed(e.message.orEmpty())
  } catch (e: IOException) {
    Outcome.Failed(e.message ?: e.toString())
  }

  private fun releaseOrThrow(source: Labeled, name: String, targetPrint: Fingerprint): Outcome {
    val path = join(out, name)
    val info = Kiff.createPatch(source.path, target.path, path)
    var kept = false
    try {
      if (!noVerify) verify(source.path, path, targetPrint)
      // Compared in bytes, not as PatchInfo.ratio, which calls any patch of an empty target free.
      if (info.patchSize > maxRatio * targetPrint.size) return Outcome.Dropped(info)
      val sourcePrint = fingerprint(source.path)
      val patchPrint = fingerprint(path)
      val patch = ReleasePatch(
        from = source.label,
        source = ReleaseSource(source.fileName, sourcePrint.size, sourcePrint.sha256),
        name = name,
        url = urlOf(name),
        size = patchPrint.size,
        sha256 = patchPrint.sha256,
        // What the header records, as Kiff 0.1 wrote it, so the manifest schema stays at 1.
        patcher = info.patcher.name.lowercase(),
        format = info.formatVersion
      )
      kept = true
      return Outcome.Kept(patch, info)
    } finally {
      // What the manifest does not list is not left behind either, for a script that uploads the
      // whole directory to publish.
      if (!kept) Files.deleteIfExists(Paths.get(path))
    }
  }

  /** Restores [patch] as an updater would, and holds the result to the target's own hash. */
  private fun verify(source: String, patch: String, expected: Fingerprint) {
    // Outside --out, so a run that is killed leaves nothing there for an upload to pick up.
    val restored = Files.createTempFile("kiff-verify-", ".tmp")
    try {
      Kiff.applyPatch(source, patch, restored.toString())
      val result = fingerprint(restored.toString())
      if (result.size != expected.size || result.sha256 != expected.sha256) {
        throw KiffException.VerificationFailed(
          "$patch restores ${result.size} bytes with sha256 ${result.sha256}, not the target"
        )
      }
    } finally {
      Files.deleteIfExists(restored)
    }
  }

  /** Names each source's patch, refusing a template that does not give each its own file. */
  private fun patchNames(manifest: String): List<String> {
    sources.groupBy { it.label }.values.firstOrNull { it.size > 1 }?.let {
      throw UsageError("More than one --from is labelled ${it.first().label}")
    }
    val names = sources.map { source ->
      val values = mapOf("target" to target.fileName, "from" to source.label, "to" to target.label)
      val name = renderName(nameTemplate, values)
      if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) {
        throw UsageError("--name gives '$name' for ${source.label}, which is not a file name")
      }
      name
    }
    names.groupBy { it }.values.firstOrNull { it.size > 1 }?.let {
      throw UsageError("--name gives ${it.first()} for more than one source; put {from} in it")
    }
    val manifestFile = Paths.get(manifest).toAbsolutePath().normalize()
    names.firstOrNull { Paths.get(join(out, it)).toAbsolutePath().normalize() == manifestFile }
      ?.let { throw UsageError("--name gives $it, which is the manifest") }
    return names
  }

  private fun urlOf(name: String): String? = baseUrl?.let { base ->
    (if (base.endsWith("/")) base else "$base/") + encodePathSegment(name)
  }

  private fun costOf(info: PatchInfo) =
    "${formatBytes(info.patchSize).padStart(10)}  ${formatPercent(info.ratio).padStart(7)}"

  private companion object {
    const val DEFAULT_NAME = "{target}.from-{from}.kiff"
    const val DEFAULT_MAX_RATIO = 0.8
  }
}

/** A file given on the command line, and the label it goes by in names and the manifest. */
internal class Labeled(val label: String, val path: String) {
  val fileName: String get() = File(path).name

  companion object {
    /**
     * Reads `LABEL=FILE` or `FILE`.
     *
     * The text before the first `=` is a label only when it holds no path separator: a label
     * becomes part of a file name, where a separator would make it a directory. So a path with an
     * `=` in a directory needs nothing special, and one with an `=` in its own name is given a
     * label or a leading `./`.
     */
    fun parse(text: String): Labeled {
      val equals = text.indexOf('=')
      val label = if (equals < 0) null else text.substring(0, equals)
      if (label == null || '/' in label || '\\' in label) return Labeled(File(text).name, text)
      require(label.isNotEmpty()) { "No label before the '=' in '$text'" }
      val path = text.substring(equals + 1)
      require(path.isNotEmpty()) { "No file after '$label='" }
      return Labeled(label, path)
    }
  }
}

private fun OptionCallTransformContext.labeled(text: String): Labeled = try {
  Labeled.parse(text)
} catch (e: IllegalArgumentException) {
  fail(e.message.orEmpty())
}

private val PLACEHOLDER = Regex("""\{([^{}]*)}""")

/** Fills each `{key}` in [template] from [values], refusing a key it does not have. */
internal fun renderName(template: String, values: Map<String, String>): String =
  PLACEHOLDER.replace(template) { match ->
    values[match.groupValues[1]] ?: throw UsageError(
      "Unknown placeholder ${match.value} in --name; use ${values.keys.joinToString { "{$it}" }}"
    )
  }

/** [name] as one segment of a URL path: every byte outside RFC 3986's unreserved set escaped. */
internal fun encodePathSegment(name: String): String = buildString {
  for (byte in name.encodeToByteArray()) {
    val c = byte.toInt() and 0xFF
    val char = c.toChar()
    if (c < 0x80 && (char.isLetterOrDigit() || char in "-._~")) {
      append(char)
    } else {
      append('%').append(HEX_DIGITS[c shr 4]).append(HEX_DIGITS[c and 0xF])
    }
  }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

internal class Fingerprint(val size: Long, val sha256: String)

/** The size and SHA-256 of the file at [path], read once, in pieces. */
internal fun fingerprint(path: String): Fingerprint {
  val digest = MessageDigest.getInstance("SHA-256")
  var size = 0L
  FileInputStream(path).use { input ->
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
      size += read
    }
  }
  return Fingerprint(size, HexFormat.of().formatHex(digest.digest()))
}

/** [dir]/[name], or [name] alone for the current directory, which reads better in output. */
internal fun join(dir: String, name: String): String =
  if (dir == ".") name else File(dir, name).path

private fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
