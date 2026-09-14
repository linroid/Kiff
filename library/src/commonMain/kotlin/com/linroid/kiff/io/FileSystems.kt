package com.linroid.kiff.io

import okio.FileSystem

/**
 * The platform's file system.
 *
 * okio's `FileHandle` - the random access [SeekableSource] needs - is common to every target, but
 * reaching a `FileSystem` is not: on JS it ships in a separate artifact, so this is the one thing
 * that stays per-platform.
 */
internal expect val systemFileSystem: FileSystem
