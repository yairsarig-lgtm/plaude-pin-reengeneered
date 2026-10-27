#!/usr/bin/env bash
# Runs ./gradlew with an environment that works on this Windows machine.
# The user profile path contains non-ASCII characters, which breaks Gradle's test-worker
# argfile, so GRADLE_USER_HOME goes through an ASCII junction (E:\gradle-home -> %USERPROFILE%\.gradle)
# and the SDK through its 8.3 short path. Override any of these from the environment.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$(cygpath -d "$LOCALAPPDATA/Android/Sdk")}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-E:\\gradle-home}"
exec ./gradlew "$@"
