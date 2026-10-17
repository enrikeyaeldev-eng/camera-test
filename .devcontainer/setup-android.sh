#!/usr/bin/env bash
# Installs the Android SDK pieces this project needs. Runs once when the Codespace is created;
# safe to re-run by hand: bash .devcontainer/setup-android.sh
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"

# Keep in sync with app/processing build.gradle.kts and .github/workflows/android.yml
PACKAGES=(
  "platform-tools"
  "platforms;android-36"
  "build-tools;36.0.0"
  "ndk;27.2.12479018"
  "cmake;3.22.1"
)

sudo mkdir -p "$SDK"
sudo chown -R "$(id -u):$(id -g)" "$SDK"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Downloading Android command-line tools..."
  tmp="$(mktemp -d)"
  curl -fsSL "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" -o "$tmp/tools.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --licenses > /dev/null || true
"$SDKMANAGER" --install "${PACKAGES[@]}"

echo
java -version
echo "Android SDK ready in $SDK. Build with: ./gradlew assembleDebug"
