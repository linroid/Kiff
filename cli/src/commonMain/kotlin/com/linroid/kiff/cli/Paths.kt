package com.linroid.kiff.cli

import okio.Path
import okio.Path.Companion.toPath

/**
 * Whether [path] names a place from a root rather than from the working directory: `/` on Linux and
 * macOS; on Windows a drive, as in `C:\` or `C:/`, or a share, as in `\\server\share`.
 */
internal fun isAbsolutePath(path: String, windows: Boolean = isWindows): Boolean {
  if (!windows) return path.startsWith("/")
  val drive = path.length >= 3 && path[0].uppercaseChar() in 'A'..'Z' && path[1] == ':' &&
    path[2].isSlash()
  val share = path.length >= 2 && path[0].isSlash() && path[1].isSlash()
  return drive || share
}

/**
 * [path] as okio should read it here. Windows takes either slash, but okio reads `C:/` as relative
 * and `//server` as a single root unless they are written with its own, so on Windows that is what
 * every slash becomes.
 */
internal fun platformPath(path: String, windows: Boolean = isWindows): Path =
  (if (windows) path.replace('/', '\\') else path).toPath()

/** [path] from the root, with no `.` or `..` left in it, so two spellings of one file compare. */
internal fun absolute(path: String): Path {
  val given = platformPath(path)
  if (isAbsolutePath(path)) return given.normalized()
  return (systemFileSystem.canonicalize(".".toPath()) / given).normalized()
}

private fun Char.isSlash() = this == '/' || this == '\\'
