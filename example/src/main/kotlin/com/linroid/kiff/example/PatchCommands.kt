package com.linroid.kiff.example

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.linroid.kiff.Kiff
import com.linroid.kiff.Patcher
import com.linroid.kiff.PatchInfo
import com.linroid.kiff.io.KiffFiles

class CreateCommand : KiffCommand(name = "create") {
  override fun help(context: Context) = "Create a patch that turns SOURCE into TARGET"

  private val patcherName by option(
    "-p",
    "--patcher",
    help = "Patcher to use: ${Kiff.patchers.joinToString("|") { it.name }}"
  ).default("binary")
  private val source by argument(help = "Original file")
  private val target by argument(help = "Updated file")
  private val patch by argument(help = "Patch file to write")

  override fun execute() {
    val patcher = resolvePatcher(patcherName)
    requireFile(source)
    requireFile(target)
    val elapsed = measureMillis {
      val info = Kiff.createPatch(patcher, source, target, patch)
      echo("Created $patch with the ${patcher.name} patcher")
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
      echo("Restored $output with the ${info.patcher.name.lowercase()} patcher")
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
    echoInfo(Kiff.info(KiffFiles.readBytes(patch)))
  }
}

private fun CliktCommand.echoInfo(info: PatchInfo) {
  echo("  Patcher:     ${info.patcher.name.lowercase()} (format v${info.formatVersion})")
  echo("  Source:      ${formatBytes(info.sourceSize)} crc32=${hex(info.sourceCrc32)}")
  echo("  Target:      ${formatBytes(info.targetSize)} crc32=${hex(info.targetCrc32)}")
  val share = formatPercent(info.ratio)
  echo("  Patch:       ${formatBytes(info.patchSize)} ($share of target)")
}

private fun hex(value: UInt) = value.toString(16).padStart(8, '0')

internal fun resolvePatcher(name: String): Patcher = Kiff.patcherOrNull(name)
  ?: throw UsageError(
    "Unknown patcher '$name'. Available: ${Kiff.patchers.joinToString(", ") { it.name }}"
  )

internal fun requireFile(path: String) {
  if (!KiffFiles.exists(path)) throw UsageError("File not found: $path")
}

internal inline fun measureMillis(block: () -> Unit): Long {
  val start = System.nanoTime()
  block()
  return (System.nanoTime() - start) / 1_000_000
}
