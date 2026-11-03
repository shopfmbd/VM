#!/bin/bash
# One-time setup for building OpenVM without Gradle (Ubuntu/Debian, needs apt + internet).
set -e
apt-get update -qq || true
apt-get install -y -qq aapt apksigner dalvik-exchange zipalign android-sdk-platform-23 default-jdk-headless
pip install -q --break-system-packages ziglang || pip install -q ziglang   # only needed to rebuild libvmloader.so
[ -f "$HOME/android-33.jar" ] || curl -sSL -o "$HOME/android-33.jar" https://raw.githubusercontent.com/Sable/android-platforms/master/android-33/android.jar
echo "now run: ANDROID_JAR=\$HOME/android-33.jar bash tools/build_local.sh"
