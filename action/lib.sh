# shellcheck shell=bash
# Shared by the action's scripts, which source it.
#
# Everything here runs on the bash 3.2 that macOS ships, and on BSD as well as GNU tools.

# $1 as the data of a workflow command, so a message of several lines stays one annotation.
escape_data() {
  local percent='%' text=$1
  text=${text//$percent/%25}
  text=${text//$'\r'/%0D}
  text=${text//$'\n'/%0A}
  printf '%s' "$text"
}

fail() {
  printf '::error::%s\n' "$(escape_data "$1")"
  exit 1
}

warn() {
  printf '::warning::%s\n' "$(escape_data "$1")"
}

notice() {
  printf '::notice::%s\n' "$(escape_data "$1")"
}

# Sets the step output $1 to $2, which is one line.
set_output() {
  printf '%s=%s\n' "$1" "$2" >> "${GITHUB_OUTPUT:?}"
}

# $1 without the whitespace around it.
trim() {
  local text=$1
  text=${text#"${text%%[![:space:]]*}"}
  text=${text%"${text##*[![:space:]]}"}
  printf '%s' "$text"
}

lowercase() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

# The SHA-256 of the file $1, in hex.
sha256_of() {
  local line
  if command -v sha256sum > /dev/null; then
    line=$(sha256sum "$1")
  else
    line=$(shasum -a 256 "$1")
  fi
  printf '%s' "${line%% *}"
}

# This runner's platform as a Kiff release names its executables, such as linux-x64, or nothing
# where a release has none, read from RUNNER_OS and RUNNER_ARCH.
runner_platform() {
  local os arch
  case ${RUNNER_OS:-} in
    Linux) os=linux ;;
    macOS) os=macos ;;
    Windows) os=windows ;;
    *) return 0 ;;
  esac
  case ${RUNNER_ARCH:-} in
    X64) arch=x64 ;;
    ARM64) arch=arm64 ;;
    *) return 0 ;;
  esac
  if [[ $os-$arch != windows-arm64 ]]; then
    printf '%s' "$os-$arch"
  fi
}

# The ref the action was loaded from: $1, which action.yml passes from github.action_ref, when the
# action's path $2 agrees that it is linroid/kiff at that ref; nothing otherwise. The runner keeps
# a downloaded action in _actions/OWNER/NAME/REF. github.action_ref is empty for uses: ./, and is
# another action's when a composite action runs this one (actions/runner#2473), which the path
# then contradicts.
action_ref() {
  local ref=$1 path=${2//\\//}
  path=$(lowercase "${path%/}")
  if [[ -n $ref && $path == */_actions/linroid/kiff/"$(lowercase "$ref")" ]]; then
    printf '%s' "$ref"
  fi
}

# The version of the Kiff release tagged at the commit that ref $1, a tag or a full commit SHA,
# names, read from the output of git ls-remote --tags on stdin; nothing when there is none. An
# annotated tag's commit is on its peeled line, TAG^{}. The ref itself wins when it is a release
# tag, and otherwise a final release wins over a prerelease at the same commit.
release_at() {
  local ref=$1 sha=''
  if [[ $ref =~ ^[0-9a-fA-F]{40}$ ]]; then
    sha=$(lowercase "$ref")
  fi
  awk -v ref="$ref" -v sha="$sha" '
    substr($2, 1, 10) == "refs/tags/" {
      name = substr($2, 11)
      if (substr(name, length(name) - 2) == "^{}") {
        peeled[substr(name, 1, length(name) - 3)] = $1
      } else {
        direct[name] = $1
        names[++count] = name
      }
    }
    function commit(tag) {
      return (tag in peeled) ? peeled[tag] : direct[tag]
    }
    END {
      target = sha != "" ? sha : ((ref in direct) ? commit(ref) : "")
      for (i = 1; target != "" && i <= count; i++) {
        name = names[i]
        if (commit(name) != target) continue
        if (name !~ /^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$/) continue
        if (tolower(name) ~ /-snapshot$/) continue
        if (name == ref) {
          best = name
          break
        }
        if (best == "" || (best ~ /-/ && name !~ /-/)) best = name
      }
      if (best != "") print substr(best, 2)
    }
  '
}
