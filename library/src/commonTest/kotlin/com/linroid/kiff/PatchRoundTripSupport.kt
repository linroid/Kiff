package com.linroid.kiff

import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Asserts that [patcher] rebuilds [target] byte for byte, and reports the patch size. */
fun assertRestores(patcher: Patcher, source: ByteArray, target: ByteArray): Int {
  val patch = patcher.createPatch(source, target)
  val info = Kiff.info(patch)
  assertEquals(patcher.id, info.patcher)
  assertEquals(source.size, info.sourceSize)
  assertEquals(target.size, info.targetSize)
  assertContentEquals(target, patcher.applyPatch(source, patch), "restored bytes differ")
  return patch.size
}

/** Pseudo-random but compressible bytes, closer to real file content than pure noise. */
fun structuredBytes(size: Int, seed: Int): ByteArray {
  val random = Random(seed)
  val result = ByteArray(size)
  var offset = 0
  while (offset < size) {
    when (random.nextInt(4)) {
      0 -> {
        val run = minOf(size - offset, random.nextInt(1, 64))
        result.fill(random.nextInt(256).toByte(), offset, offset + run)
        offset += run
      }
      1 -> {
        val phrase = "section_${random.nextInt(32)} ".encodeToByteArray()
        val count = minOf(size - offset, phrase.size)
        phrase.copyInto(result, offset, 0, count)
        offset += count
      }
      else -> {
        val chunk = minOf(size - offset, random.nextInt(1, 96))
        random.nextBytes(result, offset, offset + chunk)
        offset += chunk
      }
    }
  }
  return result
}
