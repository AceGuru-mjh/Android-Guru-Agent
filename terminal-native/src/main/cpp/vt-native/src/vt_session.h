// apex-vt-session — strictly validated binary session codec (save/load).
//
// Why: Android can kill the process at any moment (low memory / background
// eviction). The host engine snapshots its full observable state (visible
// grid, scrollback history, modes, style table, title, mouse/charset state)
// into a SessionState; save() serializes it into a self-describing blob and
// load() restores it with exhaustive validation — persisted bytes are never
// trusted. The engine-side fill-in/restore wiring is owned by the
// integration layer (main task); this module owns only the wire format and
// the codec. The format is versioned (kFormatVersion) and section-based so
// it can evolve without invalidating old files prematurely.
//
// Wire format summary (authoritative layout table in vt_session.cpp):
//   header  : magic u32 | version u16 | payloadLen u32 | crc32(payload) u32
//   payload : fixed-order sections, each framed as `u32 bodyLen | body`
#pragma once

#include "apex/vt/vt_types.h"  // public Style / CursorShape
#include "vt_types.h"          // internal Cell (src/vt_types.h)

#include <cstdint>
#include <string>
#include <vector>

namespace apex::vt::session {

constexpr uint32_t kMagic = 0x41565431u;  // "AVT1" little-endian
constexpr uint16_t kFormatVersion = 1;

constexpr int kMaxRows = 1024, kMaxCols = 512;
constexpr int kMaxScrollbackSave = 100000;  // scrollback line cap (load enforces)
constexpr size_t kMaxTitleBytes = 4096;
constexpr size_t kMaxStyles = 4096;  // mirrors StyleTable::kMaxStyles
constexpr size_t kMaxSgrStack = 16;

// A combining-mark attachment: `cps` are the combining code points stacked
// onto the base cell at (row, col). Bounded to 16 cps per mark on load.
struct Mark {
  int32_t row, col;
  std::vector<uint32_t> cps;
};

// One scrollback line: a full-width (cols) cell row plus its combining
// marks. `marks[i].row` is a scrollback-buffer index in [0, lineCount).
struct SessionRow {
  std::vector<Cell> cells;
  std::vector<Mark> marks;
};

// Full persistent snapshot of one session. `styles[0]` is the default style
// and `Cell::style` is an index into `styles`. `scrollback` is oldest-first
// and must already be truncated to kMaxScrollbackSave lines by the caller.
// rows == cols == 0 is the canonical empty state (grid / scrollback /
// gridMarks / tabStops all empty); one of rows/cols being zero is invalid.
struct SessionState {
  int32_t rows = 0, cols = 0;
  int32_t cursorRow = 0, cursorCol = 0;
  bool wrapPending = false;  // deferred wrap at end of line
  bool cursorVisible = true, applicationCursor = false;
  bool bracketedPaste = false, alternateScreen = false;
  bool reverseVideo = false, originMode = false;
  CursorShape cursorShape = CursorShape::kBar;
  int32_t scrollTop = 0, scrollBottom = 0;  // DECSTBM; 0,0 = full screen
  int32_t savedCursorRow = 0, savedCursorCol = 0;  // DECSC
  bool savedWrapPending = false, savedOriginMode = false;
  std::vector<Style> styles;  // styles[0] = default; Cell::style indexes it
  std::vector<Cell> grid;     // rows*cols, row-major flat
  std::vector<Mark> gridMarks;          // combining marks on the visible screen
  std::vector<SessionRow> scrollback;   // oldest first (truncated at save)
  int64_t scrollbackLinesEver = 0;
  std::vector<uint8_t> tabStops;  // size == cols; default every 8 columns
  std::string title;              // UTF-8
  bool g0DecGraphics = false, g1DecGraphics = false, shiftOutG1 = false;
  uint8_t mouseMode = 0;      // 0 off, 1 x10, 2 normal, 3 button, 4 any
  uint8_t mouseEncoding = 0;  // 0 x11, 1 utf8, 2 sgr, 3 urxvt
  uint32_t lastPrintable = 0;  // REP support
  std::vector<Style> sgrStack;  // XTPUSHSGR stack
};

// Encodes: header(magic u32 | version u16 | payloadLen u32 | crc32 u32) +
// payload. The payload is split into sections (each with a u32 length
// prefix): dims / cursor / modes / styles / grid / marks / scrollback /
// tabs / title / charsets / mouse / misc / sgr — order fixed, format
// evolvable. All integers are fixed-width little-endian (u8/u16/u32/i32/
// i64). save() does no content validation (the caller is trusted: keep
// mark cps <= 16, cell style ids < styles.size(), scrollback rows of
// exactly cols cells and <= kMaxScrollbackSave lines).
bool save(const SessionState& s, std::vector<uint8_t>* out);

// 严格校验：magic/版本/长度/枚举值域/rows/cols 范围/cell 数=rows*cols/
// style 数<=kMaxStyles/gridMark 与 scrollback mark 的行列越界/
// tabStops 大小==cols/title 长度<=kMaxTitleBytes/scrollback 行 cells==cols/
// cps 数每 mark <= 16/sgrStack<=16/scrollback 行数<=kMaxScrollbackSave。
// Additionally enforced: boolean fields are 0/1, every cell style id stays
// inside the styles table, every section body is exactly consumed and the
// payload has no trailing bytes. Any out-of-range value or truncation →
// false, `out` untouched (callers may clear it first).
bool load(const uint8_t* data, size_t len, SessionState* out);

// CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320), table-driven.
uint32_t crc32(const uint8_t* data, size_t len);

}  // namespace apex::vt::session
