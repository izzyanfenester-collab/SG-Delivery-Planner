#!/bin/sh
# Gradle bootstrap for environments without an installed Gradle.
set -eu
VERSION=8.13
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/sg-bootstrap"
DIST_DIR="$CACHE_DIR/gradle-$VERSION"
if [ ! -x "$DIST_DIR/bin/gradle" ]; then
 mkdir -p "$CACHE_DIR"
 URL="https://downloads.gradle.org/distributions/gradle-$VERSION-bin.zip"
 curl --fail --location --retry 3 "$URL" -o "$CACHE_DIR/distribution.zip"
 curl --fail --location --retry 3 "$URL.sha256" -o "$CACHE_DIR/distribution.sha256"
 EXPECTED=$(cat "$CACHE_DIR/distribution.sha256")
 ACTUAL=$(sha256sum "$CACHE_DIR/distribution.zip" | cut -d ' ' -f 1)
 [ "$EXPECTED" = "$ACTUAL" ] || { echo 'Gradle checksum mismatch' >&2; exit 1; }
 unzip -q -o "$CACHE_DIR/distribution.zip" -d "$CACHE_DIR"
fi
exec "$DIST_DIR/bin/gradle" "$@"
