package com.linroid.kiff.cli

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What `--use` decides before anything is downloaded, and what this build says about itself. */
class UseVersionTest {

  private val dir = TestDirectory()

  @AfterTest
  fun cleanUp() {
    dir.delete()
  }

  @Test
  fun runsTheCommandItselfWhenItIsThisVersion() {
    val a = dir.file("a", "x\n")
    val b = dir.file("b", "y\n")
    // capture's versions fail the test if anything is fetched.
    val run = capture("--use", kiffVersion, "diff", a, b)

    // Exactly what the command gives without --use. The names in the diff's header are the paths
    // as given, which a Windows path's backslashes have quoted, as git quotes them.
    val direct = capture("diff", a, b)
    assertEquals(1, run.status, run.err)
    assertContentEquals(direct.bytes, run.bytes, run.bytes.decodeToString())
    assertTrue(run.bytes.decodeToString().startsWith("--- "), run.bytes.decodeToString())
    assertEquals("", run.err)
  }

  @Test
  fun refusesASnapshotOrAnythingElseNoReleaseHas() {
    val refusals = listOf(
      "9.9.9-SNAPSHOT" to "9.9.9-SNAPSHOT is a snapshot, and no snapshot has a release to download",
      "v9.9.9" to "v9.9.9 is a tag, and its version is 9.9.9",
      "latest" to "\"latest\" is not a released Kiff version, such as 1.2.3",
      "../9.9.9" to "\"../9.9.9\" is not a released Kiff version",
      "9.9" to "\"9.9\" is not a released Kiff version",
      "" to "\"\" is not a released Kiff version"
    )
    for ((version, reason) in refusals) {
      for (use in listOf(arrayOf("--use", version), arrayOf("--use=$version"))) {
        // capture's versions fail the test if anything is fetched.
        val run = capture(*use, "create", "a", "b", "c")
        assertEquals(1, run.status, use.joinToString(" "))
        assertTrue(run.err.startsWith("Error: $reason"), run.err)
      }
    }
  }

  @Test
  fun readsTheVersionOnlyFromALeadingUse() {
    val rest = listOf("create", "--use", "3.0.0")
    for (form in listOf(listOf("--use", "1.2.3"), listOf("--use=1.2.3"))) {
      assertEquals("1.2.3" to rest, leadingVersion((form + rest).toTypedArray()), "$form")
    }
    // After the command --use is the command's, -v is --version, and + names nothing.
    assertNull(leadingVersion(arrayOf("create", "--use", "1.2.3")))
    assertNull(leadingVersion(arrayOf("-v", "1.2.3")))
    assertNull(leadingVersion(arrayOf("+1.2.3", "create")))
    assertNull(leadingVersion(arrayOf("--version")))
    // Without a value Clikt says what is missing.
    assertNull(leadingVersion(arrayOf("--use")))
    assertNull(leadingVersion(emptyArray()))
  }

  @Test
  fun takesALeadingPlusAsAnOrdinaryArgument() {
    // It is no command of this build's, so Clikt refuses it, and nothing is fetched.
    val run = capture("+9.9.9", "create", "a", "b", "c")
    assertEquals(1, run.status)
    assertTrue("+9.9.9" in run.err, run.err)
  }

  @Test
  fun printsItsOwnVersionForEitherFlagAndExplainsUseInItsHelp() {
    for (flag in listOf("-v", "--version")) {
      val version = capture(flag)
      assertEquals(0, version.status, version.err)
      assertEquals(kiffVersionLine, version.out.trim(), flag)
    }
    // Stamped by the build, not left as a placeholder.
    assertFalse("$" in kiffVersion || kiffVersion.isBlank(), kiffVersion)
    // Only a snapshot names its commit, and only as git abbreviates one.
    val commit = kiffCommit
    if (commit == null) {
      assertEquals("kiff $kiffVersion", kiffVersionLine)
    } else {
      assertTrue(kiffVersion.endsWith("-SNAPSHOT"), kiffVersion)
      assertTrue(Regex("[0-9a-f]{4,40}").matches(commit), commit)
      assertEquals("kiff $kiffVersion ($commit)", kiffVersionLine)
    }

    val help = capture("--help")
    assertEquals(0, help.status, help.err)
    assertTrue("kiff --use 0.1.0 create old.apk new.apk patch.kiff" in help.out, help.out)
    assertTrue("--use=<version>  Run with that released Kiff (first argument only)" in help.out)
    assertTrue("-v, --version" in help.out, help.out)
    assertTrue("--licenses" in help.out, help.out)
  }

  @Test
  fun printsTheLicencesItShipsUnder() {
    val run = capture("--licenses")
    assertEquals(0, run.status, run.err)
    assertTrue(run.out.startsWith("Kiff\n\n"), run.out.take(200))
    // Kiff's own, then each of what it is built from with its notice.
    for (part in listOf(
      "Apache License", "Version 2.0, January 2004", "Copyright 2026 Lin Zhang",
      "Copyright 2018 AJ Alt", "Copyright 2013 Square, Inc.", "Copyright 2021 AJ Alt",
      "Stephen Colebourne", "mingw-w64 project", "Jean-loup Gailly"
    )) {
      assertTrue(part in run.out, part)
    }
  }
}
