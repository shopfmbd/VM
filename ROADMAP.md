# Road to a real Android guest (VPhoneGaGa-style)

Goal: boot a real Android ROM's userspace inside the app, without root, sharing the phone's kernel.
Each stage has a test you can run before moving on. Stages 4+ are the hard wall; nothing past stage 1 exists yet.

| # | Stage | Status |
|---|-------|--------|
| 0 | Container: run real APKs on the host framework (`container/`) | built, unverified on device |
| 1 | `native/vmloader.c`: ptrace path-rewriting runner (rootfs becomes `/`), bionic `linker64` + `#!` handling, bind mounts, getcwd fixup. Tested on x86_64 with a glibc/busybox rootfs. arm64 builds but is untested. | built |
| 2 | Rootfs import: tar/zip now; next ext4 / sparse / erofs `system.img`, `vendor.img`, `product.img`, `apex` unpack inside the app | tar/zip done |
| 3 | Bionic bring-up: `toybox`/`sh` run on a phone (confirmed). Guest ART (`dalvikvm64`) runs Java: confirmed under qemu, not yet on a phone. Still missing: `/dev/__properties__` and a property service, `app_process`, a boot image for speed | in progress |
| 4 | **Binder.** Guest `servicemanager` needs its own binder context. Host `/dev/binder` is shared, and apps can't create binderfs, so this needs a userspace binder implementation (intercept `ioctl` on binder fds and serve the protocol), as Virtual Master does with native hooks | not started, uncertain |
| 5 | Mini init: start `servicemanager`, `hwservicemanager`, `zygote64`, `system_server` | not started |
| 6 | Display and input: guest SurfaceFlinger/gralloc output to a `SurfaceView`, touch and keyboard in | not started |
| 7 | Networking, audio, sensors, camera, GPS as virtual HALs | not started |
| 8 | Speed and stability: seccomp-bpf instead of full-syscall ptrace, 32-bit guests | not started |

## Known stage-1 limits
- Needs `targetSdkVersion 28`: Android 10+ blocks executing files from an app's data dir for apps targeting 29+.
- Intercepts only path syscalls. `/proc/self/exe`, `readlink` of host paths and `statfs` results still show host paths.
- A guest program run through the linker sees `argv[0]` as the path it was started with (bionic linker behaviour).
- 64-bit guests only. No device nodes (no root, so no `mknod`).
- ptrace is slow (every syscall stops twice). Fine for bring-up, too slow for a full system until stage 8.

## What a shipping product of this kind looks like (static analysis of VPhoneOS 5.2.1, architecture only)
Observed from file lists, ELF metadata and strings. No code, ROM or asset from it is used here.
- **The ROM is not in the APK.** It is downloaded and unpacked at runtime (strings mention Android 10/12/14/15 ROMs in 32- and 64-bit, and some versions behind a paid tier).
- **Loader executables** (`libloader*.so`) are PIE programs shipped as native libs and run from `nativeLibraryDir`, the same trick as `libvmloader.so`. They start with their own libc init and `dlopen`/`mmap`/`mprotect` a "user kernel" library.
- **"UserKernel" libraries** (`libuserkernel*.so`, about 7 MB each, 64/32-bit and emulated-CPU variants) are the core. Their strings show they provide `/binder`, `/hwbinder`, `/vndbinder`, the zygote socket, a fake `/dev/block/.../by-name/system`, and a path-redirect table. The guest's real `app_process64`, `surfaceflinger`, `debuggerd` and `linker64` run on top. Exports are `main`, `shell_process`, `init_kernel_api`, `notify_rendered`, `inject_input_event`: the host app starts it, gets frames out, and pushes touch/keys in.
- Cross-process memory access (`process_vm_readv/writev`) and `socketpair` are imported, so guest processes are separate host processes talking over sockets.
- **Extras:** an x86/x64 instruction translator, an X server and Wine/DXVK (Windows games), a Vulkan driver, a goldfish-style GL renderer.

So the "VM" is loader + user-space kernel (binder, sockets, device nodes) + a downloaded ROM, not a hypervisor. In this project that maps to stages 1 (loader), 4 (binder / user kernel) and 6 (display/input).
Not determined: how it intercepts syscalls (no seccomp/SIGSYS strings were found).

## Guest rootfs used for bring-up
Built from the phh-treble AOSP 12.1 arm64 A/B vanilla GSI (v416, raw ext4): `debugfs rdump`, then every APEX payload unpacked into `/apex/<name>`,
`/apex/apex-info-list.xml` written, and `/linkerconfig/ld.config.txt` generated with the guest's own `linkerconfig`.
Checked with `chroot` + qemu-user: bionic `toybox` and `mksh` run; `ro.build.version.release=12`. Not yet checked through `vmloader` (ptrace does not work under qemu-user).
- `android12-guest-core.tar.gz` (185 MB): bin, lib, lib64, etc, runtime/art/i18n/tzdata/conscrypt APEX. Enough for stage 3.
- `android12-guest-FULL.tar.gz` (399 MB): adds framework, fonts, all APEX payloads. Needed from stage 5.
