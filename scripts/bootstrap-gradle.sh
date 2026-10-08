#!/usr/bin/env bash
# Creates the Gradle wrapper for this project without Android Studio.
#
# The repository ships `gradle/wrapper/gradle-wrapper.properties` but not `gradle-wrapper.jar` (a
# binary blob in git is a review hazard, and some corporate mirrors strip it). This script downloads a
# known-good Gradle, runs `gradle wrapper` once, and leaves the repo in the normal state where
# `./gradlew assembleDebug` works for everyone else.
#
#   bash scripts/bootstrap-gradle.sh
# Note: `gradle wrapper` still runs Gradle's configuration phase, so the Android Gradle plugin has to
# resolve once. Either have internet, or do one Android Studio "Sync" first, which fills ~/.gradle and
# writes the wrapper for you — in that case you do not need this script at all.

set -euo pipefail

GRADLE_VERSION="${GRADLE_VERSION:-8.2}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${GRADLE_HOME:-$HOME/.gradle}/bootstrap/gradle-$GRADLE_VERSION"

if [ ! -d "$CACHE" ]; then
  echo "==> downloading Gradle $GRADLE_VERSION"
  mkdir -p "$(dirname "$CACHE")"
  ZIP="$CACHE.zip"
  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "$ZIP" "$URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "need curl or wget" >&2
    exit 1
  fi
  rm -rf "$CACHE"
  mkdir -p "$CACHE"
  ( cd "$CACHE" && unzip -q "$ZIP" && mv "gradle-$GRADLE_VERSION"/* . 2>/dev/null || true )
  rm -f "$ZIP"
fi

GRADLE_BIN="$CACHE/bin/gradle"
[ -x "$GRADLE_BIN" ] || GRADLE_BIN="$(find "$CACHE" -maxdepth 3 -type f -name gradle -path '*/bin/*' | head -1)"
if [ -z "${GRADLE_BIN:-}" ] || [ ! -x "$GRADLE_BIN" ]; then
  echo "could not locate the gradle launcher under $CACHE" >&2
  exit 1
fi

echo "==> generating the wrapper in $DIR"
cd "$DIR"
"$GRADLE_BIN" --no-daemon wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin

echo
echo "done. now build with:"
echo "  cd $DIR && ./gradlew assembleRelease"
echo "or for a fast install-and-run on the head unit (needs JDK 17 + Android SDK):"
echo "  ./gradlew installRelease"
