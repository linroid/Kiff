package com.linroid.kiff.io

import okio.Path

/**
 * Gives [to] the permission bits of [from], as far as the platform has them: an executable
 * replaced by a new file is still executable, and a private one still private. Owner, ACLs and
 * extended attributes are not carried over, and a failure leaves [to] as it was created.
 */
internal expect fun copyPermissions(from: Path, to: Path)

/**
 * Asks the platform to put [path] - a file, or a directory whose entries changed - on disk before
 * returning. Failures are ignored: this narrows the window in which a power loss can undo a write,
 * and no platform promises more than that.
 */
internal expect fun syncToDisk(path: Path)
