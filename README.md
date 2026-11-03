# OpenVM

Runs real Android apps (`.apk`, `.xapk`, `.apks`, `.apkm`) inside virtual phones, VirtualApp-style.
(The old DumbDroid / TinyOS demo engines were removed in 0.3.0.)

## Use
Main screen -> **New virtual phone** -> **Open** -> tap **+ Install APK** on the phone's home screen -> tap the app.
Each phone keeps its own apps and data (`filesDir/capps/phones/<id>`). One phone is loaded per process, so opening a different
phone restarts OpenVM. If something fails, tap **Log** on the phone's home screen and read/paste it.

## How it works
Apps are copied into private storage and run **in the OpenVM process** on the phone's real Android framework:
own `DexClassLoader`, own resources, each activity mapped onto a placeholder activity (`container/stub/*`, chosen by launch mode).
`ContainerHooks` replaces `ActivityThread.mInstrumentation` (launch/redirect) and `IPackageManager` (answers for installed apps);
hidden APIs are unlocked with the vendored LSPosed `HiddenApiBypass`. Services run in-process (`PluginServices`), providers are
initialised, storage is sandboxed in `capps/.../data`. If an activity cannot be created, the placeholder now shows the error
instead of closing silently.

## Guest runtime (experimental)
See ROADMAP.md. Main screen -> Guest runtime: import a rootfs tar/zip, run a command through `libvmloader.so`.

## Known limits
Apps that verify their own signature or use Play Integrity, Google Play services, manifest-declared broadcast receivers,
foreground/job services, widgets, multi-process apps, native libs not matching the phone's ABI (OpenVM runs 64-bit on modern
phones), system-action intent filters.

## Loader diagnostics
`-d` now freezes the failing-syscall list at the first SIGSEGV/SIGBUS/SIGILL/SIGABRT (it used to be flooded by `crash_dump64`), prints the *file* offset of the crash PC for symbolizing, and blocks the guest `crash_dump` helper (it cannot attach under ptrace and its fd-closing loop stalls traces). `-C` re-enables it. `readlink` results no longer leak the host rootfs path (bionic `realpath()` goes through `/proc/self/fd/N`).

## Stage 3 additions
PropArea writes <rootfs>/dev/__properties__ (bionic prop_area + property_info format; verified with the guest getprop under qemu-user). vmloader serves /dev/__properties__ from the rootfs.

## NOT verified
Compiles (javac vs android-34 jar); the native loader is tested on x86_64 only. Never built with Gradle or run on a device.

## Build
GitHub workflow `.github/workflows/build.yml` (Gradle), or `tools/build_local.sh` (javac + dx + aapt2 + apksigner).

## Log bridge (new)
With `-d`/`-s` the loader redirects the guest's `connect("/dev/socket/logdw")` to a socket next to the rootfs and prints every guest log line to stderr as `[guest-log] <prio> <tag>: <msg>`.
This shows ART's real error messages (the Android log of the app stays empty because the guest has no logd). The crash report also names the nearest ELF symbol of the crash PC/LR.
Verified on x86_64 by running the real Android 12 arm64 `dalvikvm64` + `hello.jar` through `vmloader` with qemu-user as the CPU translator ("Hello from ART inside the guest!").
`libvmloader.so` in `app/src/main/jniLibs/` is prebuilt: rerun `tools/build_native.sh` after editing `native/vmloader.c`.

## Stage 4-6 (this build)
- native/binder.h: userspace binder (transactions, handles, thread pool, death, fd passing, scatter-gather/FDA for hwbinder). Tested with native/test_binder.c.
- native/propsvc.h: property service on /dev/socket/property_service (writes the shared property area). Tested against the guest's real getprop.
- vmloader: -x PROG (background guest programs), -S NAME (init-style sockets, ANDROID_SOCKET_NAME), /dev/socket/* mapped into the rootfs.
- GuestBoot/BootActivity: app-side "init" (derive_classpath, servicemanager, hwservicemanager, vndservicemanager, installd, zygote64 --start-system-server).
- DisplayActivity + /dev/openvm/{fb,input}: shared framebuffer + touch ring; native/fbtest.c is the guest-side test.
NOT done: SurfaceFlinger/HWC/gralloc bridge into /dev/openvm/fb, so the real Android UI does not draw yet. Boot has never run on a device.

## Boot fixes (project11)
Found from a real boot log (servicemanager / hwservicemanager / installd / zygote64 all failing):
- `native/selinuxfs.h`: the guest gets its own fake, permissive selinuxfs under `<rootfs>/sys/fs/selinux` (status page, enforce=0, class/perm numbers, canned "allowed" answers for `access`), `statfs` reports SELINUX_MAGIC, and writes to `/proc/*/attr/*` (setcon) go to /dev/null. Fixes `servicemanager: Check failed: selinux_status_open(true) >= 0` and hwservicemanager's "Unknown class hwservice_manager / Missing permissions to add IBase".
- vmloader `-0` now also makes `capset`, `prctl(PR_CAPBSET_DROP)`, `mount`, `umount2`, `unshare(CLONE_NEWNS)` succeed (zygote needs them when it forks system_server and apps; an app can never do them). NOT yet verified on a device.
- `GuestBoot.prepareDirs`: `/data/misc/installd` and the other directories installd needs ("Failed to save version to /data/misc/installd/layout_version").
- `PropArea`: `dalvik.vm.jdwp-provider=none`, otherwise ART loads `libadbconnection.so`, which needs `libadbconnection_client.so` from the adbd APEX ("Plugin ... failed to load ... Runtime aborting"). Property area marker bumped to `.openvm-v4` so existing rootfs regenerate it.
- DisplayActivity `boot=true` (BootActivity -> "Boot + screen"): starts the boot and shows the live boot log on the screen until the guest paints a frame into /dev/openvm/fb.
- `libvmloader.so` is prebuilt: **rebuild it** (`pip install ziglang && tools/build_native.sh`, the GitHub workflow now does it). `GuestBoot` refuses to boot with an old loader (`libvmloader.so -V` must print `selinuxfs`). Only the x86_64 loader in this tree was rebuilt (with gcc, tested); the arm64 one is still the old binary.
- Still missing for a real Android UI: SurfaceFlinger/HWC/gralloc -> /dev/openvm/fb bridge, vold/netd/logd equivalents for system_server. The screen therefore shows the boot console, not the Android launcher.

## Boot log round 2 (project12)
Verified from a device log: servicemanager and hwservicemanager now start (selinuxfs fix works) and `-XjdwpProvider:none` is in effect.
New this round: no BR_INCREFS/BR_ACQUIRE for the context-manager binder node (installd SEGV in RefBase::incStrong), a frame-pointer backtrace on every crash
(`[signal]   #NN pc=...` lines), and binder logging (`-N`) on by default during boot. Still open: installd "getAndExecuteCommand returned unexpected error -13",
and zygote SEGV in `JVM_NativeLoad` (libopenjdkjvm.so) right after "I18n APEX ICU file found" -- the new backtraces should show the caller.

## Boot log round 3 (project13)
installd SEGV (`incStrong`, fault 0x4) root cause: BR_INCREFS/BR_ACQUIRE for an object the owner just passed to addService were queued on the owner *process*; the kernel queues
them on the *sending thread*, so they arrive before the transaction returns. Late delivery hit an already-freed object. Fixed in binder.h (`bref_for(..., ownerq)`).
Crash reports now also print all registers and the 10 instruction words around pc (aarch64) so the zygote crash in `JVM_NativeLoad` can be decoded.
