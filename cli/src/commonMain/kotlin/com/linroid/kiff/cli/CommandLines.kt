package com.linroid.kiff.cli

import okio.IOException

/** [text] in single quotes, which a POSIX shell takes literally but for a quote itself. */
internal fun shellQuoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/**
 * [args] as the one line Windows starts a program with, quoted so that the C runtime's parsing,
 * which nearly every program uses, reads back each argument exactly.
 */
internal fun windowsCommandLine(args: List<String>): String =
  args.joinToString(" ") { windowsQuoted(it, always = false) }

/**
 * [args], a batch file and what to pass it, as the line `cmd.exe /s /c` runs.
 *
 * cmd.exe reads `%` and `"` itself even within quotes, so an argument that holds either, or a line
 * break, could not be passed on as it is, and is refused. Quoting each argument keeps cmd.exe's
 * other special characters literal, and the quoting is the C runtime's, so the Java a release's
 * launcher hands the arguments to reads them back exactly.
 */
internal fun batchCommandLine(args: List<String>): String =
  args.joinToString(" ") { arg ->
    if (arg.any { it == '%' || it == '"' || it == '\n' || it == '\r' }) {
      throw IOException("cmd.exe cannot pass on an argument with % or \" in it: $arg")
    }
    windowsQuoted(arg, always = true)
  }

/** One argument as the C runtime's parsing reads it back: backslashes only escape a quote. */
private fun windowsQuoted(arg: String, always: Boolean): String {
  if (!always && arg.isNotEmpty() && arg.none { it in " \t\n\u000B\"" }) return arg
  return buildString {
    append('"')
    var backslashes = 0
    for (c in arg) {
      when (c) {
        '\\' -> backslashes++
        '"' -> {
          // The backslashes before a quote are escaped, and then the quote itself.
          repeat(backslashes * 2 + 1) { append('\\') }
          append('"')
          backslashes = 0
        }
        else -> {
          repeat(backslashes) { append('\\') }
          append(c)
          backslashes = 0
        }
      }
    }
    // Before the closing quote, backslashes would escape it, so they are doubled.
    repeat(backslashes * 2) { append('\\') }
    append('"')
  }
}
