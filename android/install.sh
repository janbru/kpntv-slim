#!/bin/sh
# Installs the release APK on the TV, compiles it ahead of time and starts it.
# A fresh install otherwise runs interpreted until Android's idle-time compile gets to it, which makes the
# first starts and zaps slower than they need to be.
#   ./install.sh [device]      (default 192.168.2.21:5555; build first with ./gradlew assembleRelease)
set -e
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
DEV="${1:-192.168.2.21:5555}"
cd "$(dirname "$0")"
"$ADB" -s "$DEV" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" -s "$DEV" shell cmd package compile -m speed -f nl.kpn.slim
"$ADB" -s "$DEV" shell am start -n nl.kpn.slim/.MainActivity
