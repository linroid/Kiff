#!/usr/bin/env bash
# Unpacks a release's archive of kiff on its own platform and runs it as a user would: what it says
# it is, a patch that restores exactly, a diff's bytes and exit status, and kiff --use handing over
# to a release of each kind, which a mirror on localhost serves.
#
# Usage: smoke-cli.sh ARCHIVE PLATFORM JVM_ZIP
#   ARCHIVE   the archive of the executable, named as a release names it, with its .sha256 beside it
#   PLATFORM  its platform as a release names it, such as linux-x64 or windows-x64
#   JVM_ZIP   kiff-OTHER.zip, the JVM build of another version, for --use to fall back to
# VERSION is the version ARCHIVE was built as, and OTHER the version JVM_ZIP was.
set -euo pipefail

archive=$1
platform=$2
jvm_zip=$3
version=${VERSION:?}
other=${OTHER:?}
scripts=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
python=$(command -v python3 || command -v python)
exe=''
if [[ $platform == windows-* ]]; then
  exe=.exe
fi

fail() {
  printf '::error::%s\n' "$1"
  exit 1
}

# A path a native Windows program reads the same way, where bash's own differ.
native_path() {
  if command -v cygpath > /dev/null; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

# The line sha256sum writes for the file $1 in the current directory, which is what a release
# attaches beside each file.
sha256_line() {
  if command -v sha256sum > /dev/null; then
    sha256sum "$1"
  else
    shasum -a 256 "$1"
  fi
}

# Serves the file $1 as $2 in the mirror's directory $3, with its checksum beside it.
publish() {
  mkdir -p "$3"
  cp "$1" "$3/$2"
  (cd "$3" && sha256_line "$2") > "$3/$2.sha256"
}

archive=$(cd "$(dirname "$archive")" && pwd)/$(basename "$archive")
work=$(native_path "${RUNNER_TEMP:-$(mktemp -d)}/smoke")
rm -rf "$work"
mkdir -p "$work"
cd "$work"

echo "::group::The archive, checked and unpacked as a user would"
if command -v sha256sum > /dev/null; then
  (cd "$(dirname "$archive")" && sha256sum --check "$(basename "$archive").sha256")
else
  (cd "$(dirname "$archive")" && shasum -a 256 --check "$(basename "$archive").sha256")
fi
mkdir unpacked
if [[ $platform == windows-* ]]; then
  # Windows's own tar, which opens a zip; Git Bash's tar is GNU's, which cannot.
  "$(cygpath -u -S)/tar.exe" -xf "$(native_path "$archive")" -C unpacked
else
  tar -xzf "$archive" -C unpacked
fi
root=kiff-$version-$platform
kiff=$work/unpacked/$root/kiff$exe
for file in "$kiff" "unpacked/$root/LICENSE" "unpacked/$root/README.md"; do
  if [[ ! -f $file ]]; then
    find unpacked
    fail "$archive holds no $file."
  fi
done
if [[ $(find unpacked -mindepth 1 | wc -l | tr -d ' ') != 4 ]]; then
  find unpacked
  fail "$archive should hold its $root directory with kiff$exe, LICENSE and README.md alone."
fi
# What a tar.gz has over a bare executable: it is runnable as it comes out.
if [[ -z $exe && ! -x $kiff ]]; then
  fail "$archive gives a kiff that is not executable."
fi
echo "::endgroup::"

echo "::group::What it says it is"
said=$("$kiff" --version)
if [[ $said != "kiff $version" ]]; then
  fail "$kiff --version printed '$said', not 'kiff $version'."
fi
"$kiff" --help
if ! "$kiff" --licenses | grep -q 'Version 2.0, January 2004'; then
  fail "$kiff --licenses does not give the Apache License."
fi
echo "::endgroup::"

echo "::group::A patch that restores exactly"
head -c 300000 /dev/urandom > old.bin
{ head -c 120000 old.bin; printf 'an insertion'; tail -c +150001 old.bin; } > new.bin
"$kiff" create old.bin new.bin patch.kiff
"$kiff" apply old.bin patch.kiff restored.bin
cmp restored.bin new.bin
"$kiff" info patch.kiff
echo "::endgroup::"

echo "::group::A diff, byte for byte"
printf 'a\n\tb\n' > a.txt
printf 'a\n\tc\n' > b.txt
printf -- '--- a.txt\n+++ b.txt\n@@ -1,2 +1,2 @@\n a\n-\tb\n+\tc\n' > expected.diff
status=0
"$kiff" diff a.txt b.txt > got.diff || status=$?
if [[ $status != 1 ]]; then
  fail "kiff diff of two different files exited $status, not 1."
fi
if ! cmp got.diff expected.diff; then
  od -c got.diff
  fail "kiff diff printed other bytes than diff -u would."
fi
echo "::endgroup::"

echo "::group::kiff --use, from a mirror"
# OTHER's JVM zip alone; ARCHIVED, this executable in an archive of its own, as a release attaches
# it; BARE, the same executable alone, as 0.2.0 attached it; TAMPERED, the archive under a checksum
# it does not have.
archived=0.0.2-smoke
tampered=0.0.3-smoke
bare=0.0.4-smoke
mirror=$work/mirror
publish "$jvm_zip" "kiff-$other.zip" "$mirror/v$other"
for release in "$archived" "$tampered"; do
  "$python" "$scripts/pack-cli.py" "$kiff" "$platform" "$release" "$work/packed"
done
suffix=.tar.gz
if [[ -n $exe ]]; then
  suffix=.zip
fi
publish "$work/packed/kiff-$archived-$platform$suffix" "kiff-$archived-$platform$suffix" \
  "$mirror/v$archived"
publish "$work/packed/kiff-$tampered-$platform$suffix" "kiff-$tampered-$platform$suffix" \
  "$mirror/v$tampered"
printf '%064d  kiff-%s-%s%s\n' 0 "$tampered" "$platform" "$suffix" \
  > "$mirror/v$tampered/kiff-$tampered-$platform$suffix.sha256"
publish "$kiff" "kiff-$bare-$platform$exe" "$mirror/v$bare"

"$python" -m http.server 8765 --bind 127.0.0.1 --directory "$mirror" > "$work/mirror.log" 2>&1 &
server=$!
trap 'kill "$server" 2> /dev/null || true' EXIT
for _ in $(seq 50); do
  if curl -fsS -o /dev/null http://127.0.0.1:8765/ 2> /dev/null; then
    break
  fi
  sleep 0.2
done
export KIFF_DOWNLOAD_URL=http://127.0.0.1:8765
KIFF_HOME=$(native_path "$work/home")
export KIFF_HOME

# A release with an archive for this platform runs the executable in it, kept as a bare one is.
for release in "$archived" "$bare"; do
  said=$("$kiff" --use "$release" --version)
  if [[ $said != "kiff $version" ]]; then
    fail "kiff --use $release --version printed '$said', not this executable's 'kiff $version'."
  fi
  if [[ ! -f $KIFF_HOME/versions/$release/bin/kiff$exe ]]; then
    fail "kiff --use $release installed no bin/kiff$exe."
  fi
  status=0
  "$kiff" --use "$release" diff a.txt b.txt > /dev/null || status=$?
  if [[ $status != 1 ]]; then
    fail "kiff --use $release diff exited $status, not the 1 of the version it handed over to."
  fi
done
if [[ ! -f $KIFF_HOME/versions/$archived/LICENSE ]]; then
  fail "kiff --use $archived should keep the LICENSE its archive holds."
fi
# The bare executable is what a release without an archive has, as 0.2.0 has none.
if ! grep -q "GET /v$bare/kiff-$bare-$platform$exe HTTP/1.[01]\" 200" "$work/mirror.log"; then
  cat "$work/mirror.log"
  fail "kiff --use $bare should have downloaded kiff-$bare-$platform$exe."
fi

# A release with only its JVM zip runs that, on the Java setup-java installed.
said=$("$kiff" --use "$other" --version | tr -d '\r')
if [[ $said != "kiff $other" ]]; then
  fail "kiff --use $other --version printed '$said', not 'kiff $other'."
fi
status=0
"$kiff" --use "$other" diff a.txt b.txt > /dev/null || status=$?
if [[ $status != 1 ]]; then
  fail "kiff --use $other diff exited $status, not the 1 of the version it handed over to."
fi
status=0
"$kiff" --use "$other" info "two words.kiff" 2> info.log || status=$?
if [[ $status == 0 ]] || ! grep -q 'two words.kiff' info.log; then
  cat info.log
  fail "kiff --use $other should pass 'two words.kiff' on as one argument."
fi

# One whose archive does not match its checksum is refused, and nothing of it is kept.
if "$kiff" --use "$tampered" --version 2> tampered.log; then
  fail "kiff --use $tampered should refuse a release that does not match its checksum."
fi
cat tampered.log
if ! grep -q 'has SHA-256' tampered.log || [[ -e $KIFF_HOME/versions/$tampered ]]; then
  fail "kiff --use $tampered should have refused the release for its checksum, keeping nothing."
fi
echo "::endgroup::"

echo "kiff $version runs on $platform"
