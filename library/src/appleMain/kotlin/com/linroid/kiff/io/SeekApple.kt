package com.linroid.kiff.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix.fseek

/** C `long` is 64-bit on Darwin, so one call reaches any position. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun seekTo(file: CPointer<FILE>, position: Long) {
  fseek(file, position.convert(), SEEK_SET)
}
