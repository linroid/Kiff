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
