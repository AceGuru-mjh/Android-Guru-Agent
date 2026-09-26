# VENDOR.md — apex-vt-native vendoring contract

## Upstream

- Repository: https://github.com/AceGuru-mjh/apex-vt-native
- Vendored commit: `19e8fb592875e0f3b4a165bdc107db7095e50a4d` (v0.2 foundation — upstream PR #1, CI green: host tests + 3×NDK)
- License: MIT (upstream LICENSE applies to the vendored sources)

## What is vendored

| This module | Upstream |
|---|---|
| `src/main/cpp/vt-native/include/` | `include/` (public headers) |
| `src/main/cpp/vt-native/src/` | `src/` (engine implementation + JNI bridge) |
| `src/main/cpp/vt-native/CMakeLists.txt` | `CMakeLists.txt` (library build script) |
| `src/main/kotlin/.../NativeVtCore.kt` | `kotlin/.../NativeVtCore.kt` (adapted: real imports, no `@file:JvmName`, no finalize) |

**Not** vendored: `tests/` (370 host tests: 129 v0.1 parity + 241 v0.2 foundation; fuzz + benchmark) and
`.github/workflows/` — they run in the upstream repo's CI on every push.

## Rules

1. **Vendored C++ sources are byte-identical to the upstream commit.**
   Bug fixes and features land upstream first, then a re-vendor commit
   replaces the tree wholesale (`git rm -r` + `cp -r`) and updates the
   commit SHA above.
2. Kotlin-side glue (`VtEngineFactory`, `NativeVtCore` adaptations,
   `:terminal-native` Gradle/CMake wiring) is maintained **here**.
3. The upstream repo is the single source of truth for engine semantics;
   behavioral parity with the Kotlin `TerminalCore` is enforced by the
   upstream 129-case golden suite (ported from `terminal-emulator` tests).
4. ABI: `libvt_native.so` — JNI symbols are statically bound by
   `NativeVtCore.external fun` declarations; renaming either side breaks
   at runtime, not compile time. Keep the JNI name map in sync:
   `Java_com_apex_agent_vtnative_NativeVtCore_native*`.

## Why vendored (not a binary artifact)

- Same pattern as the existing `:terminal-emulator` (vendored Kotlin VT
  engine) and the vendored proot/loader binaries.
- The APK CI (`apk.yml`) compiles the C++ for every ABI on every build —
  no binary provenance questions, no stale .so risk.
- Debuggability: sources are right here, with full symbol info in debug
  builds.
