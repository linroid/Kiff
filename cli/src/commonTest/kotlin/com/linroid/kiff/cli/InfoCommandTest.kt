package com.linroid.kiff.cli

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfoCommandTest {

  private val dir = TestDirectory()

  @AfterTest
  fun cleanUp() {
    dir.delete()
  }

  /** Writes a patch from [source] to [target] and answers what `kiff info` says of it. */
  private fun info(source: ByteArray, target: ByteArray): String {
    val patch = dir.resolve("patch.kiff").toString()
    val created = capture("create", dir.file("source", source), dir.file("target", target), patch)
    assertEquals(0, created.status, created.err)
    val run = capture("info", patch)
    assertEquals(0, run.status, run.err)
    return run.out
  }

  @Test
  fun aPatchWhoseTreeIsPackedSaysSo() {
    // An insertion every few dozen bytes takes an instruction each, which is the tree that packs.
    val source = Random(1).nextBytes(40_000)
    val target = source.toList().chunked(40).flatMap { it + 0x41.toByte() }.toByteArray()
    val out = info(source, target)
    assertTrue("(format v1, packed tree)" in out, out)
  }

  @Test
  fun aPatchWhoseTreeIsStoredSaysOnlyItsFormat() {
    val source = Random(2).nextBytes(4_000)
    val target = source.copyOf().also { it[100] = 1 }
    val out = info(source, target)
    assertTrue("(format v1)" in out, out)
  }
}
