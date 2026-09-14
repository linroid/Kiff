package com.linroid.kiff.io

import okio.FileSystem

internal actual val systemFileSystem: FileSystem get() = FileSystem.SYSTEM
