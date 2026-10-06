// apex-vt-fastpath — host-maintained render fast path for the vendored VT engine.
//
// T95 (Android-Guru-Agent): collapses the engine's per-cell FlatSnapshot into
// STYLE-RUNS (merge key = fg / bg / flags / link — byte-parity with the Kotlin
// TerminalRowRun.collapse / terminal-emulator RenderRuns.deriveRow) and encodes
// them as one flat int array for a single JNI hand-off. This removes the old
// per-cell JVM decode (one RenderCell object + one String per cell per frame)
// from the 33ms render path: the JVM now decodes lazily, per ROW, and only for
// rows the UI actually touches (~visible rows).
//
// Vendoring contract (see ../VENDOR.md): this directory is HOST-MAINTAINED and
// is compiled into the SAME libvt_native.so via target_sources() from the
// module-level CMakeLists.txt — the vendored tree (src/main/cpp/vt-native) is
// byte-identical to upstream and only its PUBLIC API (apex/vt/vt_engine.h) is
// consumed here. Engine semantics stay upstream-owned.
//
// Flat layout (int32 units):
//   [0..31]   header — identical to the v0.2 cell snapshot (vt_jni.cpp
//             nativeSnapshotCells docs; Kotlin NativeVtCore decodes both).
//   [32]      visibleRunRowCount
//   then per visible row: [runCount], then per run:
//             [colStart, colSpan, fg, bg, flags, link, textLen,
//              textLen × UTF-16 code units (one unit per int)]
//   then      [scrollbackRunRowCount] + the same per-row run encoding.
//   trailer   [linkIdCount, linkId × linkIdCount] — distinct OSC 8 ids that
//             actually appear in the run regions, ascending (T94 semantics:
//             dangling ids never enter the host linkTable).
//
// Text rules (parity with the Kotlin collapse):
//   * FLAG_HIDDEN cells emit ONE space per cell (combining marks dropped);
//   * otherwise base code point + combining marks, UTF-16 encoded
//     (supplementary code points → surrogate pairs);
//   * wide trails never appear (the engine's FlatRow already folds them into
//     the wide lead; a wide cell contributes colSpan 2).
#pragma once

#include <cstdint>
#include <vector>

#include "apex/vt/vt_engine.h"

namespace apex::vt::fastpath {

// Build the flat run snapshot for [engine] with at most [maxScrollbackLines]
// scrollback rows (0 = none; negative is clamped to 0). Appends into [out]
// (cleared first). Thread-safety: same contract as Engine::renderSnapshot —
// callers serialize engine access (RealVirtualTerminal.engineLock).
void buildRunSnapshotFlat(Engine& engine, int maxScrollbackLines,
                          std::vector<int32_t>& out);

}  // namespace apex::vt::fastpath
