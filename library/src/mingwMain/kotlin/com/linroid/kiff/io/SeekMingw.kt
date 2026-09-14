package com.linroid.kiff.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.FILE
import platform.posix.SEEK_CUR
import platform.posix.SEEK_SET
import platform.posix.fseek

/**
 * C `long` is 32-bit on Windows, so a position past 2 GB cannot be passed in one call: rewind, then
 * walk forward in 1 GB steps. A 100 GB file costs a hundred calls, which is nothing next to the
 * read that follows.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun seekTo(file: CPointer<FILE>, position: Long) {
  fseek(file, 0.convert(), SEEK_SET)
  var remaining = position
  while (remaining > 0) {
    val step = minOf(remaining, STEP).toInt()
    fseek(file, step.convert(), SEEK_CUR)
    remaining -= step
  }
}

private const val STEP = 1L shl 30
