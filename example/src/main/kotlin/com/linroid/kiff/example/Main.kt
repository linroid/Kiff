package com.linroid.kiff.example

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.linroid.kiff.algorithm.BsDiffAlgorithm
import com.linroid.kiff.algorithm.MyersDiffAlgorithm
import com.linroid.kiff.binary.BinaryDiffEngine
import com.linroid.kiff.core.DiffEngine
import com.linroid.kiff.core.Edit
import com.linroid.kiff.io.FileSource

class KiffCommand : CliktCommand(name = "kiff") {
  override fun run() = Unit
}

class DiffCommand : CliktCommand(name = "diff") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
    "Generate a diff between two files"

  private val binary by option("-b", "--binary", help = "Use binary diff (bsdiff)")
    .flag()
  private val sourceFile by argument(help = "Source file path")
  private val targetFile by argument(help = "Target file path")

  override fun run() {
    if (binary) {
      runBinaryDiff()
    } else {
      runTextDiff()
    }
  }

  private fun runTextDiff() {
    val engine = DiffEngine(MyersDiffAlgorithm())
    val source = FileSource(sourceFile).readLines()
    val target = FileSource(targetFile).readLines()
    val patch = engine.generatePatch(source, target)

    for (edit in patch.edits) {
      when (edit) {
        is Edit.Equal ->
          echo("  (${edit.count} equal lines at position ${edit.position})")
        is Edit.Delete ->
          echo("- Delete ${edit.count} lines at position ${edit.position}")
        is Edit.Insert -> {
          echo("+ Insert ${edit.lines.size} lines at position ${edit.position}:")
          edit.lines.forEach { echo("+   $it") }
        }
      }
    }
  }

  private fun runBinaryDiff() {
    val engine = BinaryDiffEngine(BsDiffAlgorithm())
    val source = FileSource(sourceFile).readBytes()
    val target = FileSource(targetFile).readBytes()
    val patch = engine.generatePatch(source, target)

    echo("Binary diff (bsdiff):")
    echo("  Source size: ${source.size} bytes")
    echo("  Target size: ${target.size} bytes")
    echo("  Control blocks: ${patch.controlBlocks.size}")
    echo("  Diff bytes: ${patch.diffBytes.size}")
    echo("  Extra bytes: ${patch.extraBytes.size}")
  }
}

class PatchCommand : CliktCommand(name = "patch") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
    "Apply a diff to recover the target file"

  private val binary by option("-b", "--binary", help = "Use binary diff (bsdiff)")
    .flag()
  private val sourceFile by argument(help = "Source file path")
  private val targetFile by argument(help = "Target file path (used to generate the patch)")

  override fun run() {
    if (binary) {
      runBinaryPatch()
    } else {
      runTextPatch()
    }
  }

  private fun runTextPatch() {
    val engine = DiffEngine(MyersDiffAlgorithm())
    val source = FileSource(sourceFile).readLines()
    val target = FileSource(targetFile).readLines()
    val patch = engine.generatePatch(source, target)

    val recovered = engine.applyPatch(source, patch)
    recovered.forEach { echo(it) }
  }

  private fun runBinaryPatch() {
    val engine = BinaryDiffEngine(BsDiffAlgorithm())
    val source = FileSource(sourceFile).readBytes()
    val target = FileSource(targetFile).readBytes()
    val patch = engine.generatePatch(source, target)

    val recovered = engine.applyPatch(source, patch)
    echo("Recovered ${recovered.size} bytes")
    if (recovered.contentEquals(target)) {
      echo("Roundtrip OK: recovered matches target")
    } else {
      echo("ERROR: recovered does not match target")
    }
  }
}

fun main(args: Array<String>) {
  KiffCommand()
    .subcommands(DiffCommand(), PatchCommand())
    .main(args)
}
