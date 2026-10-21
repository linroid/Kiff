#!/usr/bin/env bash
#
# Kiff CLI installer
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/linroid/kiff/main/install.sh | bash
#
# Options (via environment variables, given to bash rather than curl):
#   KIFF_VERSION       - version to install, with or without a leading v (default: latest)
#   KIFF_INSTALL       - directory for the kiff link (default: /usr/local/bin)
#   KIFF_HOME          - where versions are kept, as kiff --use keeps them (default: ~/.kiff)
#   KIFF_DOWNLOAD_URL  - where releases are downloaded from, for a mirror
#                        (default: https://github.com/linroid/kiff/releases/download)
#   GITHUB_TOKEN       - optional, for the GitHub API when looking up the latest release
#
# For example, to link kiff where no sudo is needed:
#   curl -fsSL https://raw.githubusercontent.com/linroid/kiff/main/install.sh \
#     | KIFF_INSTALL="$HOME/.local/bin" bash
#
# It installs the executable each release attaches for this machine, which needs nothing else.
# A release without one, as 0.1.0 has none, is installed from its JVM zip instead, which runs on
# Java 17 or newer, which this script does not install.
#
set -euo pipefail

REPO="linroid/kiff"
BINARY_NAME="kiff"
INSTALL_DIR="${KIFF_INSTALL:-/usr/local/bin}"
KIFF_DIR="${KIFF_HOME:-${HOME}/.kiff}"
DOWNLOAD_URL="${KIFF_DOWNLOAD_URL:-https://github.com/${REPO}/releases/download}"

# The temporary directories the EXIT trap removes. They are global because local variables are out
# of scope when the trap runs.
DOWNLOAD_DIR=""
UNPACK_DIR=""

# --- Helpers ---------------------------------------------------------------

info() { printf "\033[1;34m==>\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33mWarning:\033[0m %s\n" "$*" >&2; }
error() {
  printf "\033[1;31mError:\033[0m %s\n" "$*" >&2
  exit 1
}

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    error "Required command '$1' not found. Please install it and try again."
  fi
}

cleanup() {
  if [ -n "$DOWNLOAD_DIR" ]; then
    rm -rf -- "$DOWNLOAD_DIR"
  fi
  if [ -n "$UNPACK_DIR" ]; then
    rm -rf -- "$UNPACK_DIR"
  fi
}

# $1 as an absolute path, so the link points at it from anywhere.
absolute() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s/%s' "$PWD" "$1" ;;
  esac
}

# The SHA-256 of the file $1, in hex. Read from standard input, so no file name is escaped.
sha256_of() {
  local line
  if command -v sha256sum >/dev/null 2>&1; then
    line="$(sha256sum <"$1")"
  else
    line="$(shasum -a 256 <"$1")"
  fi
  printf '%s' "${line%% *}"
}

# --- Check the platform ----------------------------------------------------

# This machine as a release names its executables, such as linux-x64, or nothing when releases have
# none for its processor, which the JVM zip then serves.
detect_platform() {
  local os arch
  os="$(uname -s)"
  case "$os" in
    Linux*) os="linux" ;;
    Darwin*) os="macos" ;;
    MINGW* | MSYS* | CYGWIN*)
      warn "This script installs kiff on Linux and macOS. On Windows, download"
      warn "kiff-<version>-windows-x64.exe from https://github.com/${REPO}/releases/latest"
      warn "and run it, renamed kiff.exe if you like."
      error "Unsupported shell: $os"
      ;;
    *) error "Unsupported operating system: $os" ;;
  esac
  arch="$(uname -m)"
  case "$arch" in
    x86_64 | amd64) arch="x64" ;;
    aarch64 | arm64) arch="arm64" ;;
    *) return 0 ;;
  esac
  printf '%s-%s' "$os" "$arch"
}

# --- Resolve version -------------------------------------------------------

resolve_version() {
  if [ -n "${KIFF_VERSION:-}" ]; then
    # Strip leading 'v' if present
    echo "${KIFF_VERSION#v}"
    return
  fi

  info "Fetching latest release..." >&2
  local tag="" latest_url
  # The release page redirects to .../releases/tag/<tag>; unlike the API, it is not limited to
  # 60 anonymous requests an hour.
  if latest_url="$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
    "https://github.com/${REPO}/releases/latest")"; then
    case "$latest_url" in
      */releases/tag/*) tag="${latest_url##*/releases/tag/}" ;;
    esac
  fi

  if [ -z "$tag" ]; then
    local auth=()
    if [ -n "${GITHUB_TOKEN:-}" ]; then
      auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
    fi
    tag="$(curl -fsSL \
      -H "Accept: application/vnd.github+json" \
      ${auth[@]+"${auth[@]}"} \
      "https://api.github.com/repos/${REPO}/releases/latest" \
      | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"//;s/".*//')" || true
  fi

  if [ -z "$tag" ]; then
    error "Failed to determine the latest release version." \
      "Set KIFF_VERSION to install a specific one."
  fi
  echo "${tag#v}"
}

# Refuses a version no release can have, as kiff --use does. The pattern also keeps the version one
# path name under $KIFF_HOME/versions.
check_version() {
  local version="$1" released='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'
  case "$(printf '%s' "$version" | tr '[:upper:]' '[:lower:]')" in
    *-snapshot) error "${version} is a snapshot, and no snapshot has a release to download." ;;
  esac
  if ! [[ $version =~ $released ]]; then
    error "\"${version}\" is not a released Kiff version, such as 1.2.3."
  fi
}

# --- Check the link --------------------------------------------------------

# Refuses to replace anything at $1 but a link into $2, which an earlier run made.
check_link() {
  local link="$1" home="$2" target
  if [ -L "$link" ]; then
    target="$(readlink "$link")"
    case "$target" in
      "$home"/*) return ;;
    esac
    error "${link} links to ${target}, which is not in ${home}." \
      "Remove it and run this again, or set KIFF_INSTALL to link kiff somewhere else."
  fi
  if [ -e "$link" ]; then
    error "${link} already exists, and this script did not install it." \
      "Remove it and run this again, or set KIFF_INSTALL to link kiff somewhere else."
  fi
}

# --- Download and install ---------------------------------------------------

# Downloads ${1}.sha256 to $2 and sets CHECKSUM to its hash, which must be on the line sha256sum
# writes for the file named $3. Returns 1 when there is no such file, and fails on anything else.
CHECKSUM=""
fetch_checksum() {
  local url="$1" file="$2" name="$3" status
  status="$(curl -sSL -o "$file" -w '%{http_code}' "${url}.sha256")" || status=000
  case "$status" in
    200) ;;
    404) return 1 ;;
    *) error "Could not download ${url}.sha256 (HTTP ${status})." ;;
  esac
  local expected="" named="" rest=""
  read -r expected named rest <"$file" || true
  expected="$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')"
  named="${named#\*}"
  if ! [[ $expected =~ ^[0-9a-f]{64}$ ]] || [ -n "$rest" ] \
    || { [ -n "$named" ] && [ "$named" != "$name" ]; }; then
    error "${url}.sha256 is not a SHA-256 checksum of ${name}."
  fi
  CHECKSUM="$expected"
}

# Downloads $1 to $2 and refuses it unless it has SHA-256 $3.
fetch_checked() {
  local url="$1" file="$2" expected="$3" actual
  info "Downloading ${url}"
  if ! curl -fSL --progress-bar -o "$file" "$url"; then
    error "Download failed: ${url}."
  fi
  actual="$(sha256_of "$file")"
  if [ "$actual" != "$expected" ]; then
    error "${url} has SHA-256 ${actual}, but ${url}.sha256 says ${expected}." \
      "Nothing was installed."
  fi
}

# Installs Kiff $1 for platform $3 in $2/versions/$1, the directory kiff --use keeps it in: the
# executable built for the platform, or the JVM zip when the release has none.
install_version() {
  local version="$1" home="$2" platform="$3"
  local versions="${home}/versions"
  local dir="${versions}/${version}"
  local base="$DOWNLOAD_URL"
  while [ "${base%/}" != "$base" ]; do
    base="${base%/}"
  done
  local release="${base}/v${version}"

  DOWNLOAD_DIR="$(mktemp -d)"

  # The checksum first, so a release without one is refused before anything large is downloaded.
  local name="" native=""
  if [ -n "$platform" ]; then
    name="kiff-${version}-${platform}"
    if fetch_checksum "${release}/${name}" "${DOWNLOAD_DIR}/${name}.sha256" "$name"; then
      native=1
    fi
  fi
  if [ -z "$native" ]; then
    name="kiff-${version}.zip"
    if ! fetch_checksum "${release}/${name}" "${DOWNLOAD_DIR}/${name}.sha256" "$name"; then
      error "Download failed. Check that kiff ${version} is a published release."
    fi
    info "kiff ${version} has no build for ${platform:-this machine}, so its JVM build is installed"
    need_cmd unzip
  fi
  fetch_checked "${release}/${name}" "${DOWNLOAD_DIR}/${name}" "$CHECKSUM"

  if ! mkdir -p "$versions"; then
    error "Could not create ${versions}. Set KIFF_HOME to a directory you can write."
  fi
  # Beside the target, so the move that installs it is a rename within one directory.
  UNPACK_DIR="$(mktemp -d "${versions}/.${version}-XXXXXXXX")"
  local unpacked="${UNPACK_DIR}/kiff-${version}"
  if [ -n "$native" ]; then
    mkdir -p "${unpacked}/bin"
    mv "${DOWNLOAD_DIR}/${name}" "${unpacked}/bin/${BINARY_NAME}"
  else
    info "Extracting..."
    unzip -q "${DOWNLOAD_DIR}/${name}" -d "$UNPACK_DIR"
    if [ ! -f "${unpacked}/bin/${BINARY_NAME}" ]; then
      error "${release}/${name} holds no kiff-${version}/bin/${BINARY_NAME}."
    fi
  fi
  chmod a+x "${unpacked}/bin/"*

  # Given its final name first, so that moving it into $versions fails rather than nesting it when
  # kiff --use installed the same version meanwhile.
  mv "$unpacked" "${UNPACK_DIR}/${version}"
  if ! mv "${UNPACK_DIR}/${version}" "${versions}/" 2>/dev/null; then
    # Another install got there first, and its copy is as good as this one.
    if [ ! -f "${dir}/bin/${BINARY_NAME}" ]; then
      error "Could not move kiff ${version} into ${dir}."
    fi
  fi
}

# --- Check Java --------------------------------------------------------------

# The major version of the Java that kiff's start script runs: JAVA_HOME's when it is set, else the
# java on PATH. 0 when there is none.
java_major() {
  local java report major=0 pattern='"([0-9]+)(\.([0-9]+))?'
  if [ -n "${JAVA_HOME:-}" ]; then
    java="${JAVA_HOME}/bin/java"
  else
    java="$(command -v java || true)"
  fi
  if [ -n "$java" ] && [ -x "$java" ]; then
    # The first line names the version, as in: openjdk version "17.0.12" or java version
    # "1.8.0_392". macOS has a java that only says no Java is installed, which leaves 0.
    report="$("$java" -version 2>&1 || true)"
    if [[ ${report%%$'\n'*} =~ $pattern ]]; then
      major="${BASH_REMATCH[1]}"
      if [ "$major" = 1 ]; then
        major="${BASH_REMATCH[3]:-0}"
      fi
    fi
  fi
  echo "$major"
}

warn_java() {
  local major="$1" found="no Java"
  if [ "$major" -gt 0 ]; then
    found="Java ${major}"
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    found="${found} in JAVA_HOME (${JAVA_HOME})"
  fi
  warn "This release has no build for this machine, and its JVM build needs Java 17 or newer,"
  warn "but found ${found}."
  warn "Install one, such as:"
  case "$(uname -s)" in
    Darwin*)
      warn "  brew install openjdk@17"
      warn "and put it on your PATH: export PATH=\"\$(brew --prefix openjdk@17)/bin:\$PATH\""
      ;;
    *)
      warn "  sudo apt install openjdk-17-jre-headless    (Debian, Ubuntu)"
      warn "  sudo dnf install java-17-openjdk-headless   (Fedora, RHEL)"
      ;;
  esac
  warn "or set JAVA_HOME to one. kiff is installed, and runs once it finds Java 17."
}

# --- Main ------------------------------------------------------------------

main() {
  need_cmd curl
  need_cmd uname
  if ! command -v sha256sum >/dev/null 2>&1; then
    need_cmd shasum
  fi

  local platform version home install_dir
  platform="$(detect_platform)"
  version="$(resolve_version)"
  check_version "$version"
  home="$(absolute "$KIFF_DIR")"
  install_dir="$(absolute "$INSTALL_DIR")"
  local dir="${home}/versions/${version}"
  local launcher="${dir}/bin/${BINARY_NAME}"
  local link="${install_dir}/${BINARY_NAME}"

  info "Installing kiff ${version}"
  check_link "$link" "$home"
  trap cleanup EXIT

  # Only a complete install is ever moved into place, so one that is there needs no network.
  if [ -e "$dir" ]; then
    if [ ! -f "$launcher" ]; then
      error "${dir} holds no bin/${BINARY_NAME}: delete it to download kiff ${version} again."
    fi
    info "Using kiff ${version}, already installed in ${dir}"
  else
    install_version "$version" "$home" "$platform"
    info "Installed kiff ${version} in ${dir}"
  fi

  # Link it, with sudo only when the directory cannot be written.
  local sudo=()
  if [ -d "$install_dir" ]; then
    if [ ! -w "$install_dir" ]; then
      sudo=(sudo)
    fi
  elif ! mkdir -p "$install_dir" 2>/dev/null; then
    sudo=(sudo)
  fi
  if [ ${#sudo[@]} -gt 0 ]; then
    if ! command -v sudo >/dev/null 2>&1; then
      error "${install_dir} is not writable, and there is no sudo." \
        "Set KIFF_INSTALL to a directory you can write, such as ~/.local/bin."
    fi
    info "Linking kiff into ${install_dir} (requires sudo)..."
    sudo mkdir -p "$install_dir"
  fi
  ${sudo[@]+"${sudo[@]}"} ln -sfn "$launcher" "$link"
  info "Linked ${link} to ${launcher}"

  # Verify installation. Only a JVM build, which has its jars in lib, needs Java.
  local major=17 output
  if [ -d "${dir}/lib" ]; then
    major="$(java_major)"
  fi
  if [ "$major" -lt 17 ]; then
    warn_java "$major"
  elif output="$("$link" --version 2>&1)"; then
    info "$output"
  elif "$link" --help >/dev/null 2>&1; then
    # Releases from before --version still show they run by answering --help.
    info "kiff ${version} runs"
  else
    warn "${link} --version failed:"
    warn "$output"
  fi

  case ":${PATH}:" in
    *":${install_dir}:"*)
      local found
      found="$(command -v "$BINARY_NAME" || true)"
      if [ -n "$found" ] && [ "$found" != "$link" ]; then
        warn "${found} comes first in your PATH, so 'kiff' runs it rather than ${link}."
      else
        info "Done! Run 'kiff --help' to get started."
      fi
      ;;
    *)
      warn "${install_dir} is not in your PATH."
      warn "Add it with: export PATH=\"${install_dir}:\$PATH\""
      ;;
  esac
}

main "$@"
