package com.linroid.kiff.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.linroid.kiff.io.KiffFiles
import com.linroid.kiff.text.LineDiffEngine
import com.linroid.kiff.text.MyersDiffAlgorithm
import com.linroid.kiff.text.TextContent
import com.linroid.kiff.text.UnifiedDiff

class RootCommand : CliktCommand(name = "kiff") {
  override fun run() = Unit
}

class DiffCommand : KiffCommand(name = "diff") {
  override fun help(context: com.github.ajalt.clikt.core.Context) =
    "Print a unified diff between two text files"

  private val context by option(
    "-U", "--unified",
    help = "Lines of context around each change"
  ).int().default(3)
  private val sourceFile by argument(help = "Source file path")
  private val targetFile by argument(help = "Updated file path")

  override fun execute() {
    requireFile(sourceFile)
    requireFile(targetFile)
    val source = TextContent.of(KiffFiles.readBytes(sourceFile))
    val target = TextContent.of(KiffFiles.readBytes(targetFile))
    val edits = LineDiffEngine(MyersDiffAlgorithm()).generatePatch(source.lines, target.lines).edits
    val diff = UnifiedDiff.format(source, target, edits, sourceFile, targetFile, context)
    if (diff.isEmpty()) return
    echo(diff.trimEnd('\n'))
  }
}

fun main(args: Array<String>) {
  RootCommand()
    .subcommands(
      DiffCommand(),
      CreateCommand(),
      ApplyCommand(),
      InfoCommand(),
      ChangesCommand(),
      ExplainCommand()
    )
    .main(args)
}
