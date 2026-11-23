#!/usr/bin/env bash
# Writes the in-app update manifests (update/latest-<abi>.json) for a
# PocketTone release. The app checks
#   https://raw.githubusercontent.com/pockettone/pockettone/main/update/latest-<abi>.json
# every 6 hours (and from Options > Check for updates), so commit and push
# these to main AFTER the GitHub release with the APKs is published.
#
# Usage: scripts/pockettone-update-manifests.sh <dir with signed APKs> <release tag>
#   e.g. scripts/pockettone-update-manifests.sh /c/Apps/Clamshell/release-8.37.2-hotfix2 v8.37.2-pockettone.3
#
# APK names must look like PocketTone-<version>-<abi>.apk (abi = arm64-v8a,
# armeabi-v7a or universal) and be uploaded to the release under those names.
set -euo pipefail

dir="$1"
tag="$2"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
out="$repo_root/update"
mkdir -p "$out"

aapt2="$(ls -d "${LOCALAPPDATA:-$HOME/AppData/Local}"/Android/Sdk/build-tools/*/aapt2* 2>/dev/null | sort | tail -1)"
[ -n "$aapt2" ] || { echo "aapt2 not found in the Android SDK build-tools" >&2; exit 1; }

for abi in arm64-v8a armeabi-v7a universal; do
  apk="$(ls "$dir"/PocketTone-*-"$abi".apk 2>/dev/null | head -1)"
  if [ -z "$apk" ]; then echo "skip $abi: no APK"; continue; fi
  badging="$("$aapt2" dump badging "$apk" | head -1)"
  code="$(echo "$badging" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
  name="$(echo "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
  sum="$(sha256sum "$apk" | cut -d' ' -f1)"
  file="$(basename "$apk")"
  # uploadTimestamp stays 0: updates are decided by versionCode alone, so
  # bump currentHotfixVersion in app/build.gradle.kts for every release.
  cat > "$out/latest-$abi.json" <<JSON
{
  "versionCode": $code,
  "versionName": "$name",
  "url": "https://github.com/pockettone/pockettone/releases/download/$tag/$file",
  "sha256sum": "$sum",
  "uploadTimestamp": 0
}
JSON
  echo "wrote update/latest-$abi.json ($name, $code)"
done
