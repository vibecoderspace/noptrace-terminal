# noptrace Terminal

A minimal Android terminal that runs a real Alpine Linux shell through
[noptrace](https://github.com/vibecoderspace/noptrace), the ptrace-free
chroot/bind emulation engine for AArch64 Android.

There is no PRoot, no `libnoptrace.so` executable, and no `execve()` of
anything under app storage. The engine is compiled into `libtermux.so` and
entered in the `fork()`ed PTY child, the same way
[ghostty-android-terminal](https://github.com/sylirre/ghostty-android-terminal)
embeds `chroot-ng`.

## What it does

- Downloads a pinned Alpine minirootfs (4 MB, SHA-256 verified) on first run.
- Extracts it with a small Java tar reader into `filesDir/rootfs`.
- Starts `/bin/sh -l` inside the rootfs through the embedded noptrace engine.
- Gives you a full-screen terminal with software keyboard, pinch-to-zoom,
  copy/paste, and a compact Ctrl/Alt/Esc/Tab/arrow key row.

## Requirements

- Android 8.0+ (`minSdk 26`), **arm64-v8a only**.
- An arm64 device or arm64 emulator image. `noptrace` runs guest AArch64 code
  directly, so an x86_64 emulator cannot run it.

## Build

Builds are done in CI; see `.github/workflows/build.yml`. Locally:

```sh
git clone --recurse-submodules https://github.com/vibecoderspace/noptrace-terminal
cd noptrace-terminal
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The build needs Android SDK platform 37.2, build-tools 37.0.0, CMake 3.22.1
and NDK 28.2.13676358.

## Architecture

```
app/                  one Activity, rootfs installer, key row
terminal-emulator/    vendored Termux VT engine; libtermux.so is built from
                      src/main/jni by CMake
terminal-view/        vendored Termux terminal view
native/noptrace/      git submodule, pinned to v1.6.0-noptrace.1
```

`libtermux.so` contains:

- `termux.c` — PTY creation, fork, `createSubprocessEngine()`, wait/kill
- `noptrace_embed.c` — reconstructs the standalone entry contract and calls
  `cng_main()`
- the noptrace static library, compiled freestanding with libc names renamed

The guest environment is passed as `-E` arguments. Only `PATH=/system/bin`
and `TMPDIR=<filesDir>/tmp` (mode 0700) are given to the host process.
`/proc`, `/dev` and `/dev/shm` are synthesized by the engine; only `/sys` is
bound.

The session argv is:

```text
noptrace --link2symlink --fake-id 0:0 --shared-proc --work-dir /root
         -b /sys:/sys -E HOME=/root -E USER=root ... <rootfs> /bin/sh -l
```

## Security model

This is an unprivileged, user-space sandbox, not a security boundary against
malicious guest code. Guest processes are real host processes owned by the
app UID. Killing the session kills its process group, but a guest daemon that
calls `setsid()` can escape that group.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Termux `terminal-emulator`/`terminal-view` are Apache-2.0. noptrace is
Apache-2.0. No GPL binaries are shipped.
