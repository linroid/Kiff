#!/usr/bin/env bash
# Says whether the runner lacks Java 17 or newer, for the step that installs it. Looks where Gradle
# and kiff's start script look: JAVA_HOME when it is set, else the java on PATH.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/lib.sh"

if [[ -n ${JAVA_HOME:-} ]]; then
  java=$JAVA_HOME/bin/java
else
  java=$(command -v java || true)
fi

major=0
if [[ -n $java && -x $java ]]; then
  # The first line names the version, as in: openjdk version "17.0.12" or java version "1.8.0_392".
  # macOS has a java that only says no Java is installed, which leaves major at 0.
  report=$("$java" -version 2>&1 || true)
  version_pattern='"([0-9]+)(\.([0-9]+))?'
  if [[ ${report%%$'\n'*} =~ $version_pattern ]]; then
    major=${BASH_REMATCH[1]}
    if [[ $major == 1 ]]; then
      major=${BASH_REMATCH[3]:-0}
    fi
  fi
fi

if ((major >= 17)); then
  echo "Using Java $major at $java"
  set_output needed false
else
  echo "Found no Java 17 or newer, so installing Temurin 17"
  set_output needed true
fi
