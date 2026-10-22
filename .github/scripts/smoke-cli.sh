#!/usr/bin/env bash
# Runs a kiff executable on its own platform as a user would: what it says it is, a patch that
# restores exactly, a diff's bytes and exit status, and kiff --use handing over to a release of each
# kind, which a mirror on localhost serves.
#
# Usage: smoke-cli.sh KIFF PLATFORM JVM_ZIP
#   KIFF      the executable, named as a release names it
#   PLATFORM  its platform as a release names it, such as linux-x64 or windows-x64
#   JVM_ZIP   kiff-OTHER.zip, the JVM build of another version, for --use to fall back to
# VERSION is the version KIFF was built as, and OTHER the version JVM_ZIP was.
set -euo pipefail

kiff=$1
platform=$2
jvm_zip=$3
version=${VERSION:?}
other=${OTHER:?}
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

sha256_line() {
  if command -v sha256sum > /dev/null; then
    (cd "$(dirname "$1")" && sha256sum "$(basename "$1")")
  else
    (cd "$(dirname "$1")" && shasum -a 256 "$(basename "$1")")
  fi
}

chmod +x "$kiff"
kiff=$(native_path "$(cd "$(dirname "$kiff")" && pwd)/$(basename "$kiff")")
work=$(native_path "${RUNNER_TEMP:-$(mktemp -d)}/smoke")
rm -rf "$work"
mkdir -p "$work"
cd "$work"

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
# OTHER's JVM zip alone; NATIVE, this executable as a release of its own; TAMPERED, the same under
# a checksum it does not have.
native=0.0.2-smoke
tampered=0.0.3-smoke
mirror=$work/mirror
mkdir -p "$mirror/v$other" "$mirror/v$native" "$mirror/v$tampered"
cp "$jvm_zip" "$mirror/v$other/kiff-$other.zip"
sha256_line "$mirror/v$other/kiff-$other.zip" > "$mirror/v$other/kiff-$other.zip.sha256"
cp "$kiff" "$mirror/v$native/kiff-$native-$platform$exe"
sha256_line "$mirror/v$native/kiff-$native-$platform$exe" \
  > "$mirror/v$native/kiff-$native-$platform$exe.sha256"
cp "$kiff" "$mirror/v$tampered/kiff-$tampered-$platform$exe"
printf '%064d  kiff-%s-%s%s\n' 0 "$tampered" "$platform" "$exe" \
  > "$mirror/v$tampered/kiff-$tampered-$platform$exe.sha256"

python=$(command -v python3 || command -v python)
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

# A release with an executable for this platform runs it.
said=$("$kiff" --use "$native" --version)
if [[ $said != "kiff $version" ]]; then
  fail "kiff --use $native --version printed '$said', not this executable's 'kiff $version'."
fi
if [[ ! -f $KIFF_HOME/versions/$native/bin/kiff$exe ]]; then
  fail "kiff --use $native installed no bin/kiff$exe."
fi
status=0
"$kiff" --use "$native" diff a.txt b.txt > /dev/null || status=$?
if [[ $status != 1 ]]; then
  fail "kiff --use $native diff exited $status, not the 1 of the version it handed over to."
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

# One that does not match its checksum is refused, and nothing of it is kept.
if "$kiff" --use "$tampered" --version 2> tampered.log; then
  fail "kiff --use $tampered should refuse a release that does not match its checksum."
fi
cat tampered.log
if ! grep -q 'has SHA-256' tampered.log || [[ -e $KIFF_HOME/versions/$tampered ]]; then
  fail "kiff --use $tampered should have refused the release for its checksum, keeping nothing."
fi
echo "::endgroup::"

echo "kiff $version runs on $platform"
