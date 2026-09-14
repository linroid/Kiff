package com.linroid.kiff.io

import okio.FileSystem
import okio.NodeJsFileSystem

/** Node has a file system; a browser does not, and any use of this will fail there. */
internal actual val systemFileSystem: FileSystem get() = NodeJsFileSystem
