package com.linroid.kiff

import com.linroid.kiff.container.ContainerRegistry
import com.linroid.kiff.container.DexFormat
import com.linroid.kiff.container.ZipFormat

/**
 * The formats [Kiff.createPatch] looks for by default, in the file as a whole and inside it.
 *
 * Only plain [ZipFormat] is here, not [com.linroid.kiff.container.ApkFormat]: the two detect
 * identically, and what an APK adds - pairing a renumbered `classes4.dex` with its neighbour - is
 * knowledge about the package being patched, not about an archive that happens to be bundled
 * inside one. A file that is itself a zip carrying an `AndroidManifest.xml` is taken as an APK.
 *
 * Add to it to teach Kiff a new format:
 *
 * ```kotlin
 * val options = KiffOptions(containers = NestedContainers + TarFormat())
 * ```
 *
 * Nothing about the patch format changes when you do. A tar described as its members rather than
 * as one opaque leaf is more nodes of the same four kinds, so a decoder that has never heard of
 * tar still applies the patch.
 */
val NestedContainers: ContainerRegistry = ContainerRegistry(ZipFormat(), DexFormat())
