package com.linroid.kiff.io

import okio.Path

// Node's fs, reached the way okio's own Node file system reaches it. A browser has none, and every
// file operation fails there before these are called.
private fun fs(): dynamic = js("require('fs')")

internal actual fun copyPermissions(from: Path, to: Path) {
  try {
    val fs = fs()
    val mode = (fs.statSync(from.toString()).mode as Number).toInt()
    fs.chmodSync(to.toString(), mode and 0xFFF)
  } catch (e: Throwable) {
    // Best effort: the file keeps the permissions it was created with.
  }
}

internal actual fun syncToDisk(path: Path) {
  try {
    val fs = fs()
    val descriptor = fs.openSync(path.toString(), "r")
    try {
      fs.fsyncSync(descriptor)
    } finally {
      fs.closeSync(descriptor)
    }
  } catch (e: Throwable) {
    // Best effort, as on every platform.
  }
}
