package com.linroid.kiff.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.linroid.kiff.io.KiffFiles
import com.linroid.kiff.text.UnifiedDiff
import java.io.IOException
import java.io.OutputStream
import kotlin.system.exitProcess

class RootCommand : CliktCommand(name = "kiff") {
  override val invokeWithoutSubcommand = true

  override fun run() {
    // With no command there is nothing to do, which a script has to hear as a failure.
    if (currentContext.invokedSubcommand == null) {
      throw PrintHelpMessage(currentContext, error = true, statusCode = 1)
    }
  }
}

/**
 * Prints a unified diff, with diff(1)'s exit status: 0 when the files are the same, 1 when they
 * differ, and 2 when something went wrong - which is how a script tells "different" from
 * "broken".
 */
class DiffCommand(private val out: OutputStream = System.out) : KiffCommand(name = "diff") {
  override fun help(context: Context) = "Print a unified diff between two text files"

  override fun helpEpilog(context: Context) =
    "Exit status is 0 if the files are the same, 1 if they differ, and 2 if there is trouble."

  override val troubleStatus = 2

  private val context by option(
    "-U", "--unified",
    help = "Lines of context around each change"
  ).int().restrictTo(min = 0).default(3)
  private val sourceFile by argument(help = "Source file path")
  private val targetFile by argument(help = "Updated file path")

  override fun execute() {
    val source = read(sourceFile)
    val target = read(targetFile)
    if (source.contentEquals(target)) return
    val diff = if (isBinary(source) || isBinary(target)) {
      // Lines of a binary file mean nothing, and printing them hands a terminal raw control bytes.
      "Binary files $sourceFile and $targetFile differ\n".encodeToByteArray()
    } else {
      UnifiedDiff.formatBytes(source, target, sourceFile, targetFile, context)
    }
    // Written as the bytes it is rather than echoed: the terminal behind echo expands tabs and
    // re-encodes for the locale, and patch(1) needs the lines exactly as the files hold them.
    out.write(diff)
    out.flush()
    throw ProgramResult(1)
  }

  private fun read(path: String): ByteArray {
    if (!KiffFiles.exists(path)) throw IOException("File not found: $path")
    return KiffFiles.readBytes(path)
  }

  /** GNU diff's test: a NUL byte near the start. */
  private fun isBinary(bytes: ByteArray): Boolean {
    for (i in 0 until minOf(bytes.size, BINARY_SNIFF)) {
      if (bytes[i] == 0.toByte()) return true
    }
    return false
  }

  private companion object {
    const val BINARY_SNIFF = 8 * 1024
  }
}

internal fun kiff(out: OutputStream = System.out): RootCommand = RootCommand()
  .subcommands(
    DiffCommand(out),
    CreateCommand(),
    ApplyCommand(),
    InfoCommand(),
    ChangesCommand(),
    ExplainCommand()
  )

/** Runs the command line [args] describe and answers the exit status, as Clikt's `main` would. */
internal fun runKiff(args: Array<String>, out: OutputStream = System.out): Int {
  val kiff = kiff(out)
  return try {
    kiff.parse(args)
    0
  } catch (e: CliktError) {
    kiff.echoFormattedHelp(e)
    statusOf(e)
  }
}

/**
 * diff(1) keeps 1 for "the files differ", so a mistake in how `kiff diff` was called is trouble,
 * 2, like every other failure of that command.
 */
internal fun statusOf(e: CliktError): Int =
  if (e is UsageError && e.context?.command is DiffCommand) 2 else e.statusCode

fun main(args: Array<String>) {
  exitProcess(runKiff(args))
}
