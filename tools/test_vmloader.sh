#!/bin/bash
# Local x86_64 test of native/vmloader.c with a busybox rootfs. Needs: busybox-static, ziglang.
set -e; cd "$(dirname "$0")/.."
python3 -m ziglang cc -target x86_64-linux-musl -static -O2 -o /tmp/vmloader-x64 native/vmloader.c
R=/tmp/rootfs; rm -rf $R; mkdir -p $R/bin $R/etc $R/lib64 $R/lib/x86_64-linux-gnu
cp "$(which busybox)" $R/bin/busybox; ln -s /bin/busybox $R/bin/sh; ln -s /bin/busybox $R/bin/cat; echo guest > $R/etc/hostname
cp /bin/ls $R/bin/ls; for l in $(ldd /bin/ls | grep -o '/[^ ]*'); do mkdir -p $R$(dirname $l); cp -L $l $R$l; done
[ "$(/tmp/vmloader-x64 -r $R -- /bin/sh -c 'cat /etc/hostname')" = guest ] && echo "ok: rootfs + absolute symlink"
/tmp/vmloader-x64 -r $R -- /bin/ls / | grep -q lib64 && echo "ok: dynamic binary via guest loader"
/tmp/vmloader-x64 -r $R -- /bin/sh -c 'cat /etc/os-release' 2>&1 | grep -q "can't open" && echo "ok: host files hidden"
# readlink must not leak the host rootfs path; crash_dump helper must be blocked; crash report must name the function
[ "$(/tmp/vmloader-x64 -r $R -- /bin/sh -c 'cat /etc/hostname; exec 3</etc/hostname; readlink /proc/self/fd/3' | tail -1)" = /etc/hostname ] && echo "ok: readlink hides host path"
