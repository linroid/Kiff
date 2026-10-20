# Kiff

Kotlin Multiplatform file diffing: turn two versions of a file into a patch, and rebuild the second
file byte for byte from the first one plus that patch.

## Using it

The library is not published yet. It targets JVM, JS, iOS, macOS, Linux and Windows from one source
set. Run `./gradlew publishToMavenLocal` to publish it to `~/.m2` for local use.

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

The `cli` module is a small command-line front end for the library, installed as `kiff`:

```console
$ ./gradlew :cli:installDist
$ ./cli/build/install/kiff/bin/kiff create -p binary foo-1.0.apk foo-1.1.apk foo.patch
Created foo.patch with the binary patcher
  Patcher:     binary (format v1)
  Source:      67.4 MiB crc32=fa4b3b57
  Target:      71.7 MiB crc32=0d0e6723
  Patch:       13.7 MiB (19.19% of target)
  Took:        2450 ms

$ ./cli/build/install/kiff/bin/kiff apply foo-1.0.apk foo.patch foo-1.1-restored.apk
$ ./cli/build/install/kiff/bin/kiff info foo.patch
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

`kiff diff foo.txt bar.txt` prints a unified diff instead, for text files - the format `patch(1)`,
`git apply` and every review tool already read:

```console
$ kiff diff config.old config.new
--- config.old
+++ config.new
@@ -1,4 +1,5 @@
 alpha
-beta
+beta changed
 gamma
 delta
+epsilon
\ No newline at end of file
```

The files are compared as the bytes they hold, in any encoding, and the exit status is diff(1)'s:
0 when they are the same, 1 when they differ, 2 when something went wrong. Whether a file ended in a
newline survives the round trip; `UnifiedDiff.parse` and `UnifiedDiff.apply` read the format back,
refusing a hunk whose body does not match its header's line counts or whose context does not match
the source.

`kiff changes` says what differs; `kiff explain` says what the difference *cost*, which is not the
same question - a large entry can change and still be nearly free, and a small one can be expensive:

```console
$ kiff explain foo-1.0.apk foo-1.1.apk
19.1 MiB target, 421 regions
  Patch:        3.1 MiB (16.23% of target)
  Content:      3.1 MiB carried, before it is packed
  Instructions: 1.6 KiB of references
  Referenced:   408 regions the patch only points at

By kind
  dex               2.4 MiB   77.3%   3 entries, 44.4% of their 5.4 MiB
  native_library  612.0 KiB   19.2%   6 entries, 5.3% of their 11.2 MiB
  resource         82.0 KiB    2.6%   402 entries, 4.2% of their 1.9 MiB
  resource_table   18.2 KiB    0.6%   1 entry, 8.7% of their 208.1 KiB
  gaps              8.0 KiB    0.3%
  directory         1.4 KiB    0.0%

Most expensive regions
     2.3 MiB  classes2.dex (85.2% of 2.7 MiB)
   612.0 KiB  lib/arm64-v8a/libfoo.so (8.9% of 6.7 MiB)
```

A region the patch only points at still costs the instruction that points at it, so `Instructions`
is the floor an archive of mostly-unchanged entries cannot go below.

`kiff release` makes every patch a release ships in one run: one new file, patched from each earlier
release. Each patch is restored and its result held to the new file's SHA-256 before it is kept,
and one that costs more than `--max-ratio` of the file (0.8 by default) is dropped, since
downloading the whole file would cost about as much:

```console
$ kiff release --to v1.3.0=foo-1.3.0.apk \
    --from v1.2.0=foo-1.2.0.apk --from v1.0.0=foo-1.0.0.apk \
    --out patches --base-url https://example.com/foo/v1.3.0
foo-1.3.0.apk (v1.3.0), 71.7 MiB, apk patcher
  v1.2.0     4.6 MiB    6.42%  restored  foo-1.3.0.apk.from-v1.2.0.kiff
  v1.0.0    60.2 MiB   83.96%  dropped, over --max-ratio 0.8
Wrote patches/kiff-patches.json (1 patch)
  Took:        9120 ms
```

A file is given as `LABEL=FILE`, or as `FILE` to go by its name. The text before the first `=` is a
label only when it holds no path separator, so `builds/v=2/foo.apk` is a file; a file with an `=` in
its own name takes a label (`v1=a=b.apk`) or a leading `./`. `--patcher auto`, the default, picks
`apk` for a `.apk` file, `zip` for any other zip archive and `binary` for the rest. `--name` names
the patches from `{target}`, the new file's name, and the labels `{from}` and `{to}`.

The manifest is what an updater reads. For each file it lists the patches that rebuild it, with what
an updater needs to check before applying one and after:

```json
{
  "schema": 1,
  "generator": "kiff 0.1.0",
  "files": [
    {
      "name": "foo-1.3.0.apk",
      "version": "v1.3.0",
      "url": "https://example.com/foo/v1.3.0/foo-1.3.0.apk",
      "size": 75182899,
      "sha256": "5d1f…",
      "patches": [
        {
          "from": "v1.2.0",
          "source": {
            "name": "foo-1.2.0.apk",
            "size": 74121543,
            "sha256": "a07c…"
          },
          "name": "foo-1.3.0.apk.from-v1.2.0.kiff",
          "url": "https://example.com/foo/v1.3.0/foo-1.3.0.apk.from-v1.2.0.kiff",
          "size": 4827000,
          "sha256": "e912…",
          "patcher": "apk",
          "format": 1
        }
      ]
    }
  ]
}
```

An updater takes the patch whose `source.sha256` is the file it holds and whose `format` is in
`Kiff.readableFormats`, and checks what it restores against the file's own `sha256`. Running
`kiff release` again with the same manifest replaces that file's entry and keeps the others, so one
manifest can cover every file a release ships. The manifest is a contract with those updaters: a
field may be added, but anything else is a new `schema`.

The exit status is 0 when every source gave a patch or was dropped, and 1 when any could not be
created or verified. The other patches are still written, and the manifest lists only good ones.

## GitHub Action

The action at the root of this repository runs `kiff release` for a project's GitHub releases. It
patches each new release's files from the releases before it, restores every patch and checks it
against the new file's SHA-256, and attaches the patches and a `kiff-patches.json` manifest to the
release. A project that already attaches its builds to releases adds one workflow:

```yaml
name: Patches
on:
  release:
    types: [ published ]

jobs:
  patches:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: linroid/Kiff@v0.1.0
        with:
          assets: app-*.apk
```

Publishing `v1.3.0` with `app-1.3.0.apk` then attaches a patch from each of the three releases
before it, such as `app-1.3.0.apk.from-v1.2.0.kiff`, and the manifest. The job summary lists every
patch with its size, its share of the file and what became of it:

| File | From | Patch size | Share of file | Result |
| --- | --- | ---: | ---: | --- |
| `app-1.3.0.apk` | v1.2.0 | 4.6 MiB | 6.42% | restored exactly |
| `app-1.3.0.apk` | v1.1.0 | 9.8 MiB | 13.67% | restored exactly |
| `app-1.3.0.apk` | v1.0.0 | 60.2 MiB | 83.96% | dropped, over max-ratio 0.8 |

The action runs on Linux and macOS runners, with bash, `gh` and `jq`, which hosted runners have. It
installs Java 17 when the runner has no Java 17 or newer. kiff lets the JVM use up to 6 GiB, which
fits hosted runners; on a smaller machine, set `KIFF_OPTS` to something like `-Xmx2g`.

### Attach the patches before publishing

A release that is already published can be seen by an updater before its patches arrive. Creating it
as a draft, running the action, and only then publishing avoids that, and works however the release
is built:

```yaml
on:
  push:
    tags: [ 'v*' ]

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    env:
      GH_TOKEN: ${{ github.token }}
    steps:
      - uses: actions/checkout@v7
      - run: ./gradlew assembleRelease
      - run: gh release create "$GITHUB_REF_NAME" --draft app/build/outputs/apk/release/app-*.apk
      - uses: linroid/Kiff@v0.1.0
        with:
          assets: app-*.apk
      - run: gh release edit "$GITHUB_REF_NAME" --draft=false
```

This is the recommended setup. The `release: published` workflow above suits releases published by
hand, but it never runs for a release that another workflow published with the default
`GITHUB_TOKEN`: GitHub starts no workflow for events that token causes. Publish with a GitHub App or
personal token instead, or use the draft recipe.

### Earlier releases and several files

To patch a release that already exists, run the action by hand with its tag:

```yaml
on:
  workflow_dispatch:
    inputs:
      tag:
        required: true

jobs:
  patches:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: linroid/Kiff@v0.1.0
        with:
          tag: ${{ inputs.tag }}
          assets: |
            app-*-arm64-v8a.apk
            app-*-x86_64.apk
            desktop-*-linux-x64.zip
```

Each line of `assets` is a glob for one file. It must match exactly one file of the release, and at
most one of each earlier release; a pattern that matches more fails the run and names what it
matched. `*.kiff` files and the manifest are never matched.

Kiff never recompresses an archive's entries, which is what keeps a restore byte-exact. A file
compressed as a whole, such as a `.dmg`, a `.tar.gz` or a compressed installer, changes almost
everywhere between builds, so its patches come out near full size and `max-ratio` drops them. APKs
and zips with stored entries are where patches pay off.

### Inputs and outputs

| Input | Default | Meaning |
| --- | --- | --- |
| `assets` | required | Glob patterns for the files to patch, one per line |
| `tag` | the event's | The release to patch, which may be a draft: the `release` event's tag, else the pushed tag |
| `previous` | `3` | How many earlier releases to patch from |
| `from` | | Tags to patch from, one per line, in place of `previous` |
| `prereleases` | `false` | Whether prereleases can be patched from |
| `patcher` | `auto` | `auto`, `binary`, `zip` or `apk`, as for `kiff release` |
| `max-ratio` | `0.8` | Drop a patch larger than this share of its file |
| `name` | `{target}.from-{from}.kiff` | Patch name: `{target}` is the file's name, `{from}` and `{to}` the tags |
| `manifest` | `kiff-patches.json` | Manifest name; give each workflow its own if several patch one release |
| `upload` | `true` | Attach the patches and manifest to the release |
| `repository` | this one | The repository whose releases are read and attached to |
| `token` | `github.token` | Needs `contents: write` to attach, read access with `upload: false` |

The earlier releases are the closest ones before the new tag in version order, with a leading `v`
ignored and Semantic Versioning's precedence, so `1.4.0-rc.1` comes before `1.4.0`; a tag that is
not a version is ordered naturally. A hotfix `v1.2.5` published after `v1.3.0` is patched from
`v1.2.4` and below, never from `v1.3.0`. A draft is never picked, and a release without the file is
passed over without using up one of `previous`. `from` takes exactly the tags it lists, and
only warns about one that does not have the file.

The outputs are `directory`, which holds the patches and the manifest, `manifest`, its path,
`patches`, a JSON array with the `file`, `from`, `name`, `url`, `size` and `ratio` of each patch,
and `count`. With `upload: false` nothing is attached, so a later step can put the files on S3 or a
CDN instead. A project that does not use GitHub releases at all can run `kiff release` itself, with
`--base-url` set to where its files are served.

### Versions

The action and the CLI are released together, so `linroid/Kiff@v0.1.0` runs kiff 0.1.0, downloaded
from that release and checked against its checksum. Any other ref, such as a branch or a commit,
builds the CLI from the action's own source first, which adds a few minutes. Kiff has not been
released yet, so until v0.1.0 is out, pin a commit of `main`.

Pin an exact version or commit, not a branch, and move it together with the Kiff that your app uses.
Every patch records the version of the patch format it was written in, and the Kiff inside an app
refuses a format newer than it knows. An action ahead of the app's Kiff can write patches that no
installed copy of the app can read, so it should never be ahead of the oldest app version you still
update.

### Updating from the manifest

Every release carries its own manifest, and GitHub serves the newest published one at a fixed
address, which skips drafts and prereleases:

```text
https://github.com/OWNER/REPO/releases/latest/download/kiff-patches.json
```

An updater fetches it and finds the entry for its file. It takes the patch whose `source.sha256` is
the file it has installed and whose `format` is in `Kiff.readableFormats`, downloads that patch and
checks its `sha256`, applies it, and checks the result against the file's own `sha256`. When there
is no such patch, because the installed version is too old, was dropped for size or the release has
no manifest, it downloads the full file from `url` instead.


## How a patch is built

[`FORMAT.md`](FORMAT.md) is the full specification, and `FormatVectorsTest` is what keeps it honest:
patches frozen at this version of the format, with the inputs they were built from, asserting only
that they still apply. Prose cannot fail; those can.


A patch is a header, a tree of regions, and one stream of content:

```
"KIFF"  4 bytes
version 1 byte     patcher 1 byte       flags 1 byte
source  varint size + CRC-32
target  varint size + CRC-32
tree    region tree, each region checksummed
content literals, packed with a built-in LZ77 + Huffman codec
```

Sizes and the source offsets inside the tree are 64-bit varints, so a patch can describe inputs
beyond 2 GB.

Each node of the tree covers a run of target bytes and says how it is described:

| | |
| --- | --- |
| `COMPOSITE` | children, tiling this region in order |
| `DELTA` | a byte-level instruction stream |
| `TEXT` | a line-level edit script |
| `COLUMNS` | a table of fixed-width rows, read as columns of differences |
| `RAW` | the bytes verbatim |

Nesting is what lets a region be described on its own terms. A zip is a composite of its entries,
gaps and directory; a changed entry is a composite of its header, its data and any descriptor, so
the *data* can pick an encoding without the header bytes around it having a say. Adding a container
format later - treating a dex as sections rather than as one opaque leaf - adds nodes to the tree
and changes neither the node set nor the reader.

## Teaching Kiff a new container

A container is taken apart by a `ContainerFormat`, and that is the whole extension point:

```kotlin
class TarFormat : ContainerFormat {
  override val name = "tar"
  override fun detect(bytes: ByteArray, from: Int, to: Int) = /* magic */
  override fun decompose(bytes: ByteArray, from: Int, to: Int): List<Child> = /* members */
}

val patcher = ApkPatcher(containers = NestedContainers + TarFormat())
```

`NestedContainers` already holds `ZipFormat` and `DexFormat`, so an archive inside an archive, and
a dex inside either, are taken apart without being asked.

Nothing else moves. The patch format does not change, because a decomposed region is more nodes of
the same four kinds; the reader does not change, because a patch records the structure it used
rather than asking the reader to work it out; and patches written before the format existed still
apply. A dex is an opaque leaf today and its sections tomorrow, with no format version in between.

A format can also declare that some children are **interchangeable**, by giving them a shared
`group`. Pairing decides which source child a target child is described *against*; a group decides
which source bytes it can be *found in*, and those differ whenever a format moves content between
siblings. Android does exactly that: classes are split across `classes.dex`, `classes2.dex` and so
on, and a rebuild repartitions them, so a class can change file while changing nothing else. On two
real builds the split went from 3.4 MB + 7.2 MB to 5.5 MB + 5.1 MB. `ApkFormat` puts every dex in
one group, and the members are indexed together rather than one at a time.

**The one rule**: children must tile their parent exactly, gaps included. Everything the format does
not name - a preamble, alignment padding, an APK signing block, a trailer - is still a child, just
an unnamed one. A patch restores byte for byte only because every byte belongs to something.
`ContainerFormatTiling.describe(format, bytes)` asserts this; call it from your tests.

Decomposing is weighed, not assumed. A nested region is encoded both ways and the bigger result is
discarded, so a format that does not pay off costs encode time and never patch size. That matters
more than it sounds: a byte search that copies from anywhere in the source is already good at
finding moved and edited content, so on *stored* content today, decomposing mostly breaks even. It
will earn its keep on compressed children, which cannot be compared at all without being decoded.

`COLUMNS` is what a format's knowledge is worth. A table of ids is the renumbering problem in its
purest form: every entry of a dex's `string_ids` is an offset into the file, so inserting one string
shifts all of them and no matching run survives. The *gaps* between those offsets are the string
lengths, and those barely change. Reading the table column by column as differences turns shifting
absolutes into stable gaps. Only the format knows a region is a table, so a `Child` declares its row
layout; the rearrangement itself is exact in both directions, so it risks nothing.

`DexFormat` is a worked example, and an honest one. A dex is mostly tables of offsets into itself,
so adding a method renumbers everything after it and a byte search finds nothing to match: on a real
pair of builds the two dex files account for 98% of the patch while costing 90% of their own size.
Splitting them by section, which their map list makes nearly free, recovers about 0.3% on its own.
Section boundaries are not the fix for renumbering - they are what a fix is built on. Declaring the
row layout of the sections that are tables is the fix, and takes the same pair from 7.5% of the
target to 6.5%.

Every leaf is chosen by measurement, not by guess. Whatever a `RegionPlanner` nominates, the encoder
builds it, builds the byte-level encoding too, and keeps whichever packs smaller - and keeps neither
if storing the bytes outright would have been smaller still. A planner can therefore only ever cost
encode time, never patch size, which is what makes a speculative new encoding safe to try.

Leaves share a single content stream rather than each packing their own, so a deep tree costs
structure but never costs compression. Regions that are nothing but a copy from consecutive source
bytes merge into one, so an archive of untouched entries does not pay per entry to say so.

The delta instruction stream is a sequence of four instructions:

| | |
| --- | --- |
| `COPY` | a range from anywhere in the source |
| `DIFF` | a range that *almost* matches, as byte-wise differences from the source |
| `ADD` | literal bytes |
| `RUN` | a repeated byte |

`DIFF` is what keeps patches small for recompiled code. Long stretches of a new build are identical
to the old one except for embedded offsets, so the differences are mostly zero and collapse in the
content stream, where the same region emitted as literals would not compress at all.

`TEXT` splits a region into lines that carry their own terminators, so concatenating them reproduces
the bytes exactly - a trailing newline, or its absence, survives. It wins where byte matches
fragment and loses where they do not, which is why it competes rather than being chosen.

Every region that produces target bytes also carries a CRC-32 of them, which costs about 0.005% of
a patch and is what turns "this did not restore" into "this region did not restore". On a package of
seventy megabytes that is the difference between a bug you can find and one you can only stare at.
A flag in the header says whether they are present, and a flag this build does not recognise is
refused rather than guessed at.

The two whole-file checksums do real work too: `applyPatch` refuses a source file that is not the one the patch was
built against (`KiffException.SourceMismatch`) and refuses to hand back a target whose checksum does
not match what was recorded at creation time (`KiffException.VerificationFailed`).

The delta algorithm - the search that produces those instructions - is what every patcher drives.
The one Kiff ships, `RollingHashAlgorithm`, finds copies with a rolling hash over 16-byte blocks of
the source, sampled every 4-16 bytes depending on how large a range is indexed, which keeps the
index small enough to diff APK-sized files in a couple of seconds. Between copies it tracks the
source offset the target is running parallel to, and emits `DIFF` while the aligned bytes still
mostly agree.

Because the instruction set is fixed, a different algorithm needs no change to the patch format or
to any patcher: it is an encode-side choice that the same reader decodes. That is what makes the
search pluggable.

## Plugging in your own algorithm

`DeltaAlgorithm` is public. Implement it to change how bytes are matched, and hand it to any
patcher:

```kotlin
object MyAlgorithm : DeltaAlgorithm {
  override val name = "mine"
  override fun scanner(source: SeekableSource, from: Long, to: Long) = object : DeltaScanner {
    override fun scan(
      target: SeekableSource, from: Long, to: Long, sink: DeltaSink, initialAlignment: Long
    ) {
      // describe target[from, to) with sink.copy / diff / add / run
    }
  }
}

val patch = BinaryPatcher(MyAlgorithm).createPatch(source, target)
val restored = Kiff.binary.applyPatch(source, patch)  // the stock patcher reads it back
```

The algorithm is not recorded in the patch and nothing needs to know it ran: the regions a scanner
reports must simply tile the target exactly. An algorithm prepares a scanner over a source range
once, and a patcher reuses that scanner across many target regions, so the index is built one time
per range rather than once per region.

## Inputs are addressed, not streamed

A delta is not a sequential transform. A `COPY` names an arbitrary source offset and applying one
reads that offset back, so Kiff takes a `SeekableSource` - random access, 64-bit positions - rather
than a stream:

```kotlin
interface SeekableSource : AutoCloseable {
  val size: Long
  fun read(position: Long, into: ByteArray, offset: Int = 0, length: Int = into.size - offset): Int
}
```

`ByteArray.asSource()` wraps bytes already in memory; `fileSource(path)` opens a file for random
access on every target that has a file system (browser JS does not). Checksums are taken straight
off a source, a chunk at a time, so a file is never read whole for them. The `ByteArray` overloads
on `Patcher` are conveniences over `ByteArraySource`.

Applying a patch never holds either file. A patch describes its target front to back, so the
restore is written out as it is produced, and the source is addressed rather than read in:

```kotlin
Kiff.apk.applyPatch(sourceFile, patch, target)   // target is a RestoreTarget
```

A streamed restore can only be checked as it goes, so a target receives bytes before they are
verified: write them somewhere you can discard if `applyPatch` throws. `Kiff.applyPatch` with paths
does that for a file, moving it into place only once it verified.

That is the difference between a restore a build server can do and one a phone can. Applying a
4.6 MiB patch to a 71.8 MiB package needs about 32 MB of heap this way, against something over 170
when both files are held - and what is left is the patch itself, not the files.

File access is [okio](https://square.github.io/okio/), whose `FileHandle` gives 64-bit random
access from common code on every target Kiff builds for. Paths are plain `String` throughout the
public API and okio is an implementation detail: no okio type appears in a public signature, and it
is not on a consumer's compile classpath. Only reaching a `FileSystem` is per-platform, because on
JS it ships in a separate artifact.

**Current limit:** the bundled `RollingHashAlgorithm` still indexes a `ByteArray`, so a patcher
materializes both inputs once before encoding and refuses an input above 2 GB with
`KiffException.UnsupportedInput`. The format and the algorithm contract address more than that; a
bounded-memory search is the next piece of work, and it needs no format change when it lands.

## Zip archives

`ZipPatcher` reads both archives' layouts and describes the target region by region, pairing each
target entry with the source entry it came from - by name, or by content fingerprint when an entry
was renamed. An unchanged entry then costs a single copy instruction no matter how far it moved, and
a changed entry is indexed against its counterpart alone rather than against the whole archive,
which buys a finer sampling stride and far fewer hash collisions.

Regions the format does not name are described too - the preamble, alignment padding, an APK signing
block, the central directory - which is what makes the restored archive identical to the last byte.
If either input turns out not to be a readable zip, the patcher falls back to a whole-file byte
scan, so it always produces a working patch.

`ZipPatcher.analyze(source, target)` reports the same pairing as data, without building a patch.

**Entry data is never recompressed.** That is what makes a restore byte-exact - re-deflating would
have to reproduce the original compressor's output bit for bit - but it also means a patch for a
*deflated* entry can only be as small as the change in its compressed bytes. Modern APKs store
their `.so`, `.dex` and `resources.arsc` entries uncompressed, so in practice the interesting
content is compared directly.

## Android packages

`ApkPatcher` is the zip patcher plus what is specific to an APK:

- **Dex files are paired by ordinal.** A build that gains a dex renumbers the rest, so a
  `classes4.dex` with no counterpart by name or content is still compared against the source's
  highest-numbered dex rather than against the whole archive.
- **The signing block is a first-class region.** The unnamed run of bytes a v2+ signed APK carries
  between its last entry and the central directory is located, reproduced exactly, and reported on.
- **Entries are classified** as dex, native library, manifest, resource table, resource, asset,
  signature, metadata or other, which is what makes `ApkPatcher.analyze` readable.

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

## Layout

Everything a caller needs is in `com.linroid.kiff`; the subpackages are the pieces behind it.

| Package | Holds |
| --- | --- |
| `com.linroid.kiff` | `Kiff`, `Patcher`, `PatcherId`, `PatchInfo`, `KiffException`, and the three patchers |
| `.delta` | the algorithm seam - `DeltaAlgorithm`, `DeltaScanner`, `DeltaSink` - plus the bundled rolling-hash search and the instruction codec |
| `.io` | `SeekableSource`, `ByteArraySource`, `fileSource`, whole-file helpers |
| `.format` | the patch container and its primitives: varint I/O, CRC-32, LZSS |
| `.zip` / `.apk` | archive parsing and region encoding, each with the report type its patcher returns |
| `.text` | the line-based Myers diff, which is not part of the patch pipeline |

## Targets

`jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `macosX64`, `linuxX64`, `mingwX64`,
`js(nodejs, browser)`.

Patchers address both files through `SeekableSource` rather than reading them as streams, because a
delta copies from arbitrary source offsets. Until the bundled search is converted to bounded memory,
peak memory is still roughly source + target + patch + index.

## Releasing

Between releases `main` carries the next version with a `-SNAPSHOT` suffix. To release `0.1.0`:

1. Set `version=0.1.0` in `gradle.properties` and land that on `main`.
2. Tag that commit `v0.1.0` and push the tag.
3. The [release workflow](.github/workflows/release.yml) builds the CLI and attaches
   `kiff-0.1.0.zip` and `kiff-0.1.0.zip.sha256` to a draft release for the tag. It refuses a tag
   that disagrees with `gradle.properties`, and can be re-run by hand for an existing tag.
4. Review the draft and publish it.
5. Bump `main` to the next snapshot, such as `version=0.1.1-SNAPSHOT`.

## License

Apache 2.0. See [LICENSE](LICENSE).
