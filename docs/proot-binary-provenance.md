# PRoot Binary Provenance (P71)

This document records the origin, version, and licensing of every prebuilt binary
distributed inside `platform/terminal/src/main/jniLibs/`.

## Why prebuilt?

`nativeLibraryDir` is the only app-controlled directory on Android that is
**guaranteed executable** (SELinux `execute` on `apk-lib` files). This is the
standard distribution model used by Termux, UserLAnd, and Andronix for PRoot.
The v1 source is the official Termux package repository (statically verified
against the repo's `Packages` index SHA-256 digests).

## Files and versions

| jniLibs file | Origin (Termux package) | Version | Source |
|---|---|---|---|
| `libproot.so` | `proot` | 5.1.107.92 | https://packages.termux.dev/apt/termux-main/pool/main/p/proot/ |
| `libproot-loader.so` | `proot` (libexec/proot/loader) | 5.1.107.92 | same |
| `libproot-loader32.so` | `proot` (libexec/proot/loader32) | 5.1.107.92 | same (64-bit ABIs only — runs 32-bit guests) |
| `libtalloc.so` | `libtalloc` | 2.4.3 | https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/ |
| `libandroid-shmem.so` | `libandroid-shmem` | 0.7 | https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/ |

ABIs bundled: `arm64-v8a`, `armeabi-v7a`, `x86_64` (matches the module's
`ndk.abiFilters`).

Binaries were built by the Termux project with NDK r29, minSdk 24 (compatible
with this app's minSdk 26), and dynamically linked against the Android bionic
`libc.so` plus the two bundled libraries above.

Integrity: `platform/terminal/proot-binaries.sha256` pins every file's SHA-256.
A CI step in `static-analysis` re-verifies all digests on every build.

## Runtime library resolution

`libproot.so` declares `DT_NEEDED` entries `libtalloc.so.2` and
`libandroid-shmem.so`. Android packaging requires jniLibs entries to be named
`lib*.so`, so `libtalloc.so` is bundled under that name and
`PRootHostEnvironment` creates a `libtalloc.so.2 -> <nativeLibraryDir>/libtalloc.so`
symlink in an app-owned staging directory at first use; `LD_LIBRARY_PATH` for
the PRoot process points at that staging directory. The PRoot guest loader is
passed explicitly via `PROOT_LOADER` / `PROOT_LOADER_32`, so its on-disk name
is irrelevant.

## Licensing

- **proot** — GPLv2+ (https://github.com/proot-me/proot). Distributing the
  binary requires offering the corresponding source. Source for version
  5.1.107.92 is available at the proot-me GitHub repository and the Termux
  packaging repository (https://github.com/termux/proot). The corresponding
  source offer: this app's repository README links both sources; the binary
  itself is byte-identical to the official Termux build (verifiable against
  the checksums above).
- **talloc** — LGPL-3.0 (https://talloc.samba.org). Dynamically linked;
  the LGPL requires source availability for the library and the ability to
  relink. Source for 2.4.3: https://www.samba.org/ftp/talloc/.
- **libandroid-shmem** — MIT (https://github.com/termux/libandroid-shmem).

Upgrade policy: binaries are only replaced by updating this document, the
checksum manifest, and the two together in one commit. Never modify a binary
in place.

## v2 plan (from the implementation plan doc, §5.1)

Build proot + talloc statically from source in CI to drop the Termux runtime
dependency set (and shrink to a single self-contained binary). v1 ships the
Termux build because it is battle-tested on the exact ABI matrix this app
targets.
