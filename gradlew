#!/usr/bin/env sh
# Gradle wrapper startup script (simplified)
DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_VERSION=8.9
GRADLE_HOME="$HOME/.gradle/wrapper/dists/gradle-$GRADLE_VERSION-bin"
GRADLE_ZIP_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -f "$GRADLE_HOME/bin/gradle" ]; then
    mkdir -p "$GRADLE_HOME"
    TMP_ZIP="/tmp/gradle-$GRADLE_VERSION.zip"
    echo "Downloading Gradle $GRADLE_VERSION..."
    if [ ! -f "$TMP_ZIP" ]; then
        wget -q "$GRADLE_ZIP_URL" -O "$TMP_ZIP"
    fi
    unzip -q "$TMP_ZIP" -d "$HOME/.gradle/wrapper/dists/"
    DIST_DIR=$(ls -d "$HOME/.gradle/wrapper/dists/gradle-$GRADLE_VERSION"*/ 2>/dev/null | head -1)
    mv "$DIST_DIR" "$GRADLE_HOME" 2>/dev/null || true
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
