# Kiff

Kotlin Multiplatform file diffing: turn two versions of a file into a patch, and rebuild the second
file byte for byte from the first one plus that patch.

## Algorithms

| Algorithm | `AlgorithmId` | Best for |
| --- | --- | --- |
| `binary` | `BINARY` | Any pair of files, treated as opaque byte streams |

Every algorithm implements the same [`PatchAlgorithm`](library/src/commonMain/kotlin/com/linroid/kiff/PatchAlgorithm.kt)
contract, writes the same self-describing patch container, and restores the target exactly:

```kotlin
val patch = Kiff.binary.createPatch(source, target)
val restored = Kiff.binary.applyPatch(source, patch)  // == target, byte for byte
```

File-level helpers pick the algorithm out of the patch itself, so restoring never needs to be told
which one produced it:

```kotlin
Kiff.createPatch(Kiff.binary, "foo-1.0.bin", "foo-1.1.bin", "foo.patch")
Kiff.applyPatch("foo-1.0.bin", "foo.patch", "foo-1.1-restored.bin")
```

## CLI

The `example` module is a small CLI over the library, installed as `kiff`:

```console
$ ./gradlew :example:installDist
$ ./example/build/install/kiff/bin/kiff create -a binary foo-1.0.apk foo-1.1.apk foo.patch
Created foo.patch with the binary algorithm
  Algorithm:   binary (format v1)
  Source:      67.4 MiB crc32=fa4b3b57
  Target:      71.7 MiB crc32=0d0e6723
  Patch:       13.7 MiB (19.19% of target)
  Took:        2450 ms

$ ./example/build/install/kiff/bin/kiff apply foo-1.0.apk foo.patch foo-1.1-restored.apk
$ ./example/build/install/kiff/bin/kiff info foo.patch
```

`kiff diff foo.txt bar.txt` prints a line-based (Myers) diff instead, for text files.

## How a patch is built

A patch is a header plus a delta stream:

```
"KIFF"  4 bytes
version 1 byte     algorithm 1 byte     flags 1 byte
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

The `binary` algorithm finds copies with a rolling hash over 16-byte blocks of the source, sampled
every 4-16 bytes depending on file size, which keeps the index small enough to diff APK-sized files
in a couple of seconds. Between copies it tracks the source offset the target is running parallel
to, and emits `DIFF` while the aligned bytes still mostly agree.

## Targets

`jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `macosX64`, `linuxX64`, `mingwX64`,
`js(nodejs, browser)`.

Algorithms need random access to both files, so they work on byte arrays: peak memory is roughly
source + target + patch + index.
