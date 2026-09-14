package com.linroid.kiff.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.linroid.kiff.text.MyersDiffAlgorithm
import com.linroid.kiff.text.LineDiffEngine
import com.linroid.kiff.text.Edit
import com.linroid.kiff.text.TextFile

class RootCommand : CliktCommand(name = "kiff") {
  override fun run() = Unit
}

class DiffCommand : KiffCommand(name = "diff") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
    "Generate a line-based diff between two text files"

  private val sourceFile by argument(help = "Source file path")
  private val targetFile by argument(help = "Target file path")

  override fun execute() {
    val engine = LineDiffEngine(MyersDiffAlgorithm())
    val source = TextFile(sourceFile).readLines()
    val target = TextFile(targetFile).readLines()
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
}

fun main(args: Array<String>) {
  RootCommand()
    .subcommands(
      DiffCommand(),
      CreateCommand(),
      ApplyCommand(),
      InfoCommand(),
      ChangesCommand()
    )
    .main(args)
}
