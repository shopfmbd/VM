#!/bin/bash
# Builds libvmloader.so (static executable, shipped as a "native lib" so Android extracts it and allows exec).
# Needs: pip install ziglang
set -e
cd "$(dirname "$0")/.."
for t in "aarch64-linux-musl arm64-v8a" "x86_64-linux-musl x86_64"; do
  set -- $t; mkdir -p app/src/main/jniLibs/$2
  python3 -m ziglang cc -target $1 -static -s -O2 -Wall -o app/src/main/jniLibs/$2/libvmloader.so native/vmloader.c
  python3 -m ziglang cc -target $1 -static -s -O2 -Wall -o app/src/main/jniLibs/$2/libbindertest.so native/test_binder.c
  python3 -m ziglang cc -target $1 -static -s -O2 -Wall -o app/src/main/jniLibs/$2/libfbtest.so native/fbtest.c
  python3 -m ziglang cc -target $1 -static -s -O2 -Wall -o app/src/main/jniLibs/$2/libinittest.so native/test_init.c
done
ls -la app/src/main/jniLibs/*/
