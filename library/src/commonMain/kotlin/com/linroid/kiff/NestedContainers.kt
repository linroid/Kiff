package com.linroid.kiff

import com.linroid.kiff.container.ContainerRegistry
import com.linroid.kiff.container.ZipFormat

/**
 * What the bundled patchers look for *inside* the file they are given.
 *
 * Only plain [ZipFormat] is here, not [com.linroid.kiff.container.ApkFormat]: the two detect
 * identically, and what an APK adds - pairing a renumbered `classes4.dex` with its neighbour - is
 * knowledge about the package being patched, not about an archive that happens to be bundled
 * inside one.
 *
 * Add to it to teach a patcher a new format:
 *
 * ```kotlin
 * val patcher = ZipPatcher(containers = NestedContainers + DexFormat())
 * ```
 *
 * Nothing about the patch format changes when you do. A dex described as its sections rather than
 * as one opaque leaf is more nodes of the same four kinds, so a decoder that has never heard of
 * dex still applies the patch.
 */
val NestedContainers: ContainerRegistry = ContainerRegistry(ZipFormat())
