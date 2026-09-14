package com.linroid.kiff.example

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.linroid.kiff.Kiff
import com.linroid.kiff.PatchAlgorithm
import com.linroid.kiff.PatchInfo
import com.linroid.kiff.io.KiffFiles

class CreateCommand : CliktCommand(name = "create") {
  override fun help(context: Context) = "Create a patch that turns SOURCE into TARGET"

  private val algorithmName by option(
    "-a",
    "--algorithm",
    help = "Algorithm to use: ${Kiff.algorithms.joinToString("|") { it.name }}"
  ).default("binary")
  private val source by argument(help = "Original file")
  private val target by argument(help = "Updated file")
  private val patch by argument(help = "Patch file to write")

  override fun run() {
    val algorithm = resolveAlgorithm(algorithmName)
    requireFile(source)
    requireFile(target)
    val elapsed = measureMillis {
      val info = Kiff.createPatch(algorithm, source, target, patch)
      echo("Created $patch with the ${algorithm.name} algorithm")
      echoInfo(info)
    }
    echo("  Took:        ${elapsed} ms")
  }
}

class ApplyCommand : CliktCommand(name = "apply") {
  override fun help(context: Context) = "Restore a file from SOURCE and PATCH"

  private val source by argument(help = "Original file")
  private val patch by argument(help = "Patch file")
  private val output by argument(help = "File to write the restored target to")

  override fun run() {
    requireFile(source)
    requireFile(patch)
    val elapsed = measureMillis {
      val info = Kiff.applyPatch(source, patch, output)
      echo("Restored $output with the ${info.algorithm.name.lowercase()} algorithm")
      echoInfo(info)
    }
    echo("  Took:        ${elapsed} ms")
  }
}

class InfoCommand : CliktCommand(name = "info") {
  override fun help(context: Context) = "Show what a patch file contains"

  private val patch by argument(help = "Patch file")

  override fun run() {
    requireFile(patch)
    echoInfo(Kiff.info(KiffFiles.readBytes(patch)))
  }
}

private fun CliktCommand.echoInfo(info: PatchInfo) {
  echo("  Algorithm:   ${info.algorithm.name.lowercase()} (format v${info.formatVersion})")
  echo("  Source:      ${formatBytes(info.sourceSize.toLong())} crc32=${hex(info.sourceCrc32)}")
  echo("  Target:      ${formatBytes(info.targetSize.toLong())} crc32=${hex(info.targetCrc32)}")
  val share = formatPercent(info.ratio)
  echo("  Patch:       ${formatBytes(info.patchSize.toLong())} ($share of target)")
}

private fun hex(value: UInt) = value.toString(16).padStart(8, '0')

internal fun resolveAlgorithm(name: String): PatchAlgorithm = Kiff.algorithmOrNull(name)
  ?: throw UsageError(
    "Unknown algorithm '$name'. Available: ${Kiff.algorithms.joinToString(", ") { it.name }}"
  )

internal fun requireFile(path: String) {
  if (!KiffFiles.exists(path)) throw UsageError("File not found: $path")
}

internal inline fun measureMillis(block: () -> Unit): Long {
  val start = System.nanoTime()
  block()
  return (System.nanoTime() - start) / 1_000_000
}
