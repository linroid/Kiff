package com.linroid.kiff.io

import okio.Path

/** Windows has no POSIX permission bits to carry over. */
internal actual fun copyPermissions(from: Path, to: Path) = Unit

/** Not attempted on Windows, where a replaced file is flushed by the move itself. */
internal actual fun syncToDisk(path: Path) = Unit
