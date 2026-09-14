# Kiff

Kotlin Multiplatform file diffing: turn two versions of a file into a patch, and rebuild the second
file byte for byte from the first one plus that patch.

## Patchers

| Patcher | `PatcherId` | Best for |
| --- | --- | --- |
| `binary` | `BINARY` | Any pair of files, treated as opaque byte streams |
| `zip` | `ZIP` | Zip archives, compared entry by entry |
| `apk` | `APK` | Android packages: zip structure plus dex, native library and signing block awareness |

A patcher decides how the two files are carved into regions worth comparing: `binary` compares them
whole, `zip` and `apk` walk the archive and compare each entry against the entry it came from. What
searches the bytes inside a region is a separate concern - a *delta algorithm* - and all three
patchers drive the same one.

Every patcher implements the same [`Patcher`](library/src/commonMain/kotlin/com/linroid/kiff/Patcher.kt)
contract, writes the same self-describing patch container, and restores the target exactly:

```kotlin
val patch = Kiff.binary.createPatch(source, target)
val restored = Kiff.binary.applyPatch(source, patch)  // == target, byte for byte
```

File-level helpers pick the patcher out of the patch itself, so restoring never needs to be told
which one produced it:

```kotlin
Kiff.createPatch(Kiff.binary, "foo-1.0.bin", "foo-1.1.bin", "foo.patch")
Kiff.applyPatch("foo-1.0.bin", "foo.patch", "foo-1.1-restored.bin")
```

## CLI

The `example` module is a small CLI over the library, installed as `kiff`:

```console
$ ./gradlew :example:installDist
$ ./example/build/install/kiff/bin/kiff create -p binary foo-1.0.apk foo-1.1.apk foo.patch
Created foo.patch with the binary patcher
  Patcher:     binary (format v1)
  Source:      67.4 MiB crc32=fa4b3b57
  Target:      71.7 MiB crc32=0d0e6723
  Patch:       13.7 MiB (19.19% of target)
  Took:        2450 ms

$ ./example/build/install/kiff/bin/kiff apply foo-1.0.apk foo.patch foo-1.1-restored.apk
$ ./example/build/install/kiff/bin/kiff info foo.patch
```

`kiff changes foo-1.0.apk foo-1.1.apk` lists what differs entry by entry, without building a
patch. For an APK it also groups the entries the way the package is built:

```console
$ kiff changes foo-1.0.apk foo-1.1.apk
18.4 MiB -> 19.1 MiB
unchanged=412  metadata_changed=3  modified=11  added=2  removed=1

  native_library  6 entries, 2 changed, 11.2 MiB (+612.0 KiB)
  dex             3 entries, 2 changed, 5.4 MiB (+84.0 KiB)
  resource        402 entries, 7 changed, 1 removed, 1.9 MiB (-12.4 KiB)
  resource_table  1 entry, 1 changed, 208.1 KiB (+1.2 KiB)
  manifest        1 entry, 1 changed, 4.1 KiB (+96 B)
  signing block   8.0 KiB -> 8.0 KiB

  M lib/arm64-v8a/libfoo.so (6.1 MiB -> 6.7 MiB)
  M classes2.dex (2.6 MiB -> 2.7 MiB)
  ~ res/bar.webp
  - res/baz.png (14.2 KiB)
  + META-INF/services/qux (26 B)
```

`kiff diff foo.txt bar.txt` prints a line-based (Myers) diff instead, for text files.

## How a patch is built

A patch is a header plus a delta stream:

```
"KIFF"  4 bytes
version 1 byte     patcher 1 byte       flags 1 byte
source  varint size + CRC-32
target  varint size + CRC-32
delta   instruction stream + literal stream
```

The delta stream is a sequence of four instructions:

| | |
| --- | --- |
| `COPY` | a range from anywhere in the source |
| `DIFF` | a range that *almost* matches, as byte-wise differences from the source |
| `ADD` | literal bytes |
| `RUN` | a repeated byte |

`DIFF` is what keeps patches small for recompiled code. Long stretches of a new build are identical
to the old one except for embedded offsets, so the differences are mostly zero and collapse in the
literal stream, where the same region emitted as literals would not compress at all. Literals and
difference bytes share one stream, packed with a small built-in LZ77 codec.

The two checksums do real work: `applyPatch` refuses a source file that is not the one the patch was
built against (`KiffException.SourceMismatch`) and refuses to hand back a target whose checksum does
not match what was recorded at creation time (`KiffException.VerificationFailed`).

The delta algorithm - the search that produces those instructions - is what every patcher drives.
The one Kiff ships, `RollingHashAlgorithm`, finds copies with a rolling hash over 16-byte blocks of
the source, sampled every 4-16 bytes depending on how large a range is indexed, which keeps the
index small enough to diff APK-sized files in a couple of seconds. Between copies it tracks the
source offset the target is running parallel to, and emits `DIFF` while the aligned bytes still
mostly agree.

Because the instruction set is fixed, a different algorithm needs no change to the patch format or
to any patcher: it is an encode-side choice that the same reader decodes.

## Zip archives

`ZipDiff` reads both archives' layouts and describes the target region by region, pairing each
target entry with the source entry it came from - by name, or by content fingerprint when an entry
was renamed. An unchanged entry then costs a single copy instruction no matter how far it moved, and
a changed entry is indexed against its counterpart alone rather than against the whole archive,
which buys a finer sampling stride and far fewer hash collisions.

Regions the format does not name are described too - the preamble, alignment padding, an APK signing
block, the central directory - which is what makes the restored archive identical to the last byte.
If either input turns out not to be a readable zip, the patcher falls back to a whole-file byte
scan, so it always produces a working patch.

`ZipDiff.analyze(source, target)` reports the same pairing as data, without building a patch.

**Entry data is never recompressed.** That is what makes a restore byte-exact - re-deflating would
have to reproduce the original compressor's output bit for bit - but it also means a patch for a
*deflated* entry can only be as small as the change in its compressed bytes. Modern APKs store
their `.so`, `.dex` and `resources.arsc` entries uncompressed, so in practice the interesting
content is compared directly.

## Android packages

`ApkDiff` is the zip patcher plus what is specific to an APK:

- **Dex files are paired by ordinal.** A build that gains a dex renumbers the rest, so a
  `classes4.dex` with no counterpart by name or content is still compared against the source's
  highest-numbered dex rather than against the whole archive.
- **The signing block is a first-class region.** The unnamed run of bytes a v2+ signed APK carries
  between its last entry and the central directory is located, reproduced exactly, and reported on.
- **Entries are classified** as dex, native library, manifest, resource table, resource, asset,
  signature, metadata or other, which is what makes `ApkDiff.analyze` readable.

Storing `.so`, `.dex` and `resources.arsc` uncompressed is the norm for current Android builds - 108
of the 191 entries in the APK above - so the entries that dominate the package are compared as their
real content, not as compressed bytes.

## Benchmark

Two consecutive release builds of the same app, 67.4 MiB and 71.7 MiB, on an M-series Mac. All
three restore a byte-identical file, checked against the target's SHA-256:

| Patcher | Patch | Create | Apply |
| --- | --- | --- | --- |
| `binary` | 13.7 MiB (19.19%) | 2.5 s | 0.7 s |
| `zip` | 13.5 MiB (18.82%) | 2.3 s | 0.8 s |
| `apk` | 13.5 MiB (18.82%) | 2.2 s | 0.8 s |

The three land close together on *this* pair because ~70% of the target sits in a handful of large
entries that genuinely changed - a replaced native library, a rebuilt dex - and no amount of
structure awareness makes new content smaller. The structural patchers pull ahead when entries
move, when an archive is repacked, or when only part of it was rebuilt; `apk` and `zip` agree here
because every dex in this pair still pairs by name.

## Targets

`jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `macosX64`, `linuxX64`, `mingwX64`,
`js(nodejs, browser)`.

Patchers need random access to both files, so they work on byte arrays: peak memory is roughly
source + target + patch + index.
