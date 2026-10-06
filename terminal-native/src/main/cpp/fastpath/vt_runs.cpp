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
//
// Text rules (parity with the Kotlin collapse):
//   * FLAG_HIDDEN cells emit ONE space per cell (combining marks dropped);
//   * otherwise base code point + combining marks, UTF-16 encoded
//     (supplementary code points → surrogate pairs);
//   * wide trails never appear (the engine's FlatRow already folds them into
//     the wide lead; a wide cell contributes colSpan 2).
#include "vt_runs.h"

#include <algorithm>

namespace apex::vt::fastpath {

namespace {

constexpr int32_t kRunHeaderInts = 32;  // byte-parity with v0.2 cell snapshot header

// Run merge key — parity with Kotlin TerminalRowRun.collapse / RenderRuns.deriveRow.
inline bool sameRunKey(const FlatCell& a, const FlatCell& b) {
  return a.fg == b.fg && a.bg == b.bg && a.flags == b.flags && a.link == b.link;
}
inline int32_t cellSpanOf(const FlatCell& c) {
  return (c.flags & kRfWide) ? 2 : 1;
}

// Append a code point as UTF-16 code units (one unit per int32 slot).
inline void appendUtf16(uint32_t cp, std::vector<int32_t>& out) {
  if (cp < 0x10000) {
    out.push_back(int32_t(cp));
    return;
  }
  cp -= 0x10000;
  out.push_back(int32_t(0xD800 | (cp >> 10)));
  out.push_back(int32_t(0xDC00 | (cp & 0x3FF)));
}

// Append the run text for cells [i, j): base cp + combining marks, or one
// space per hidden cell (HIDDEN drops the combining marks — Kotlin parity).
void appendRunText(const FlatCell* cells, size_t i, size_t j,
                   const std::vector<uint32_t>& combPool, std::vector<int32_t>& out) {
  const bool hidden = (cells[i].flags & kRfHidden) != 0;
  if (hidden) {
    for (size_t k = i; k < j; ++k) out.push_back(int32_t(' '));
    return;
  }
  for (size_t k = i; k < j; ++k) {
    const FlatCell& c = cells[k];
    appendUtf16(c.cp == 0 ? uint32_t(' ') : c.cp, out);
    for (uint32_t m = 0; m < c.combCount && c.combOff + m < combPool.size(); ++m) {
      appendUtf16(combPool[c.combOff + m], out);
    }
  }
}

// One row → [runCount, runs…] appended to [out]. Row cells are the engine's
// already-trimmed, trail-folded FlatRow projection. Non-zero run links are
// folded into [linkIds] (dedup — bounded by the engine's 64-entry table).
void encodeRunRow(const FlatRow& row, const std::vector<uint32_t>& combPool,
                  std::vector<int32_t>& out, std::vector<uint16_t>& linkIds) {
  const std::vector<FlatCell>& cells = row.cells;
  const size_t n = cells.size();
  // First pass: count runs (merge key scan) so the decoder layout is stable.
  size_t runCount = 0;
  if (n > 0) {
    runCount = 1;
    for (size_t k = 1; k < n; ++k) {
      if (!sameRunKey(cells[k], cells[k - 1])) ++runCount;
    }
  }
  out.push_back(int32_t(runCount));
  if (n == 0) return;

  size_t i = 0;
  int32_t col = 0;
  while (i < n) {
    size_t j = i + 1;
    int32_t span = cellSpanOf(cells[i]);
    while (j < n && sameRunKey(cells[j], cells[i])) {
      span += cellSpanOf(cells[j]);
      ++j;
    }
    const FlatCell& first = cells[i];
    out.push_back(col);                 // colStart
    out.push_back(span);                // colSpan
    out.push_back(int32_t(first.fg));   // fg (0 = theme default)
    out.push_back(int32_t(first.bg));   // bg
    out.push_back(int32_t(first.flags));
    out.push_back(int32_t(first.link)); // 1-based URI table index (0 = none)
    if (first.link != 0 &&
        std::find(linkIds.begin(), linkIds.end(), first.link) == linkIds.end()) {
      linkIds.push_back(first.link);
    }
    const size_t textLenSlot = out.size();
    out.push_back(0);                   // placeholder — patched after the units
    appendRunText(cells.data(), i, j, combPool, out);
    out[textLenSlot] = int32_t(out.size() - textLenSlot - 1);
    col += span;
    i = j;
  }
}

void encodeRunRegion(const std::vector<FlatRow>& rows,
                     const std::vector<uint32_t>& combPool, std::vector<int32_t>& out,
                     std::vector<uint16_t>& linkIds) {
  out.push_back(int32_t(rows.size()));
  for (const FlatRow& row : rows) encodeRunRow(row, combPool, out, linkIds);
}

// The 32-int header — byte-parity with the vendored vt_jni.cpp
// nativeSnapshotCells header (same FlatSnapshot fields, same slot order), so
// the Kotlin side decodes cell- and run-snapshots through one code path.
void encodeHeader(const FlatSnapshot& snap, const Engine& engine, std::vector<int32_t>& out) {
  out.push_back(int32_t(snap.rows));
  out.push_back(int32_t(snap.cols));
  out.push_back(int32_t(snap.cursorRow));
  out.push_back(int32_t(snap.cursorCol));
  out.push_back(snap.cursorVisible ? 1 : 0);
  out.push_back(int32_t(static_cast<int>(snap.cursorShape)));
  out.push_back(snap.alternateScreen ? 1 : 0);
  out.push_back(snap.applicationCursor ? 1 : 0);
  out.push_back(snap.bracketedPaste ? 1 : 0);
  out.push_back(snap.reverseVideo ? 1 : 0);
  out.push_back(int32_t(snap.scrollbackTotal));
  out.push_back(int32_t(snap.links.size()));
  out.push_back(int32_t(snap.scrollbackBase >> 32));
  out.push_back(int32_t(uint32_t(snap.scrollbackBase)));
  out.push_back(int32_t(snap.bellSeq >> 32));
  out.push_back(int32_t(uint32_t(snap.bellSeq)));
  out.push_back(int32_t(snap.selStartRow >> 32));
  out.push_back(int32_t(uint32_t(snap.selStartRow)));
  out.push_back(snap.selStartRow < 0 ? -1 : int32_t(snap.selStartCol));
  out.push_back(int32_t(snap.selEndRow >> 32));
  out.push_back(int32_t(uint32_t(snap.selEndRow)));
  out.push_back(snap.selEndRow < 0 ? -1 : int32_t(snap.selEndCol));
  out.push_back(int32_t(snap.searchHitCount));
  out.push_back(int32_t(snap.activeSearchHit));
  out.push_back(int32_t(snap.mouseMode));
  out.push_back(int32_t(snap.mouseEncoding));
  out.push_back(snap.focusReport ? 1 : 0);
  out.push_back(snap.altScroll ? 1 : 0);
  out.push_back(snap.applicationKeypad ? 1 : 0);
  out.push_back(int32_t(snap.modifyLevel));
  out.push_back(int32_t(engine.activeLinkCount()));
  out.push_back(0);  // reserved (alignment / future use)
}

}  // namespace

void buildRunSnapshotFlat(Engine& engine, int maxScrollbackLines,
                          std::vector<int32_t>& out) {
  out.clear();
  if (maxScrollbackLines < 0) maxScrollbackLines = 0;
  // renderSnapshot drains the bell — parity with both the v0.2 cell path and
  // the Kotlin TerminalCore.renderSnapshot (bellSeq consumed per snapshot).
  FlatSnapshot snap = engine.renderSnapshot(maxScrollbackLines);
  const size_t roughRows = snap.visible.size() + snap.scrollback.size();
  out.reserve(kRunHeaderInts + roughRows * 2 + roughRows * 8);

  std::vector<uint16_t> linkIds;  // distinct ids appearing in the run regions
  encodeHeader(snap, engine, out);
  encodeRunRegion(snap.visible, snap.combPool, out, linkIds);
  encodeRunRegion(snap.scrollback, snap.combPool, out, linkIds);
  // Trailing table (T94 semantics — dangling ids never enter the Kotlin
  // linkTable): [linkIdCount, ids ascending…].
  std::sort(linkIds.begin(), linkIds.end());
  out.push_back(int32_t(linkIds.size()));
  for (uint16_t id : linkIds) out.push_back(int32_t(id));
}

}  // namespace apex::vt::fastpath
