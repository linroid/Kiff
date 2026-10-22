# Kiff

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fcom%2Flinroid%2Fkiff%2Fkiff%2Fmaven-metadata.xml&label=Maven%20Central)](https://central.sonatype.com/artifact/com.linroid.kiff/kiff)
[![Release](https://img.shields.io/github/v/release/linroid/kiff)](https://github.com/linroid/kiff/releases/latest)
[![Tests](https://github.com/linroid/kiff/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/linroid/kiff/actions/workflows/tests.yml)
[![Kotlin](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Flinroid%2Fkiff%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.kotlin&label=Kotlin&logo=kotlin)](gradle/libs.versions.toml)
![Platforms](https://img.shields.io/badge/platforms-JVM%20%7C%20JS%20%7C%20iOS%20%7C%20macOS%20%7C%20Linux%20%7C%20Windows-informational)
[![License](https://img.shields.io/github/license/linroid/kiff)](LICENSE)

Kotlin Multiplatform file diffing: turn two versions of a file into a patch, and rebuild the second
file byte for byte from the first one plus that patch.

## Getting started

### Using the library

Kiff is on Maven Central as `com.linroid.kiff:kiff`. Below, `<version>` is the latest release, which
the Maven Central badge above shows. It targets JVM, JS, iOS, macOS, Linux and Windows from one
source set, so a multiplatform project adds it to `commonMain`:

```kotlin
kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation("com.linroid.kiff:kiff:<version>")
    }
  }
}
```

A JVM or Android project adds it to `dependencies`:

```kotlin
dependencies {
  implementation("com.linroid.kiff:kiff:<version>")
}
```

With a version catalog, that is an entry in `gradle/libs.versions.toml`, and
`implementation(libs.kiff)`:

```toml
[versions]
kiff = "<version>"

[libraries]
kiff = { module = "com.linroid.kiff:kiff", version.ref = "kiff" }
```

Gradle picks the artifact each target needs, such as `kiff-jvm` or `kiff-iosarm64`, from the module
metadata `kiff` publishes. Maven reads no such metadata, so a Maven project depends on
`com.linroid.kiff:kiff-jvm` instead.

An Android app gets `kiff-jvm`. Creating and applying patches, `fileSource` and reading files work
at any API level, but writing a file needs API 26: `Kiff.createPatch` and `Kiff.applyPatch` with
paths, and `KiffFiles.writeBytes` and `writeStreaming`, sync what they write and keep its
permissions through `java.nio.file`. An app whose `minSdk` is lower enables core library
desugaring with the `nio` desugared library, which needs AGP 7.4 or newer:

```kotlin
android {
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
  }
}

dependencies {
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:<desugar version>")
}
```

Or it restores into a `RestoreTarget` of its own, which takes no file API from Kiff at all.

The artifact is Java 17 bytecode, which D8 turns into dex down to API 21, built with the Kotlin
version the badge above shows. That version is the tighter limit: an app compiles against it with
Kotlin at most one minor version older, and a minified build needs an R8 that reads its metadata,
which [Google's table](https://developer.android.com/build/kotlin-support) lists.

A patch rebuilds one file from another:

```kotlin
val patch = Kiff.createPatch(source, target)
val restored = Kiff.applyPatch(source, patch)  // == target, byte for byte
```

[Creating and applying a patch](#creating-and-applying-a-patch) goes on from there.

### Installing the CLI

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/kiff/main/install.sh | bash
```

That installs the latest release's executable for your machine into `~/.kiff` and links `kiff`
into `/usr/local/bin`. It is one file, a download of about 1 to 1.5 MB, on Linux or macOS on x64 or
arm64, and needs nothing else. Give bash `KIFF_VERSION` to pin a release, as in
`| KIFF_VERSION=X.Y.Z bash`, and `KIFF_INSTALL` to put the link somewhere else, such as
`$HOME/.local/bin`. A release without an executable for your machine, such as 0.1.0, which has none,
is installed from its JVM zip instead, which runs on Java 17 or newer; the script says so, and does
not install Java.

Without the script, download the archive for your platform from the
[latest release](https://github.com/linroid/kiff/releases/latest):
`kiff-<version>-linux-x64.tar.gz`, `kiff-<version>-linux-arm64.tar.gz`,
`kiff-<version>-macos-arm64.tar.gz`, `kiff-<version>-macos-x64.tar.gz` or
`kiff-<version>-windows-x64.zip`. Each holds a `kiff-<version>-<platform>` directory with `kiff`
(`kiff.exe` for Windows), `LICENSE` and `README.md`. Check it against the `.sha256` beside it,
unpack it with `tar -xzf`, or on Windows with `tar -xf` or Explorer, and put that `kiff` on your
`PATH`. The `.tar.gz` keeps `kiff` executable. macOS refuses a file a browser downloaded until
`xattr -d com.apple.quarantine kiff` clears the flag, which curl does not set. On any other
platform, download `kiff-<version>.zip`, the JVM build, unzip it, and run `bin/kiff` with Java 17 or
newer. Kiff 0.2.0 attached each executable bare, as `kiff-0.2.0-<platform>`, with `.exe` for
Windows.

To uninstall, remove the `kiff` link and `~/.kiff`.

`kiff --use X` runs a command with another released Kiff, downloaded on first use: see
[Running another version](#running-another-version). The [CLI reference](#cli) has every command.

### Setting up the GitHub Action

The action patches each new release of a project from the releases before it, and attaches the
patches with a manifest that the app's updater reads.

1. **Attach each build to its release**, with its version in its name, such as `app-1.3.0.apk`. The
   action patches files a release already carries.
2. **Add `.github/workflows/patches.yml`:**

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
         - uses: linroid/kiff@v1
           with:
             assets: app-*.apk
             kiff-version: X.Y.Z
   ```

3. **Choose the versions.** `@v1` is the easy choice: it runs the latest Kiff 1.x release. For the
   strict choice, pin the action to a release, `@vX.Y.Z`, or the commit SHA it names, so it changes
   only when you change it. Either way, set `kiff-version` to the oldest Kiff embedded in an app
   build you still support, since that Kiff applies the patches; for an app that first embeds Kiff
   now, that is the latest release, which the Release badge above shows. [Versions](#versions) says
   why.
4. **Publish a release.** The action attaches a patch from each of the three releases before it,
   and `kiff-patches.json`, and its job summary lists every patch. A release that another workflow
   publishes with the default `GITHUB_TOKEN` starts no `release` workflow, so a release workflow
   uses the [draft-first recipe](#attach-the-patches-before-publishing) instead, which also keeps
   updaters from seeing a release before its patches.
5. **Update from the manifest in the app.** Add [the library](#using-the-library) and read
   `https://github.com/OWNER/REPO/releases/latest/download/kiff-patches.json`. Take the patch whose
   `source.sha256` is the installed file's SHA-256, download it, apply it, and check the result
   against the file's `sha256`. [Updating from the manifest](#updating-from-the-manifest) has every
   check.

The [action reference](#github-action) has every input and output, more recipes, and how the action
picks the Kiff it runs.

## Creating and applying a patch

```kotlin
val patch = Kiff.createPatch(source, target)
val restored = Kiff.applyPatch(source, patch)  // == target, byte for byte
```

The same with paths writes the patch, and the restored file, to disk:

```kotlin
Kiff.createPatch("foo-1.0.apk", "foo-1.1.apk", "foo.kiff")
Kiff.applyPatch("foo-1.0.apk", "foo.kiff", "foo-1.1-restored.apk")
```

### How structure is detected

`createPatch` looks at what the target is as a whole and describes it accordingly:

| The target is | It is described | The header records |
| --- | --- | --- |
| a zip that carries an `AndroidManifest.xml` | as an APK: entry by entry, with its dex files searched together and paired by ordinal | `apk` |
| any other zip whose directory reads | entry by entry, each against the entry it came from | `zip` |
| anything else | as plain bytes: copies from anywhere in the source, plus what is new | `binary` |

Inside an archive, a zip or a dex is found wherever it sits, and taken apart when that makes the
patch smaller. When the source cannot be taken apart the way the target is, such as an APK patched
from a file that is no archive, the target is described whole.

Applying needs none of this. A patch records the structure it was built with, so `applyPatch`
applies every patch the same way. The header's `patcher` byte, read back as `PatchInfo.patcher`,
says what the writer took the target to be. It is informational, and it is the code Kiff 0.1.0's
patcher for that kind of file wrote.

What can be changed is in `KiffOptions`: the delta `algorithm`, the `planner` that picks an encoding
for each region of an archive, the `lineAlgorithm` behind text regions, and `containers`, the
formats looked for in the file as a whole and inside it. An empty registry describes every file as
plain bytes:

```kotlin
Kiff.createPatch(source, target, KiffOptions(containers = ContainerRegistry.Empty))
```

`KiffReports` answers questions about two files without keeping a patch: `zip` and `apk` list what
changed entry by entry, and `explain` and `explainApk` say where the bytes of the patch
`createPatch` would build go.

## CLI

The `cli` module is a small command-line front end for the library, installed as `kiff` (see
[Installing the CLI](#installing-the-cli)):

```console
$ kiff create foo-1.0.apk foo-1.1.apk foo.patch
Created foo.patch
  Read as:     apk (format v1)
  Source:      67.4 MiB crc32=fa4b3b57
  Target:      71.7 MiB crc32=0d0e6723
  Patch:       13.5 MiB (18.82% of target)
  Took:        2200 ms

$ kiff apply foo-1.0.apk foo.patch foo-1.1-restored.apk
$ kiff info foo.patch
```

`create` reads each file the way [the library](#how-structure-is-detected) does.

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
foo-1.3.0.apk (v1.3.0), 71.7 MiB
  v1.2.0     4.6 MiB    6.42%  restored  foo-1.3.0.apk.from-v1.2.0.kiff
  v1.0.0    60.2 MiB   83.96%  dropped, over --max-ratio 0.8
Wrote patches/kiff-patches.json (1 patch)
  Took:        9120 ms
```

A file is given as `LABEL=FILE`, or as `FILE` to go by its name. The text before the first `=` is a
label only when it holds no path separator, so `builds/v=2/foo.apk` is a file; a file with an `=` in
its own name takes a label (`v1=a=b.apk`) or a leading `./`. Each patch is built the way `kiff
create` builds it, from what the new file is. `--name` names the patches from `{target}`, the new
file's name, and the labels `{from}` and `{to}`.

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

A patch's `patcher` is what its header records, `apk`, `zip` or `binary`, and is for display. An
updater takes the patch whose `source.sha256` is the file it holds and whose `format` is in
`Kiff.readableFormats`, and checks what it restores against the file's own `sha256`. Running
`kiff release` again with the same manifest replaces that file's entry and keeps the others, so one
manifest can cover every file a release ships. The manifest is a contract with those updaters: a
field may be added, but anything else is a new `schema`.

The exit status is 0 when every source gave a patch or was dropped, and 1 when any could not be
created or verified. The other patches are still written, and the manifest lists only good ones.

### Running another version

`--use VERSION`, or `--use=VERSION`, runs the command after it with that release of Kiff instead
of the one you started:

```console
$ kiff --use 0.1.0 create -p apk old.apk new.apk patch.kiff
Downloading kiff 0.1.0 from https://github.com/linroid/kiff/releases/download/v0.1.0/kiff-0.1.0.zip
Created patch.kiff with the apk patcher
  Patcher:     apk (format v1)
  ...
```

It has to be the first argument. Anywhere later, `--use` belongs to the command and is passed on
as it is.

A patch is applied on the user's device, by the Kiff inside the app they already have, and that
Kiff refuses a format newer than it knows. So build patches with the oldest Kiff still embedded in a
version of the app you update. A newer Kiff reads every patch an older one wrote, which
`FormatVectorsTest` holds each build to with patches frozen in the released format, so a patch from
the oldest one applies on every later app as well.

The first run of a version downloads it from its GitHub release with `curl`, which has to be
installed. It takes the executable built for this machine, with `PLATFORM` one of `linux-x64`,
`linux-arm64`, `macos-arm64`, `macos-x64` and `windows-x64`: from its archive,
`kiff-VERSION-PLATFORM.tar.gz`, unpacked with the system's `tar`, or `kiff-VERSION-windows-x64.zip`;
failing that, bare, as 0.2.0 attached it, `kiff-VERSION-PLATFORM` (`.exe` for Windows); and
otherwise `kiff-VERSION.zip`, the JVM build, as for 0.1.0, which has no other. Whichever it takes
is refused unless it matches the `.sha256` beside it, as is an archive with anything outside its
`kiff-VERSION-PLATFORM` directory, and the executable is installed as
`$KIFF_HOME/versions/VERSION/bin/kiff`, with `KIFF_HOME` at `~/.kiff` unless set. It is put
together beside that directory and renamed into place, so an interrupted or concurrent run never
leaves half a version there, and every later run starts the installed copy without the network.
`KIFF_DOWNLOAD_URL` replaces `https://github.com/linroid/kiff/releases/download` for a mirror,
which serves the same files under `vVERSION/`. A draft release cannot be downloaded until it is
published, and a `-SNAPSHOT` version is refused, since no snapshot has a release. Kiff 0.2.0's own
`--use` knows only bare executables, so it runs any later release from its JVM zip, which needs
Java 17 or newer.

Only the `kiff` you start needs this feature. Everything after the version goes to the other one
unread, so that can be any release, even one older than `--use`, with whatever commands and flags
it has. On Linux and macOS it takes the place of the `kiff` you started, so its exit status and
signals are your own; elsewhere its exit status is returned as it is. Progress goes to standard
error, so `kiff --use 0.1.0 diff a b | patch` still works. A JVM build runs on Java 17 or newer:
the one in `JAVA_HOME`, or `java` on the `PATH`, and a `kiff` that is itself the JVM build lends
its own Java when `JAVA_HOME` is unset. Naming the version that is running just runs the command,
and `kiff -v` or `kiff --version` says which version that is. A build from source is
`0.0.0-SNAPSHOT`, which `--version` follows with the commit it was built from, as in
`kiff 0.0.0-SNAPSHOT (59e074e)`. No release is that build, so from one `--use` always hands the
command over.

## GitHub Action

The action at the root of this repository runs `kiff release` for a project's GitHub releases. It
patches each new release's files from the releases before it, restores every patch and checks it
against the new file's SHA-256, and attaches the patches and a `kiff-patches.json` manifest to the
release. A project that already attaches its builds to releases adds one workflow, the one in
[Setting up the GitHub Action](#setting-up-the-github-action). Here and below, `vX.Y.Z` stands for
the latest Kiff release, which the Release badge above shows. Pin that exact tag, or the commit it
names, rather than a branch.

Publishing `v1.3.0` with `app-1.3.0.apk` then attaches a patch from each of the three releases
before it, such as `app-1.3.0.apk.from-v1.2.0.kiff`, and the manifest. The job summary names the
kiff that made the patches, and lists every patch with its size, its share of the file and what
became of it:

| File | From | Patch size | Share of file | Result |
| --- | --- | ---: | ---: | --- |
| `app-1.3.0.apk` | v1.2.0 | 4.6 MiB | 6.42% | restored exactly |
| `app-1.3.0.apk` | v1.1.0 | 9.8 MiB | 13.67% | restored exactly |
| `app-1.3.0.apk` | v1.0.0 | 60.2 MiB | 83.96% | dropped, over max-ratio 0.8 |

The action runs on Linux and macOS runners, with bash, `gh` and `jq`, which hosted runners have. It
runs the release's executable for the runner, which needs nothing else. A release without one, such
as 0.1.0, runs from its JVM zip, and a build from source needs a JDK, so then the action installs
Java 17 when the runner has no Java 17 or newer. The JVM build may use up to 6 GiB, which fits
hosted runners; on a smaller machine, set `KIFF_OPTS` to something like `-Xmx2g`.

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
      - uses: linroid/kiff@v1
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
      - uses: linroid/kiff@v1
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
| `kiff-version` | the action's | Released Kiff to make the patches with: the oldest one embedded in an app you update |
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

A patch is applied on the user's device, by the Kiff inside the app they already have, and that
Kiff refuses a patch format newer than it knows. So set `kiff-version` to the oldest Kiff still
embedded in a build of the app you update:

```yaml
      - uses: linroid/kiff@v1
        with:
          assets: app-*.apk
          kiff-version: 0.1.0
```

A newer Kiff reads every patch an older one wrote, which `FormatVectorsTest` holds each build to,
so patches from the oldest one apply on every later app as well. Raise `kiff-version` once no app
you still update embeds anything older. The action downloads exactly that release's executable for
the runner, from its archive `kiff-VERSION-PLATFORM.tar.gz` or bare as `kiff-VERSION-PLATFORM`, or
its `kiff-VERSION.zip` when it has neither, refuses any of them unless it matches the `.sha256`
beside it, and runs it; a release it cannot download fails the run.
It takes a released version such as `0.1.0` and refuses a `-SNAPSHOT`. The job summary and the
manifest's `generator` say which kiff made the patches. As for `kiff --use`, `KIFF_DOWNLOAD_URL`
replaces `https://github.com/linroid/kiff/releases/download` with a mirror that serves the same
files under `vVERSION/`.

`linroid/kiff@v1` is the easy choice. From Kiff 1.0.0 on, the tag `v1` moves to each 1.x release
once that release is built and uploaded, but never back to a lower version, and never to a release
whose `FORMAT.md` specifies another patch format than the one `v1` was at: a new patch format comes
with a new major version, and a tag of its own. Pinning `linroid/kiff@vX.Y.Z`, or the commit SHA
it names, is the strict choice, which changes only when you change it. Either way, `kiff-version`
still pins which Kiff writes the patches.

Without `kiff-version`, the action runs the Kiff release tagged at the commit it was loaded from,
downloaded in the same way: `linroid/kiff@vX.Y.Z` runs kiff X.Y.Z, and so does the commit that
`vX.Y.Z` names, and `linroid/kiff@v1` runs the highest release at the commit `v1` names. A ref
with no release at its commit, such as a branch, builds the CLI from the action's own source
first, as `0.0.0-SNAPSHOT`, which adds a few minutes. So does a release whose files cannot be
downloaded, such as a draft, as that release's version. Use `v1`, a release tag or a commit, not a
branch. With `kiff-version` set, the action itself can move ahead of your apps, since the format
comes from the kiff it runs.

### Updating from the manifest

Every release carries its own manifest, and GitHub serves the newest published one at a fixed
address, which skips drafts and prereleases:

```text
https://github.com/OWNER/REPO/releases/latest/download/kiff-patches.json
```

An updater fetches it and finds the entry for its file. It takes the patch whose `source.sha256` is
the file it has installed and whose `format` is in `Kiff.readableFormats`, downloads that patch and
checks its `sha256`, applies it, and checks the result against the file's own `sha256`. Before
applying, it compares `Kiff.info(patch).targetSize` with the file's `size`: a few bytes of patch can
declare gigabytes of target, and the declared size is what a restore writes and may allocate. When
there is no such patch, because the installed version is too old, was dropped for size or the
release has no manifest, it downloads the full file from `url` instead.


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

A container is taken apart by a `ContainerFormat`, and that is the whole extension point. It is
public API, kept compatible like the rest, so a format Kiff does not ship, such as an ISO image or
a DMG, can be added from outside Kiff:

```kotlin
class TarFormat : ContainerFormat {
  override val name = "tar"
  override fun detect(bytes: ByteArray, from: Int, to: Int) = /* magic */
  override fun decompose(bytes: ByteArray, from: Int, to: Int): List<Child> = /* members */
}

val options = KiffOptions(containers = NestedContainers + TarFormat())
val patch = Kiff.createPatch(source, target, options)
```

`NestedContainers` already holds `ZipFormat` and `DexFormat`, so an archive inside an archive, and
a dex inside either, are taken apart without being asked. The registry is asked about the file as
a whole too, so a tar is taken apart whether it is the file being patched or inside one.

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

Decomposing is weighed, not assumed. A nested region, or a whole file of any format but a zip, is
encoded both ways and the bigger result is discarded, so a format that does not pay off costs encode
time and never patch size. That matters more than it sounds: a byte search that copies from
anywhere in the source is already good at finding moved and edited content, so on *stored* content
today, decomposing mostly breaks even. It will earn its keep on compressed children, which cannot be
compared at all without being decoded. A zip that is the whole file is the exception: it is always
taken apart, since weighing it would mean searching the largest
input Kiff is given twice.

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

Every region that produces target bytes also carries a CRC-32 of them, four bytes each, which is
what turns "this did not restore" into "this region did not restore". On a package of seventy
megabytes that is the difference between a bug you can find and one you can only stare at. They
cost under 1% of a typical archive patch, and up to several percent of a small one whose entries
are stored: 32 of the 423 bytes of a patch between two kotlin-stdlib jars repacked uncompressed.
A flag in the header says whether they are present, and a flag this build does not recognise is
refused rather than guessed at.

The two whole-file checksums do real work too: `applyPatch` refuses a source file that is not the one the patch was
built against (`KiffException.SourceMismatch`) and refuses to hand back a target whose checksum does
not match what was recorded at creation time (`KiffException.VerificationFailed`).

The delta algorithm - the search that produces those instructions - is what every region is
described with.
The one Kiff ships, `RollingHashAlgorithm`, finds copies with a rolling hash over 16-byte blocks of
the source, sampled every 4-16 bytes depending on how large a range is indexed, which keeps the
index small enough to diff APK-sized files in a couple of seconds. Between copies it tracks the
source offset the target is running parallel to, and emits `DIFF` while the aligned bytes still
mostly agree. When an insertion or deletion shifts that offset and the aligned bytes stop agreeing
altogether, it looks for the shifted offset up to 128 bytes either side before falling back to
literals, so rebuilt code that has no exact match left in it still realigns.

Because the instruction set is fixed, a different algorithm needs no change to the patch format or
to how a file is taken apart: it is an encode-side choice that the same reader decodes. That is what
makes the search pluggable.

## Plugging in your own algorithm

`DeltaAlgorithm` is public. Implement it to change how bytes are matched, and pass it in
`KiffOptions`:

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

val patch = Kiff.createPatch(source, target, KiffOptions(algorithm = MyAlgorithm))
val restored = Kiff.applyPatch(source, patch)  // read back with no options at all
```

The algorithm is not recorded in the patch and nothing needs to know it ran: the regions a scanner
reports must simply tile the target exactly. An algorithm prepares a scanner over a source range
once, and Kiff reuses that scanner across many target regions, so the index is built one time
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
on `Kiff` are conveniences over `ByteArraySource`.

Applying a patch never holds either file. A patch describes its target front to back, so the
restore is written out as it is produced, and the source is addressed rather than read in:

```kotlin
Kiff.applyPatch(sourceFile, patch, target)   // target is a RestoreTarget
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

**Current limit:** the bundled `RollingHashAlgorithm` still indexes a `ByteArray`, so Kiff
materializes both inputs once before encoding and refuses an input above 2 GB with
`KiffException.UnsupportedInput`. The format and the algorithm contract address more than that; a
bounded-memory search is the next piece of work, and it needs no format change when it lands.

## Zip archives

A zip is described from both archives' layouts, region by region, pairing each target entry with
the source entry it came from - by name, or by content fingerprint when an entry
was renamed. An unchanged entry then costs a single copy instruction no matter how far it moved, and
a changed entry is indexed against its counterpart alone rather than against the whole archive,
which buys a finer sampling stride and far fewer hash collisions.

Regions the format does not name are described too - the preamble, alignment padding, an APK signing
block, the central directory - which is what makes the restored archive identical to the last byte.
If the source turns out not to be a readable zip, the target is described as one whole region, so
there is always a working patch.

`KiffReports.zip(source, target)` reports the same pairing as data, without building a patch.

**Entry data is never recompressed.** That is what makes a restore byte-exact - re-deflating would
have to reproduce the original compressor's output bit for bit - but it also means a patch for a
*deflated* entry can only be as small as the change in its compressed bytes. Modern APKs store
their `.so`, `.dex` and `resources.arsc` entries uncompressed, so in practice the interesting
content is compared directly.

## Android packages

An APK is described as a zip, plus what is specific to an APK:

- **Dex files are paired by ordinal.** A build that gains a dex renumbers the rest, so a
  `classes4.dex` with no counterpart by name or content is still compared against the source's
  highest-numbered dex rather than against the whole archive.
- **The signing block is a first-class region.** The unnamed run of bytes a v2+ signed APK carries
  between its last entry and the central directory is located, reproduced exactly, and reported on.
- **Entries are classified** as dex, native library, manifest, resource table, resource, asset,
  signature, metadata or other, which is what makes `KiffReports.apk` readable.

Storing `.so`, `.dex` and `resources.arsc` uncompressed is the norm for current Android builds - 108
of the 191 entries in the APK above - so the entries that dominate the package are compared as their
real content, not as compressed bytes.

## Benchmark

Two consecutive release builds of the same app, 67.4 MiB and 71.7 MiB, on an M-series Mac, read as
the APK they are, and for comparison as a plain zip and as plain bytes, the way 0.1's three
patchers read them. All three restore a byte-identical file, checked against the target's SHA-256:

| Read as | Patch | Create | Apply |
| --- | --- | --- | --- |
| `binary` | 13.7 MiB (19.19%) | 2.5 s | 0.7 s |
| `zip` | 13.5 MiB (18.82%) | 2.3 s | 0.8 s |
| `apk` | 13.5 MiB (18.82%) | 2.2 s | 0.8 s |

The three land close together on *this* pair because ~70% of the target sits in a handful of large
entries that genuinely changed - a replaced native library, a rebuilt dex - and no amount of
structure awareness makes new content smaller. Structure pulls ahead when entries move, when an
archive is repacked, or when only part of it was rebuilt; `apk` and `zip` agree here because every
dex in this pair still pairs by name.

## Layout

Everything a caller needs is in `com.linroid.kiff`; the subpackages are the pieces behind it.

| Package | Holds |
| --- | --- |
| `com.linroid.kiff` | `Kiff`, `KiffOptions`, `KiffReports`, `PatchInfo`, `PatcherId`, `KiffException` |
| `.delta` | the algorithm seam - `DeltaAlgorithm`, `DeltaScanner`, `DeltaSink` - plus the bundled rolling-hash search and the instruction codec |
| `.io` | `SeekableSource`, `ByteArraySource`, `fileSource`, whole-file helpers |
| `.format` | the patch container and its primitives: varint I/O, CRC-32, the content codec |
| `.zip` / `.apk` | archive parsing and region encoding, each with the report type `KiffReports` returns |
| `.text` | the line-based Myers diff, which is not part of the patch pipeline |

## Targets

The library: `jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `macosX64`, `linuxX64`,
`linuxArm64`, `mingwX64`, `js(nodejs, browser)`.

The CLI: an executable each for `linuxX64`, `linuxArm64`, `macosArm64`, `macosX64` and `mingwX64`,
built with Kotlin/Native, and the `jvm` build for anywhere else Java 17 runs.

Kiff addresses both files through `SeekableSource` rather than reading them as streams, because a
delta copies from arbitrary source offsets. Until the bundled search is converted to bounded memory,
peak memory is still roughly source + target + patch + index.

## Building from source

Kiff builds with the Gradle wrapper on JDK 17 or newer. A build from source is `0.0.0-SNAPSHOT`,
and its `kiff --version` names the commit:

```console
$ ./gradlew :cli:installDist
$ ./cli/build/install/kiff/bin/kiff --version
kiff 0.0.0-SNAPSHOT (59e074e)
```

That is the JVM build. The executable for a platform is linked by its own task, such as
`./gradlew :cli:linkReleaseExecutableMacosArm64`, into
`cli/build/bin/macosArm64/releaseExecutable/kiff.kexe`, or `kiff.exe` for `mingwX64`. A Mac links
every platform's; Linux links all but the Macs'.

`./gradlew publishToMavenLocal` publishes the library to `~/.m2` as `0.0.0-SNAPSHOT`, for a project
to try before a release. The tests run per target, as CI runs them:

| Command | Runs |
| --- | --- |
| `./gradlew jvmTest` | the library's and the CLI's tests on the JVM |
| `./gradlew jsTest` | the shared suite on Node.js and in a browser |
| `./gradlew iosSimulatorArm64Test` | the shared suite on the iOS simulator, on macOS with Xcode |
| `./gradlew macosArm64Test` | the library's and the CLI's tests natively on Apple silicon |
| `./gradlew linuxX64Test` | the library's and the CLI's tests natively on Linux |

For Linux on arm64 and for Windows, CI links the test executables on Linux, with
`./gradlew linkDebugTestLinuxArm64 linkDebugTestMingwX64`, and runs them on a machine of each.

## Releasing

A release's version is its tag. Nothing in the source names a version: any build without
`-Pversion` is `0.0.0-SNAPSHOT`, so releasing needs no commit of its own. To release `X.Y.Z`:

1. Tag the commit `vX.Y.Z` and push the tag. A prerelease adds a suffix, as in `vX.Y.Z-rc.1`.
2. The [release workflow](.github/workflows/release.yml) builds the CLI with `-Pversion=X.Y.Z`
   through [CLI Binaries](.github/workflows/cli-binaries.yml): an executable each for Linux and
   Windows on a Linux runner and for macOS on a Mac, stripped, and the JVM zip.
   [pack-cli.py](.github/scripts/pack-cli.py) packs each executable with `LICENSE` and `README.md`
   in a `kiff-X.Y.Z-<platform>` directory, dated the tagged commit, so the same executables give
   the same bytes. Each archive is unpacked on a machine of its own platform, which checks that it
   says `kiff X.Y.Z`, restores a patch exactly, and hands `kiff --use` over to a release of each
   kind. It then attaches `kiff-X.Y.Z-linux-x64.tar.gz`, `kiff-X.Y.Z-linux-arm64.tar.gz`,
   `kiff-X.Y.Z-macos-arm64.tar.gz`, `kiff-X.Y.Z-macos-x64.tar.gz`, `kiff-X.Y.Z-windows-x64.zip` and
   `kiff-X.Y.Z.zip`, each with a `.sha256`, to a draft release for the tag, whose notes start with
   what they are. It refuses any other tag and a `-SNAPSHOT`, and can be re-run by hand for an
   existing tag whose source builds the executables.
3. It then uploads the library, built with the same `-Pversion`, to the Central Portal as a
   deployment. A run started by hand uploads only when told to, since Central takes each version
   once.
4. Last, it moves the major tag `vX` to the release's commit, so that `linroid/kiff@vX` runs it,
   with [major-tag.sh](.github/scripts/major-tag.sh). It skips 0.x, prereleases and any release
   below the highest `vX.*.*` tag, and fails rather than move `vX` to a release whose `FORMAT.md`
   specifies another patch format than at the commit `vX` names: a new patch format needs a new
   major version.
5. Review the draft and publish it soon, since until then the action at `vX` finds nothing to
   download and builds Kiff from source. Then open
   [Deployments](https://central.sonatype.com/publishing/deployments) on the Central Portal, wait
   for the deployment to show as validated, and publish it. The workflow does not wait for that
   validation, so a failed one shows only there.

To build a release by hand, pass its version the same way, as in
`./gradlew -Pversion=X.Y.Z :cli:distZip :cli:linkReleaseExecutableMacosArm64`. Pull requests that
touch the CLI or the library run CLI Binaries too, as `0.0.0-ci`.

The upload needs five repository secrets, the same ones linroid/Ketch uses:
`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`, a Central Portal user token; and
`SIGNING_KEY`, `SIGNING_KEY_ID` and `SIGNING_KEY_PASSWORD`, an ASCII-armored secret PGP key, its
short ID and its passphrase. The public half has to be on a keyserver Central reads, such as
`keyserver.ubuntu.com`.

## License

Apache 2.0. See [LICENSE](LICENSE).
