package com.linroid.kiff.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.linroid.kiff.Kiff
import com.linroid.kiff.PatchInfo
import com.linroid.kiff.io.KiffFiles
import kotlin.time.TimeSource

class CreateCommand : KiffCommand(name = "create") {
  override fun help(context: Context) = "Create a patch that turns SOURCE into TARGET"

  override fun helpEpilog(context: Context) = DETECTION_HELP

  private val source by argument(help = "Original file")
  private val target by argument(help = "Updated file")
  private val patch by argument(help = "Patch file to write")

  override fun execute() {
    requireFile(source)
    requireFile(target)
    val elapsed = measureMillis {
      val info = Kiff.createPatch(source, target, patch)
      echo("Created $patch")
      echoInfo(info)
    }
    echo("  Took:        ${elapsed} ms")
  }
}

class ApplyCommand : KiffCommand(name = "apply") {
  override fun help(context: Context) = "Restore a file from SOURCE and PATCH"

  private val source by argument(help = "Original file")
  private val patch by argument(help = "Patch file")
  private val output by argument(help = "File to write the restored target to")

  override fun execute() {
    requireFile(source)
    requireFile(patch)
    val elapsed = measureMillis {
      val info = Kiff.applyPatch(source, patch, output)
      echo("Restored $output")
      echoInfo(info)
    }
    echo("  Took:        ${elapsed} ms")
  }
}

class InfoCommand : KiffCommand(name = "info") {
  override fun help(context: Context) = "Show what a patch file contains"

  private val patch by argument(help = "Patch file")

  override fun execute() {
    requireFile(patch)
    echoInfo(Kiff.info(patch))
  }
}

private fun CliktCommand.echoInfo(info: PatchInfo) {
  echo("  Read as:     ${info.patcher.name.lowercase()} (format v${info.formatVersion})")
  echo("  Source:      ${formatBytes(info.sourceSize)} crc32=${hex(info.sourceCrc32)}")
  echo("  Target:      ${formatBytes(info.targetSize)} crc32=${hex(info.targetCrc32)}")
  val share = formatPercent(info.ratio)
  echo("  Patch:       ${formatBytes(info.patchSize)} ($share of target)")
}

private fun hex(value: UInt) = value.toString(16).padStart(8, '0')

/** How `create` and `release` decide what a file is, for their help. */
internal const val DETECTION_HELP =
  "An APK - a zip that carries an AndroidManifest.xml - is compared entry by entry, with its " +
    "dex files searched together. Any other zip is compared entry by entry, and any other file " +
    "as plain bytes. Inside an archive, archives and dex files are taken apart where that " +
    "makes the patch smaller."

internal fun requireFile(path: String) {
  if (!KiffFiles.exists(path)) throw UsageError("File not found: $path")
}

internal inline fun measureMillis(block: () -> Unit): Long {
  val start = TimeSource.Monotonic.markNow()
  block()
  return start.elapsedNow().inWholeMilliseconds
}
