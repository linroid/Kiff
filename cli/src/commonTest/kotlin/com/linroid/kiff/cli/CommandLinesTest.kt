package com.linroid.kiff.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.IOException

class CommandLinesTest {

  @Test
  fun quotesOnlyWhatTheCRuntimeWouldSplitOrUnescape() {
    assertEquals("kiff create a b", windowsCommandLine(listOf("kiff", "create", "a", "b")))
    assertEquals("kiff \"two words\" \"\"", windowsCommandLine(listOf("kiff", "two words", "")))
    // Backslashes escape only a quote, so only those before one, or before the closing quote,
    // are doubled.
    assertEquals("C:\\out\\x", windowsCommandLine(listOf("C:\\out\\x")))
    assertEquals("\"C:\\my dir\\\\\"", windowsCommandLine(listOf("C:\\my dir\\")))
    assertEquals("\"say \\\"hi\\\"\"", windowsCommandLine(listOf("say \"hi\"")))
    assertEquals("\"a\\\\\\\"b\"", windowsCommandLine(listOf("a\\\"b")))
  }

  @Test
  fun quotesEveryArgumentOfABatchFileAndRefusesWhatCmdWouldRead() {
    assertEquals(
      "\"C:\\kiff\\bin\\kiff.bat\" \"create\" \"a & b\" \"x^y\" \"C:\\out dir\\\\\"",
      batchCommandLine(listOf("C:\\kiff\\bin\\kiff.bat", "create", "a & b", "x^y", "C:\\out dir\\"))
    )
    for (arg in listOf("100%", "say \"hi\"", "two\nlines")) {
      assertFailsWith<IOException>(arg) { batchCommandLine(listOf("kiff.bat", arg)) }
    }
  }

  @Test
  fun quotesForAPosixShellSoNothingIsExpanded() {
    assertEquals("'a b'", shellQuoted("a b"))
    assertEquals("'it'\\''s \$HOME'", shellQuoted("it's \$HOME"))
  }
}
