package com.linroid.kiff.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.terminal
import com.linroid.kiff.KiffException
import java.io.IOException

/**
 * Base for the Kiff subcommands.
 *
 * A [KiffException] is a verdict the library reached about the files it was handed - this source is
 * not the one the patch was built from, these bytes are not a patch - and a failed read or write is
 * the same kind of answer. Neither is a bug in Kiff, so neither deserves a stack trace: the frames
 * only bury the one sentence the user needs. Clikt prints a [CliktError] as that sentence and exits
 * non-zero, which is the whole contract a command-line tool owes its caller.
 *
 * Anything else still propagates with its trace intact, because anything else really is a bug.
 */
abstract class KiffCommand(name: String) : CliktCommand(name = name) {

  final override fun run() {
    try {
      execute()
    } catch (e: KiffException) {
      throw reported(e.message)
    } catch (e: IOException) {
      throw reported(e.message ?: e.toString())
    }
  }

  /** Runs the command. See [run] for the failures that are reported rather than thrown. */
  protected abstract fun execute()

  /** Borrows Clikt's own `Error:` label and styling so a library failure looks like any other. */
  private fun reported(message: String?): CliktError {
    val label = currentContext.terminal.theme.danger(currentContext.localization.usageError())
    return CliktError("$label $message")
  }
}
