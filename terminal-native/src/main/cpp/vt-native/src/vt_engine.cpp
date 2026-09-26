// apex-vt-engine — UTF-8 decoder + VT parser + screen wiring + CSI dispatch.
//
// Exact semantic port of the Kotlin TerminalCore 2.0 (ATR 2.1 PR #53, incl.
// T82/T85 hardening). Every behavioral quirk documented in the Kotlin source
// is preserved here, including:
//   * wrapPending only set when a char actually lands in the last column;
//   * C1 bytes 0x80..0x9F passthrough as 7-bit equivalents;
//   * SGR colon sub-params (38:2:r:g:b / 38:2:cs:r:g:b / 4:x / 38:5:n);
//   * explicit CSI param 0 = default (xterm semantics);
//   * erase carries the current style (bg persistence), scroll does not;
//   * ED 3 clears only main-screen scrollback;
//   * DECSTR soft reset vs RIS full reset;
//   * DA1/DA2/DSR responses ("ESC[?6c", "ESC[>0;276;0c", CPR 1-based);
//   * bounded mutation list folding to FULL at 4096 entries;
//   * linesEverScrolled monotonic across clear()/eviction;
//   * only scrollUp with top==0 feeds the scrollback ring;
//   * DEC Special Graphics via ESC(0 G0 designation.
#include "apex/vt/vt_engine.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "vt_grapheme.h"
#include "vt_input.h"
#include "vt_lines.h"
#include "vt_mouse.h"
#include "vt_parser.h"
#include "vt_reflow.h"
#include "vt_screen.h"
#include "vt_search.h"
#include "vt_session.h"
#include "vt_style.h"
#include "vt_width.h"

namespace apex::vt {

namespace {

constexpr size_t kMaxPendingMutations = 4096;  // parity with Kotlin
constexpr size_t kMaxPendingClipboard = 8;     // parity with Kotlin
constexpr int kMaxRepRepeat = 1024;             // T85 REP guard
constexpr size_t kMaxLinks = 64;                // OSC 8 table bound
constexpr size_t kMaxSgrStack = 16;             // XTPUSHSGR bound
constexpr int kMaxSearchLinesRows = 4000;       // runaway wrapped line bound

// Session-only cell flag: carries the ROW wrap state through the grid /
// scrollback sections without a format change (set on the row's LAST cell by
// save, stripped by restore). Never set on live cells.
constexpr uint16_t kCellWrappedSession = 1u << 3;

// mouse::Type from the public int codes (see Engine::encodeMouseEvent).
mouse::Type mouseTypeOf(int type) {
  switch (type) {
    case 0: return mouse::Type::kPress;
    case 1: return mouse::Type::kRelease;
    case 2: return mouse::Type::kMotion;
    case 3: return mouse::Type::kWheelUp;
    case 4: return mouse::Type::kWheelDown;
    case 5: return mouse::Type::kWheelLeft;
    default: return mouse::Type::kWheelRight;
  }
}

mouse::Mode mouseModeOf(uint8_t m) {
  switch (m) {
    case 1: return mouse::Mode::kX10;
    case 2: return mouse::Mode::kNormal;
    case 3: return mouse::Mode::kButton;
    case 4: return mouse::Mode::kAny;
    default: return mouse::Mode::kOff;
  }
}

mouse::Encoding mouseEncodingOf(uint8_t e) {
  switch (e) {
    case 1: return mouse::Encoding::kUtf8;
    case 2: return mouse::Encoding::kSgr;
    case 3: return mouse::Encoding::kUrxvt;
    default: return mouse::Encoding::kX11;
  }
}

// ─── Base64 (standard alphabet) for OSC 52 — lenient-fail like Kotlin. ────
bool base64Decode(const std::string& in, std::string& out) {
  auto val = [](char c) -> int {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
  };
  out.clear();
  out.reserve(in.size() / 4 * 3 + 3);
  uint32_t buf = 0;
  int bits = 0;
  int pad = 0;
  for (char c : in) {
    if (c == '=') {
      ++pad;
      continue;
    }
    if (pad > 0) return false;  // data after padding → invalid
    int v = val(c);
    if (v < 0) return false;
    buf = (buf << 6) | uint32_t(v);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push_back(char((buf >> bits) & 0xFF));
    }
  }
  if (pad > 2) return false;
  return true;
}

// Lenient UTF-8 → UTF-8 pass-through used for clipboard text (Kotlin decodes
// to a String, replacing invalid sequences with U+FFFD).
std::string sanitizeUtf8(const std::string& in) {
  size_t i = 0;
  std::string out;
  out.reserve(in.size());
  while (i < in.size()) {
    uint8_t b = uint8_t(in[i]);
    size_t need = 0;
    uint32_t cp = 0;
    if (b < 0x80) {
      out.push_back(char(b));
      ++i;
      continue;
    } else if ((b & 0xE0) == 0xC0) {
      need = 2;
      cp = b & 0x1F;
    } else if ((b & 0xF0) == 0xE0) {
      need = 3;
      cp = b & 0x0F;
    } else if ((b & 0xF8) == 0xF0) {
      need = 4;
      cp = b & 0x07;
    } else {
      out.append("\xEF\xBF\xBD");  // U+FFFD
      ++i;
      continue;
    }
    if (i + need > in.size()) {
      out.append("\xEF\xBF\xBD");
      ++i;
      continue;
    }
    bool ok = true;
    for (size_t k = 1; k < need; ++k) {
      uint8_t cc = uint8_t(in[i + k]);
      if ((cc & 0xC0) != 0x80) {
        ok = false;
        break;
      }
      cp = (cp << 6) | (cc & 0x3F);
    }
    if (!ok || (need == 2 && cp < 0x80) || (need == 3 && cp < 0x800) ||
        (need == 4 && cp < 0x10000) || cp > 0x10FFFF ||
        (cp >= 0xD800 && cp <= 0xDFFF)) {
      out.append("\xEF\xBF\xBD");
      ++i;
      continue;
    }
    // re-encode
    if (cp < 0x800) {
      out.push_back(char(0xC0 | (cp >> 6)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
      out.push_back(char(0xE0 | (cp >> 12)));
      out.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    } else {
      out.push_back(char(0xF0 | (cp >> 18)));
      out.push_back(char(0x80 | ((cp >> 12) & 0x3F)));
      out.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    }
    i += need;
  }
  return out;
}

// UTF-16 (lenient, lone surrogates → U+FFFD) → UTF-8. Used for OSC payloads.
std::string utf16ToUtf8(const std::vector<char16_t>& in) {
  std::string out;
  out.reserve(in.size());
  size_t i = 0;
  while (i < in.size()) {
    uint32_t cp = in[i++];
    if (cp >= 0xD800 && cp <= 0xDBFF && i < in.size() && in[i] >= 0xDC00 && in[i] <= 0xDFFF) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (uint32_t(in[i]) - 0xDC00);
      ++i;
    } else if (cp >= 0xD800 && cp <= 0xDFFF) {
      cp = 0xFFFD;
    }
    if (cp < 0x80) {
      out.push_back(char(cp));
    } else if (cp < 0x800) {
      out.push_back(char(0xC0 | (cp >> 6)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
      out.push_back(char(0xE0 | (cp >> 12)));
      out.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    } else {
      out.push_back(char(0xF0 | (cp >> 18)));
      out.push_back(char(0x80 | ((cp >> 12) & 0x3F)));
      out.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    }
  }
  return out;
}

}  // namespace

// ─────────────────────────────────────────────────────────────────────────
// Engine::Impl — all state + the parser host callbacks.
// ─────────────────────────────────────────────────────────────────────────

struct Engine::Impl {
  Impl(int nRows, int nCols, int nMaxScrollback)
      : main(nRows, nCols, nMaxScrollback, true, styles),
        alt(nRows, nCols, 0, false, styles),
        maxScrollback(nMaxScrollback),
        rows(main.rows()),
        cols(main.cols()) {
    scrollBottom = rows - 1;
    tabStops.assign(size_t(cols), 0);
    resetTabStops();
  }

  // ── components ──
  StyleTable styles;
  Screen main;
  Screen alt;
  Screen* cur = &main;
  Parser<Impl> parser;
  int maxScrollback;

  // ── UTF-8 incremental decoder state ──
  uint8_t utfPending[4] = {0, 0, 0, 0};
  int utfCount = 0;
  int utfExpected = 0;

  // ── modes ──
  bool autoWrap = true;            // DECAWM (7)
  bool cursorVisible = true;       // DECTCEM (25)
  bool applicationCursor = false;  // DECCKM (1)
  bool originMode = false;         // DECOM (6)
  bool insertMode = false;         // IRM (4)
  bool bracketedPaste = false;     // (2004)
  bool reverseVideo = false;       // DECSCNM (5)
  bool alternateScreen = false;    // (47/1047/1049)
  bool newlineMode = false;        // LNM (ANSI 20)

  // ── cursor ──
  struct Cursor {
    int row = 0, col = 0;
    bool visible = true;
    bool wrapPending = false;
  } cursor, savedCursor;

  int scrollTop = 0, scrollBottom = 0;
  int rows, cols;

  // ── tabs ──
  std::vector<uint8_t> tabStops;

  // ── style ──
  uint16_t curStyle = 0;   // interned id (0 = DEFAULT)
  uint16_t savedStyle = 0;

  // ── misc ──
  std::string titleStr;
  bool hasTitle = false;
  enum class G0 { kAscii, kDecGraphics } g0 = G0::kAscii;
  CursorShape cursorShapeV = CursorShape::kBar;
  bool bellPending = false;
  int64_t bellSeq = 0;
  std::vector<std::string> clipboard;
  std::vector<Mutation> mutations;
  std::vector<uint8_t> responses;
  int lastBaseRow = 0, lastBaseCol = 0;
  int32_t lastPrintableCp = -1;

  // ── v0.2 foundation state (host-facing; never touched by the PTY hot path) ──
  bool applicationKeypad = false;  // DECKPAM (ESC =) / DECKPNM (ESC >)
  bool focusReport = false;        // mode 1004
  bool altScroll = false;          // mode 1007
  bool syncing = false;            // mode 2026 (fold mutations → one FULL)
  bool syncFullPending = false;
  uint8_t modifyLevel = 0;         // 0 none, 1/2 modifyOtherKeys, 3 Kitty
  uint8_t mouseModeV = 0;          // 0 off, 1 X10, 2 normal, 3 button, 4 any
  uint8_t mouseEncodingV = 0;      // 0 X11, 1 UTF-8, 2 SGR, 3 urxvt
  bool charProtected = false;      // DECSCA — new cells carry kCellProtected
  bool syncEndJustNow = false;     // 2026-l just folded — skip the parity mutation
  // OSC 8 hyperlink table (id = index + 1; 0 = no link). Bounded eviction:
  // the oldest entry's URI is cleared; stale spans resolve to no-link lazily.
  std::vector<std::string> linkUris;
  uint16_t activeLink = 0;
  // XTPUSHSGR / XTPOPSGR stack (interned style ids).
  std::vector<uint16_t> sgrStack;
  // Search hits (global rows — see FlatSnapshot::scrollbackBase) + cursor.
  std::vector<SearchMatchInfo> searchHits;
  int activeHit = -1;
  // Selection anchor/end in global rows (alt screen: screen rows, documented).
  bool selActive = false;
  int64_t selAnchorRow = -1, selEndRow = -1;
  int32_t selAnchorCol = 0, selEndCol = 0;

  // Reusable scratch (avoid per-feed allocations).
  std::string respondScratch;
  std::string osc52Scratch;

  // ── helpers ──
  int clampCol(int c) const { return c < 0 ? 0 : (c > cols - 1 ? cols - 1 : c); }
  int clampRow(int r) const {
    if (originMode) return r < scrollTop ? scrollTop : (r > scrollBottom ? scrollBottom : r);
    return r < 0 ? 0 : (r > rows - 1 ? rows - 1 : r);
  }
  int originRow(int param1Based) const {
    int p = param1Based - 1;
    if (originMode) {
      int v = scrollTop + p;
      return v < scrollTop ? scrollTop : (v > scrollBottom ? scrollBottom : v);
    }
    return p < 0 ? 0 : (p > rows - 1 ? rows - 1 : p);
  }

  void addMutation(MutationType type, int first, int last) {
    if (syncing) {  // mode 2026: batch output — fold everything into one FULL
      syncFullPending = true;
      return;
    }
    // BoundedMutationList parity: on overflow, clear + fold to FULL, then
    // append the new element (Kotlin folds to [FULL, element]).
    if (mutations.size() >= kMaxPendingMutations) {
      mutations.clear();
      mutations.push_back(Mutation{MutationType::kFull, 0, 0});
    }
    mutations.push_back(Mutation{type, first, last});
  }
  void mutationRows(int r) { addMutation(MutationType::kCells, r, r); }

  void beginSync() { syncing = true; syncFullPending = false; }
  void endSync() {
    syncing = false;
    if (syncFullPending) {
      syncFullPending = false;
      addMutation(MutationType::kFull, 0, 0);
    }
  }

  // Attach the active OSC 8 link to a freshly written cell run.
  void attachLink(int row, int colStart, int colEnd) {
    if (activeLink == 0) return;
    cur->attachLink(row, colStart, colEnd, activeLink);
  }

  void respond(const char* s) {
    responses.insert(responses.end(), reinterpret_cast<const uint8_t*>(s),
                     reinterpret_cast<const uint8_t*>(s) + std::strlen(s));
  }

  void resetTabStops() {
    for (int i = 0; i < cols; ++i) tabStops[size_t(i)] = (i % 8 == 0 && i > 0) ? 1 : 0;
  }
  int nextTab(int col) const {
    int c = col + 1;
    while (c < cols && !tabStops[size_t(c)]) ++c;
    return c > cols - 1 ? cols - 1 : c;
  }
  int prevTab(int col) const {
    int c = col - 1;
    while (c > 0 && !tabStops[size_t(c)]) --c;
    return c < 0 ? 0 : c;
  }
  void resizeTabs(int newCols) {
    std::vector<uint8_t> next(size_t(newCols), 0);
    for (int i = 0; i < newCols; ++i) next[size_t(i)] = (i % 8 == 0 && i > 0) ? 1 : 0;
    // Overlap against the ACTUAL tab-stop vector size — `cols` may already
    // have been updated to the new width by the caller (grow case used to
    // read past the old vector; caught by ASAN on the v0.2 reflow tests).
    int overlap = std::min(int(tabStops.size()), newCols);
    for (int i = 0; i < overlap; ++i) next[size_t(i)] = tabStops[size_t(i)];
    tabStops = std::move(next);
  }

  uint32_t mapCharset(uint32_t cp) const {
    if (g0 != G0::kDecGraphics) return cp;
    uint32_t mapped = decSpecialGraphics(cp);
    return mapped ? mapped : cp;
  }

  // ═══ UTF-8 decoder (Kotlin Utf8Decoder parity) ═══
  void feedCp(uint32_t cp) { parser.feed(cp, *this); }

  void decodePending() {
    uint32_t b0 = utfPending[0];
    uint32_t cp;
    switch (utfExpected) {
      case 2: cp = ((b0 & 0x1F) << 6) | (utfPending[1] & 0x3F); break;
      case 3: cp = ((b0 & 0x0F) << 12) | ((utfPending[1] & 0x3F) << 6) | (utfPending[2] & 0x3F); break;
      case 4:
        cp = ((b0 & 0x07) << 18) | ((utfPending[1] & 0x3F) << 12) | ((utfPending[2] & 0x3F) << 6) |
             (utfPending[3] & 0x3F);
        break;
      default: cp = 0xFFFD; break;
    }
    if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) ||
        (utfExpected == 2 && cp < 0x80) || (utfExpected == 3 && cp < 0x800) ||
        (utfExpected == 4 && cp < 0x10000)) {
      cp = 0xFFFD;
    }
    feedCp(cp);
  }

  // Returns false when the byte must be re-processed (invalid continuation).
  bool feedByte(uint8_t b) {
    if (utfCount == 0) {
      if (b < 0x80) {
        feedCp(b);
      } else if (b < 0xC0) {
        // T85: standalone C1 bytes (0x80..0x9F) pass through to the VT layer
        // (xterm 8-bit controls); 0xA0..0xBF → U+FFFD.
        feedCp(b < 0xA0 ? b : 0xFFFD);
      } else if (b < 0xE0) {
        utfPending[0] = b;
        utfCount = 1;
        utfExpected = 2;
      } else if (b < 0xF0) {
        utfPending[0] = b;
        utfCount = 1;
        utfExpected = 3;
      } else if (b < 0xF8) {
        utfPending[0] = b;
        utfCount = 1;
        utfExpected = 4;
      } else {
        feedCp(0xFFFD);  // invalid lead byte
      }
      return true;
    }
    if ((b & 0xC0) == 0x80) {
      utfPending[utfCount++] = b;
      if (utfCount == utfExpected) {
        decodePending();
        utfCount = 0;
        utfExpected = 0;
      }
      return true;
    }
    feedCp(0xFFFD);  // invalid continuation
    utfCount = 0;
    utfExpected = 0;
    return false;  // re-process this byte
  }

  // ═══ Parser host callbacks ═══

  void onPrintable(uint32_t cp) { putPrintable(mapCharset(cp)); }

  void onC0(uint32_t byte) { handleC0(byte); }

  void onCsi(const CsiSequence& seq) { handleCsi(seq); }

  void onOsc(int32_t code, const std::vector<char16_t>& data) { handleOsc(code, data); }

  void onEsc(char final, const char* intermediates, size_t nInter) {
    handleEsc(final, intermediates, nInter);
  }

  void onDcs(const std::vector<char16_t>&) { /* DCS ignored (§27) */ }
  void onUnknown() { /* safely ignore (§24) */ }

  // ═══ printable + wide char (Kotlin putPrintable parity) ═══
  void putPrintable(uint32_t cp) {
    int width = unicodeWidth(cp);
    if (width == 0) {
      // Zero width (combining / ZWJ / VS / emoji modifier): attach to the last
      // placed base cell — never an independent cell (§10/§11).
      cur->putCombining(lastBaseRow, lastBaseCol, cp);
      mutationRows(lastBaseRow);
      return;
    }

    if (insertMode) {  // IRM: shift cells right, cursor stays
      int r = cursor.row, c = cursor.col;
      insertCharsAtCursor(width);
      uint16_t fl = uint16_t(width == 2 ? kCellWideLead : 0);
      if (charProtected) fl = uint16_t(fl | kCellProtected);
      Cell cell{cp, curStyle, fl};
      cur->put(r, c, cell);
      attachLink(r, c, c + width);
      lastBaseRow = r;
      lastBaseCol = c;
      mutationRows(r);
      cursor.col = c + width > cols - 1 ? cols - 1 : c + width;
      cursor.wrapPending = false;
      return;
    }

    int prow = cursor.row, pcol = cursor.col;
    if (cursor.wrapPending && autoWrap) {
      cur->setRowWrapped(cursor.row, true);  // the row's content continues below
      ++prow;
      pcol = 0;
      cursor.wrapPending = false;
      if (prow > scrollBottom) {
        cur->scrollUp(1, scrollTop, scrollBottom);
        prow = scrollBottom;
      }
    }
    if (width == 2 && pcol >= cols - 1) {  // wide char at last column → wrap (§9)
      cur->setRowWrapped(cursor.row, true);
      ++prow;
      pcol = 0;
      if (prow > scrollBottom) {
        cur->scrollUp(1, scrollTop, scrollBottom);
        prow = scrollBottom;
      }
    }

    uint16_t fl = uint16_t(width == 2 ? kCellWideLead : 0);
    if (charProtected) fl = uint16_t(fl | kCellProtected);
    Cell cell{cp, curStyle, fl};
    cur->put(prow, pcol, cell);
    attachLink(prow, pcol, pcol + width);
    lastBaseRow = prow;
    lastBaseCol = pcol;
    lastPrintableCp = int32_t(cp);
    mutationRows(prow);

    if (width == 2 && pcol + 2 >= cols) {
      cursor.row = prow;
      cursor.col = cols - 1;
      cursor.wrapPending = autoWrap;
    } else {
      cursor.row = prow;
      cursor.col = pcol + width > cols - 1 ? cols - 1 : pcol + width;
      // T85: wrapPending only when the char actually lands in the last cell.
      cursor.wrapPending = autoWrap && (pcol + width >= cols);
    }
  }

  bool parserIsGroundAndIdle() const {
    return parser.inGround() && utfCount == 0 && !insertMode;
  }

  // ═══ Fast ASCII printable run (hot path) ═══
  // Semantics identical to putPrintable() for width-1 chars, but processes a
  // whole run with a cached row pointer and one mutation per touched row.
  // Only valid when: parser in GROUND, no pending UTF-8, IRM off.
  void printAsciiRun(const uint8_t* p, size_t n) {
    if (insertMode) {  // IRM shifts per char — degenerate to slow path
      for (size_t k = 0; k < n; ++k) putPrintable(p[k]);
      return;
    }
    const bool decGraphics = (g0 == G0::kDecGraphics);
    Cell blankCell{' ', styles.defaultId(), 0};
    int lastMutRow = -1;
    Cell* row = nullptr;
    RowExtras* rowEx = nullptr;
    int rowIdx = -1;
    for (size_t k = 0; k < n; ++k) {
      uint32_t cp = p[k];
      if (decGraphics && cp >= 0x60 && cp <= 0x7E) {
        uint32_t mapped = decSpecialGraphics(cp);
        if (mapped) cp = mapped;
      }
      int prow = cursor.row, pcol = cursor.col;
      if (cursor.wrapPending && autoWrap) {
        cur->setRowWrapped(cursor.row, true);
        ++prow;
        pcol = 0;
        cursor.wrapPending = false;
        if (prow > scrollBottom) {
          cur->scrollUp(1, scrollTop, scrollBottom);
          prow = scrollBottom;
        }
      }
      if (prow != rowIdx) {  // cache the row (changes rarely: every `cols` chars)
        rowIdx = prow;
        row = const_cast<Cell*>(cur->rowCells(prow));
        rowEx = const_cast<RowExtras*>(cur->combiningExtras(prow));
      }
      Cell& dst = row[pcol];
      if (pcol > 0 && (dst.flags & kCellWideTrail)) {
        row[pcol - 1] = blankCell;  // repair the wide lead (put() parity)
        if (rowEx) {
          rowEx->removeCol(uint16_t(pcol - 1));
          rowEx->invalidateLinkAt(uint16_t(pcol - 1));
        }
      }
      if (rowEx) rowEx->removeCol(uint16_t(pcol));
      if (rowEx) rowEx->invalidateLinkAt(uint16_t(pcol));
      dst.cp = cp;
      dst.style = curStyle;
      dst.flags = charProtected ? uint16_t(kCellProtected) : uint16_t(0);
      lastBaseRow = prow;
      lastBaseCol = pcol;
      lastPrintableCp = int32_t(cp);
      if (activeLink != 0) cur->attachLink(prow, pcol, pcol + 1, activeLink);
      if (pcol + 1 >= cols) {
        cursor.row = prow;
        cursor.col = cols - 1;
        cursor.wrapPending = autoWrap;
      } else {
        cursor.row = prow;
        cursor.col = pcol + 1;
        cursor.wrapPending = false;
      }
      if (prow != lastMutRow) {
        mutationRows(prow);
        lastMutRow = prow;
      }
    }
  }

  // IRM: shift cells right by [width] within the current row (at cursor).
  void insertCharsAtCursor(int width) {
    cur->insertChars(cursor.row, cursor.col, width);
  }

  // ═══ C0 controls (§4) ═══
  void handleC0(uint32_t byte) {
    switch (byte) {
      case 0x07:  // BEL
        bellPending = true;
        break;
      case 0x08:  // BS
        if (cursor.col > 0) --cursor.col;
        cursor.wrapPending = false;
        break;
      case 0x09:  // HT
        cursor.col = nextTab(cursor.col);
        cursor.wrapPending = false;
        break;
      case 0x0A:
      case 0x0B:
      case 0x0C: {  // LF/VT/FF
        if (newlineMode) cursor.col = 0;  // T82: LNM
        ++cursor.row;
        cursor.wrapPending = false;
        if (cursor.row > scrollBottom) {
          cur->scrollUp(1, scrollTop, scrollBottom);
          cursor.row = scrollBottom;
        }
        break;
      }
      case 0x0D:  // CR
        cursor.col = 0;
        cursor.wrapPending = false;
        break;
      default:
        break;  // other C0 ignored
    }
    mutationRows(cursor.row);  // parity: ALWAYS, even for ignored C0
  }

  // ═══ CSI dispatch (§5) ═══
  void handleCsi(const CsiSequence& seq) {
    const char final = seq.finalByte;
    switch (final) {
      case 'A': moveCursor(-seq.paramOrDefault(0, 1), 0); break;  // CUU
      case 'B': moveCursor(seq.paramOrDefault(0, 1), 0); break;   // CUD
      case 'C': moveCursor(0, seq.paramOrDefault(0, 1)); break;   // CUF
      case 'D': moveCursor(0, -seq.paramOrDefault(0, 1)); break;  // CUB
      case 'E':  // CNL
        cursor.row = clampRow(cursor.row + seq.paramOrDefault(0, 1));
        cursor.col = 0;
        cursor.wrapPending = false;
        break;
      case 'F':  // CPL
        cursor.row = clampRow(cursor.row - seq.paramOrDefault(0, 1));
        cursor.col = 0;
        cursor.wrapPending = false;
        break;
      case 'G':  // CHA
        cursor.col = clampCol(seq.paramOrDefault(0, 1) - 1);
        cursor.wrapPending = false;
        break;
      case 'd':  // VPA
        cursor.row = originRow(seq.paramOrDefault(0, 1));
        cursor.wrapPending = false;
        break;
      case 'H':
      case 'f': {  // CUP / HVP
        cursor.col = clampCol(seq.paramOrDefault(1, 1) - 1);
        cursor.row = originRow(seq.paramOrDefault(0, 1));
        cursor.wrapPending = false;
        break;
      }
      case 'J':  // ED / DECSED (selective — protected cells survive)
        if (seq.privateMarker == '?') eraseDisplaySelective(seq.param(0, 0));
        else eraseDisplay(seq.param(0, 0));
        break;
      case 'K':  // EL / DECSEL (selective)
        if (seq.privateMarker == '?') eraseLineSelective(seq.param(0, 0));
        else eraseLine(seq.param(0, 0));
        break;
      case 'S':  // SU
        cur->scrollUp(seq.paramOrDefault(0, 1), scrollTop, scrollBottom);
        break;
      case 'T':  // SD
        cur->scrollDown(seq.paramOrDefault(0, 1), scrollTop, scrollBottom);
        break;
      case 'L':  // IL
        cur->insertLines(cursor.row, seq.paramOrDefault(0, 1), scrollTop, scrollBottom);
        addMutation(MutationType::kInsertLines, cursor.row, scrollBottom);
        break;
      case 'M':  // DL
        cur->deleteLines(cursor.row, seq.paramOrDefault(0, 1), scrollTop, scrollBottom);
        addMutation(MutationType::kDeleteLines, cursor.row, scrollBottom);
        break;
      case 'P': deleteChars(seq.paramOrDefault(0, 1)); break;  // DCH
      case '@': insertChars(seq.paramOrDefault(0, 1)); break;  // ICH
      case 'X': {                                                // ECH
        int n = seq.paramOrDefault(0, 1);
        cur->eraseRow(cursor.row, cursor.col, cursor.col + n - 1, curStyle);
        mutationRows(cursor.row);
        break;
      }
      case 'm': applySgr(seq); break;  // SGR (incl. colon sub-params)
      case 'r': {                      // DECSTBM / DECERA / DECSACE
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '$') {
          eraseRectangle(seq);          // DECERA — plain rectangular erase
          break;
        }
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '*') {
          // DECSACE: attribute change extent (1 = stream, 2 = rectangle).
          // Tracked but only meaningful for DECCRA/DECFRA consumers that
          // implement area attributes — accepted for interop safety.
          break;
        }
        int t = seq.paramOrDefault(0, 1) - 1;
        int b = (seq.params.size() > 1 ? seq.paramOrDefault(1, rows) : rows) - 1;
        setScrollRegion(t, b);
        cursor.row = originMode ? scrollTop : 0;
        cursor.col = 0;
        break;
      }
      case '`':  // HPA
        cursor.col = clampCol(seq.paramOrDefault(0, 1) - 1);
        cursor.wrapPending = false;
        break;
      case 'a':  // HPR
        cursor.col = clampCol(cursor.col + seq.paramOrDefault(0, 1));
        cursor.wrapPending = false;
        break;
      case 'e':  // VPR
        cursor.row = clampRow(cursor.row + seq.paramOrDefault(0, 1));
        cursor.wrapPending = false;
        break;
      case 'b': {  // REP (T85) — repeat last printable
        int n = seq.paramOrDefault(0, 1);
        n = n < 0 ? 0 : (n > kMaxRepRepeat ? kMaxRepRepeat : n);
        if (lastPrintableCp > 0) {
          for (int i = 0; i < n; ++i) putPrintable(uint32_t(lastPrintableCp));
        }
        break;
      }
      case 'q':  // DECSCUSR (SP q) / DECSCA (" q — char protection)
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == ' ') {
          switch (seq.paramOrDefault(0, 0)) {
            case 3:
            case 4: cursorShapeV = CursorShape::kUnderline; break;
            case 5:
            case 6: cursorShapeV = CursorShape::kBar; break;
            default: cursorShapeV = CursorShape::kBlock; break;  // 0/1/2 + other
          }
        } else if (seq.intermediates.size() == 1 && seq.intermediates[0] == '"') {
          charProtected = (seq.paramOrDefault(0, 0) == 1);  // DECSCA
        }
        break;
      case 'c':  // DA1 / DA2 / modifyOtherKeys set
        if (seq.privateMarker == '>') {
          if (!seq.params.empty()) {
            // CSI > Ps;Pm c — xterm modifyOtherKeys negotiation: level 1/2,
            // 0 disables. (CSI > 4;2 c is the canonical form.)
            int32_t level = seq.params.size() > 1 ? seq.param(1, 1) : seq.param(0, 1);
            modifyLevel = level <= 0 ? uint8_t(0) : (level == 1 ? uint8_t(1) : uint8_t(2));
            if (modifyLevel == 2) modifyLevel = 2;
          } else {
            respond("\x1B[>0;276;0c");
          }
        } else {
          respond("\x1B[?6c");
        }
        break;
      case 'n':  // DSR
        switch (seq.paramOrDefault(0, 0)) {
          case 5: respond("\x1B[0n"); break;
          case 6:
            respondScratch.clear();
            respondScratch += "\x1B[";
            respondScratch += std::to_string(cursor.row + 1);
            respondScratch += ';';
            respondScratch += std::to_string(cursor.col + 1);
            respondScratch += 'R';
            respond(respondScratch.c_str());
            break;
          default: break;
        }
        break;
      case 'p':  // DECSTR / DECRQM
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '!') {
          softReset();
        } else if (seq.intermediates.size() == 1 && seq.intermediates[0] == '$') {
          decrqm(seq.paramOrDefault(0, 0), seq.privateMarker == '?');
        }
        break;
      case 'u':  // Kitty keyboard protocol (CSI = / > / < … u) / ANSI.SYS restore
        if (seq.privateMarker == '=') {
          // CSI = 1 ; flags u — enable (flags) / CSI = 1 ; 0 u — disable.
          int32_t flags = seq.params.size() > 1 ? seq.param(1, 1) : 1;
          modifyLevel = flags != 0 ? uint8_t(3) : uint8_t(0);
        } else if (seq.privateMarker == '<') {
          modifyLevel = 0;  // pop — disable
        } else if (seq.privateMarker == '>') {
          modifyLevel = 3;  // push — enable
        } else {
          cursor = savedCursor;
          curStyle = savedStyle;
        }
        break;
      case 'v':  // DECCRA — CSI t;l;b;r;dstT;dstL $ v (single page)
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '$') copyRectangle(seq);
        break;
      case 'x':  // DECFRA — CSI Pc;t;l;b;r $ x
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '$') fillRectangle(seq);
        break;
      case 'z':  // DECERA — CSI t;l;b;r $ z
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '$') eraseRectangle(seq);
        break;
      case '{':  // DECSERA (selective rect erase) / XTPUSHSGR (# {)
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '$') {
          eraseRectangle(seq, /*selective=*/true);
        } else if (seq.intermediates.size() == 1 && seq.intermediates[0] == '#') {
          pushSgr();
        }
        break;
      case '}':  // XTPOPSGR — CSI # }
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '#') popSgr();
        break;
      case '~':  // XTREPORTSGR — CSI # ~
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '#') reportSgr();
        break;
      case 'h':
        if (seq.privateMarker == '?') setDecMode(seq.params, true);
        else setAnsiMode(seq.params, true);
        break;
      case 'l':
        if (seq.privateMarker == '?') setDecMode(seq.params, false);
        else setAnsiMode(seq.params, false);
        break;
      case 's':  // save cursor (ANSI.SYS)
        savedCursor = cursor;
        savedStyle = curStyle;
        break;
      case 'Z':  // CBT
        cursor.col = prevTab(cursor.col);
        cursor.wrapPending = false;
        break;
      case 'g':  // TBC
        switch (seq.param(0, 0)) {
          case 0:
            if (cursor.col >= 0 && cursor.col < cols) tabStops[size_t(cursor.col)] = 0;
            break;
          case 3:
            std::fill(tabStops.begin(), tabStops.end(), 0);
            break;
          default: break;
        }
        break;
      default:
        break;  // unknown CSI — safely ignore (§27)
    }
    if (!syncEndJustNow) {
      mutationRows(cursor.row);  // parity: ALWAYS, even for unknown CSI
    } else {
      syncEndJustNow = false;
    }
  }

  void setScrollRegion(int top, int bottom) {
    int t = top < 0 ? 0 : top;
    int b = bottom;
    if (t > rows - 1) t = rows - 1;
    if (b > rows - 1) b = rows - 1;
    if (b < t) b = t;
    scrollTop = t;
    scrollBottom = b;
  }

  void moveCursor(int dRow, int dCol) {
    cursor.row = clampRow(cursor.row + dRow);
    cursor.col = clampCol(cursor.col + dCol);
    cursor.wrapPending = false;
  }

  // ICH (§5): insert [n] blank cells at the cursor, rest shifts right.
  void insertChars(int n) {
    int count = n < 1 ? 1 : n;
    cur->insertChars(cursor.row, cursor.col, count);
    mutationRows(cursor.row);
  }

  // DCH (§5): delete [n] cells at the cursor, rest shifts left.
  void deleteChars(int n) {
    int count = n < 1 ? 1 : n;
    cur->deleteChars(cursor.row, cursor.col, count);
    mutationRows(cursor.row);
  }

  void eraseDisplay(int mode) {
    switch (mode) {
      case 0:
        cur->eraseRow(cursor.row, cursor.col, cols - 1, curStyle);
        cur->eraseRows(cursor.row + 1, rows - 1, curStyle);
        break;
      case 1:
        cur->eraseRows(0, cursor.row - 1, curStyle);
        cur->eraseRow(cursor.row, 0, cursor.col, curStyle);
        break;
      case 2: cur->eraseRows(0, rows - 1, curStyle); break;
      case 3:  // ED 3 — erase saved lines only (xterm `clear`)
        main.clearScrollback();
        break;
      default: break;
    }
    addMutation(MutationType::kErase, 0, rows - 1);
  }

  void eraseLine(int mode) {
    switch (mode) {
      case 0: cur->eraseRow(cursor.row, cursor.col, cols - 1, curStyle); break;
      case 1: cur->eraseRow(cursor.row, 0, cursor.col, curStyle); break;
      case 2: cur->eraseRow(cursor.row, 0, cols - 1, curStyle); break;
      default: break;
    }
    mutationRows(cursor.row);
  }

  // ═══ SGR (§6) — semicolon + colon sub-param forms ═══
  void applySgr(const CsiSequence& seq) {
    const auto& params = seq.params;
    if (params.empty()) {
      curStyle = styles.defaultId();
      return;
    }
    size_t i = 0;
    while (i < params.size()) {
      int32_t p = params[i];
      if (seq.hasSubParams(int(i))) {
        uint32_t nSub = 0;
        const int32_t* subs = seq.subParams(int(i), nSub);
        if (p == 4) {
          if (nSub >= 2) setUnderline(underlineFromSub(subs[1]));
        } else if (p == 38 || p == 48) {
          Color c;
          if (colorFromColonSubs(subs, nSub, c)) {
            if (p == 38) setFg(c);
            else setBg(c);
          }
        }
        i += nSub - 1;  // skip the sub values (same token)
      } else {
        switch (p) {
          case 0: curStyle = styles.defaultId(); break;
          case 1: setAttr(kAttrBold, true); break;
          case 2: setAttr(kAttrDim, true); break;
          case 3: setAttr(kAttrItalic, true); break;
          case 4: setUnderline(kUlSingle); break;
          case 5: setAttr(kAttrBlink, true); break;
          case 7: setAttr(kAttrInverse, true); break;
          case 8: setAttr(kAttrHidden, true); break;
          case 9: setAttr(kAttrStrike, true); break;
          case 21: setUnderline(kUlDouble); break;  // T85: SGR 21
          case 22: setAttr(kAttrBold, false); setAttr(kAttrDim, false); break;
          case 23: setAttr(kAttrItalic, false); break;
          case 24: setUnderline(kUlNone); break;
          case 25: setAttr(kAttrBlink, false); break;
          case 27: setAttr(kAttrInverse, false); break;
          case 28: setAttr(kAttrHidden, false); break;
          case 29: setAttr(kAttrStrike, false); break;
          default:
            if (p >= 30 && p <= 37) setFg(indexedColor(p - 30));
            else if (p >= 40 && p <= 47) setBg(indexedColor(p - 40));
            else if (p >= 90 && p <= 97) setFg(indexedColor(p - 90 + 8));
            else if (p >= 100 && p <= 107) setBg(indexedColor(p - 100 + 8));
            else if (p == 39) setFg(Color{});
            else if (p == 49) setBg(Color{});
            else if (p == 38 || p == 48) {
              // 38;5;n (256) or 38;2;r;g;b (TrueColor) — clamped (P1 fix).
              bool isFg = p == 38;
              if (i + 1 < params.size()) {
                int32_t mode = params[i + 1];
                if (mode == 5) {
                  if (i + 2 < params.size()) {
                    Color c = indexedColor(clamp255(params[i + 2]));
                    if (isFg) setFg(c); else setBg(c);
                  }
                  i += 2;
                } else if (mode == 2) {
                  if (i + 4 < params.size()) {
                    Color c;
                    c.kind = kColorRgb;
                    c.r = uint8_t(clamp255(params[i + 2]));
                    c.g = uint8_t(clamp255(params[i + 3]));
                    c.b = uint8_t(clamp255(params[i + 4]));
                    if (isFg) setFg(c); else setBg(c);
                  }
                  i += 4;
                }
              }
            }
            break;
        }
      }
      ++i;
    }
  }

  static uint8_t clamp255(int32_t v) {
    return uint8_t(v < 0 ? 0 : (v > 255 ? 255 : v));
  }
  static Color indexedColor(int32_t idx) {
    Color c;
    c.kind = kColorIndexed;
    c.index = clamp255(idx);
    return c;
  }
  static Underline underlineFromSub(int32_t code) {
    switch (code) {
      case 0: return kUlNone;
      case 2: return kUlDouble;
      case 3: return kUlCurly;
      case 4: return kUlDotted;
      case 5: return kUlDashed;
      default: return kUlSingle;
    }
  }
  // 38:5:n / 38:2:r:g:b / 38:2:cs:r:g:b — returns false when malformed.
  static bool colorFromColonSubs(const int32_t* subs, uint32_t n, Color& out) {
    if (n < 2) return false;
    switch (subs[1]) {
      case 5:
        if (n >= 3) {
          out = indexedColor(subs[2]);
          return true;
        }
        return false;
      case 2:
        if (n >= 6) {  // colorspace prefix form — cs ignored
          out.kind = kColorRgb;
          out.r = clamp255(subs[3]);
          out.g = clamp255(subs[4]);
          out.b = clamp255(subs[5]);
          return true;
        }
        if (n >= 5) {
          out.kind = kColorRgb;
          out.r = clamp255(subs[2]);
          out.g = clamp255(subs[3]);
          out.b = clamp255(subs[4]);
          return true;
        }
        return false;
      default:
        return false;
    }
  }

  // Style mutation helpers — re-intern after each change.
  void setAttr(uint16_t bit, bool on) {
    Style s = styles.get(curStyle);
    if (on) s.attr = uint16_t(s.attr | bit);
    else s.attr = uint16_t(s.attr & ~bit);
    curStyle = styles.intern(s);
  }
  void setUnderline(Underline ul) {
    Style s = styles.get(curStyle);
    s.attr = uint16_t((s.attr & ~(7u << kUlShift)) | (uint16_t(ul) << kUlShift));
    curStyle = styles.intern(s);
  }
  void setFg(const Color& c) {
    Style s = styles.get(curStyle);
    s.fg = c;
    curStyle = styles.intern(s);
  }
  void setBg(const Color& c) {
    Style s = styles.get(curStyle);
    s.bg = c;
    curStyle = styles.intern(s);
  }

  // ═══ DECSTR soft reset (DEC STD 070) ═══
  void softReset() {
    curStyle = styles.defaultId();
    cursor.row = 0;
    cursor.col = 0;
    cursor.wrapPending = false;
    insertMode = false;
    originMode = false;
    autoWrap = true;
    applicationCursor = false;
    cursorVisible = true;
    savedCursor = Cursor{};
    savedStyle = styles.defaultId();
    setScrollRegion(0, rows - 1);
    cursorShapeV = CursorShape::kBar;
    charProtected = false;   // DECSCA off
    activeLink = 0;          // hyperlinks closed, spans stay (content is content)
    sgrStack.clear();        // XTPUSHSGR stack is style state — soft reset drops it
    addMutation(MutationType::kFull, 0, 0);
  }

  // ═══ v0.2: DECRQM — mode report (CSI ? Ps $ p → CSI ? Ps;Pm $ y) ═══
  // Status: 0 not recognized, 1 set, 2 reset, 3 permanently set, 4 permanently
  // reset. We only ever report 1/2/0 — the feature matrix is honest.
  void decrqm(int32_t mode, bool decPrivate) {
    int status = 0;
    bool known = true;
    auto boolMode = [&](bool v) { status = v ? 1 : 2; };
    if (decPrivate) {
      switch (mode) {
        case 1: boolMode(applicationCursor); break;
        case 4: boolMode(insertMode); break;
        case 5: boolMode(reverseVideo); break;
        case 6: boolMode(originMode); break;
        case 7: boolMode(autoWrap); break;
        case 9: boolMode(mouseModeV == 1); break;
        case 25: boolMode(cursorVisible); break;
        case 47: boolMode(alternateScreen); break;
        case 66: boolMode(applicationKeypad); break;
        case 1000: boolMode(mouseModeV == 2); break;
        case 1001: boolMode(false); break;  // highlight tracking not implemented
        case 1002: boolMode(mouseModeV == 3); break;
        case 1003: boolMode(mouseModeV == 4); break;
        case 1004: boolMode(focusReport); break;
        case 1005: boolMode(mouseEncodingV == 1); break;
        case 1006: boolMode(mouseEncodingV == 2); break;
        case 1007: boolMode(altScroll); break;
        case 1015: boolMode(mouseEncodingV == 3); break;
        case 1047: boolMode(alternateScreen); break;
        case 1048: status = 1; break;  // cursor save/restore always available
        case 1049: boolMode(alternateScreen); break;
        case 2004: boolMode(bracketedPaste); break;
        case 2026: boolMode(syncing); break;
        case 2027: boolMode(modifyLevel != 0); break;
        default: known = false; break;
      }
    } else {
      switch (mode) {
        case 4: boolMode(insertMode); break;
        case 20: boolMode(newlineMode); break;
        default: known = false; break;
      }
    }
    if (!known) status = 0;
    respondScratch.clear();
    respondScratch += "\x1B[";
    if (decPrivate) respondScratch += '?';
    respondScratch += std::to_string(mode);
    respondScratch += ';';
    respondScratch += std::to_string(status);
    respondScratch += "$y";
    respond(respondScratch.c_str());
  }

  // ═══ v0.2: DECSED / DECSEL — selective erase (protected cells survive) ═══
  void eraseCellIfUnprotected(int r, int c) {
    Cell cell = cur->cell(r, c);
    if (cell.flags & kCellProtected) return;
    if ((cell.flags & kCellWideTrail) && c > 0) {
      // A trail erases together with its lead — but only if the lead is also
      // unprotected (a protected wide char owns both columns).
      Cell lead = cur->cell(r, c - 1);
      if (lead.flags & kCellProtected) return;
    }
    if (cell.flags & kCellWideLead) {
      // erase the lead AND its trail as one unit
      Cell blank{' ', curStyle, 0};
      cur->setCell(r, c, blank);
      if (c + 1 < cols) {
        Cell trail{0, curStyle, kCellWideTrail};
        cur->setCell(r, c + 1, trail);
      }
      return;
    }
    Cell blank{' ', curStyle, 0};
    cur->setCell(r, c, blank);
  }

  void eraseDisplaySelective(int mode) {
    switch (mode) {
      case 0:
        for (int c = cursor.col; c < cols; ++c) eraseCellIfUnprotected(cursor.row, c);
        for (int r = cursor.row + 1; r < rows; ++r)
          for (int c = 0; c < cols; ++c) eraseCellIfUnprotected(r, c);
        break;
      case 1:
        for (int r = 0; r < cursor.row; ++r)
          for (int c = 0; c < cols; ++c) eraseCellIfUnprotected(r, c);
        for (int c = 0; c <= cursor.col && c < cols; ++c) eraseCellIfUnprotected(cursor.row, c);
        break;
      case 2:
        for (int r = 0; r < rows; ++r)
          for (int c = 0; c < cols; ++c) eraseCellIfUnprotected(r, c);
        break;
      default: break;  // 3 = scrollback — main.clearScrollback() semantics stay non-selective
    }
    addMutation(MutationType::kErase, 0, rows - 1);
  }

  void eraseLineSelective(int mode) {
    switch (mode) {
      case 0:
        for (int c = cursor.col; c < cols; ++c) eraseCellIfUnprotected(cursor.row, c);
        break;
      case 1:
        for (int c = 0; c <= cursor.col && c < cols; ++c) eraseCellIfUnprotected(cursor.row, c);
        break;
      case 2:
        for (int c = 0; c < cols; ++c) eraseCellIfUnprotected(cursor.row, c);
        break;
      default: break;
    }
    mutationRows(cursor.row);
  }

  // ═══ v0.2: rectangular operations (DECERA / DECSERA / DECFRA / DECCRA) ═══
  struct Rect {
    int top, left, bottom, right;  // 0-based inclusive
  };

  // Params are 1-based (xterm); default 1; origin mode ignored for rects
  // (xterm applies DECOM — we deliberately keep absolute coords, documented).
  Rect rectFrom(const CsiSequence& seq, int firstParam) {
    Rect r{};
    r.top = std::max(0, seq.paramOrDefault(firstParam, 1) - 1);
    r.left = std::max(0, seq.paramOrDefault(firstParam + 1, 1) - 1);
    r.bottom = seq.paramOrDefault(firstParam + 2, rows) - 1;
    r.right = seq.paramOrDefault(firstParam + 3, cols) - 1;
    r.bottom = std::min(r.bottom, rows - 1);
    r.right = std::min(r.right, cols - 1);
    return r;
  }

  void eraseRectangle(const CsiSequence& seq, bool selective = false) {
    Rect r = rectFrom(seq, 0);
    if (r.top > r.bottom || r.left > r.right) return;
    for (int row = r.top; row <= r.bottom; ++row) {
      for (int c = r.left; c <= r.right; ++c) {
        if (selective) {
          eraseCellIfUnprotected(row, c);
        } else {
          Cell cell = cur->cell(row, c);
          if ((cell.flags & kCellWideLead) && c + 1 <= r.right) {
            // erasing the lead also erases its trail when inside the rect
            Cell blank{' ', curStyle, 0};
            cur->setCell(row, c, blank);
            Cell trail{0, curStyle, kCellWideTrail};
            cur->setCell(row, c + 1, trail);
            ++c;  // trail consumed
          } else if (cell.flags & kCellWideLead) {
            // lead inside, trail outside → the wide char degrades to a blank
            // lead (xterm keeps the trail; we keep a blank trail styled)
            Cell blank{' ', curStyle, 0};
            cur->setCell(row, c, blank);
            Cell trail{0, curStyle, kCellWideTrail};
            cur->setCell(row, std::min(c + 1, cols - 1), trail);
          } else {
            Cell blank{' ', curStyle, 0};
            cur->setCell(row, c, blank);
          }
        }
      }
    }
    if (RowExtras* ex = const_cast<RowExtras*>(cur->combiningExtras(r.top))) {
      ex->invalidateAllLinks();
    }
    addMutation(MutationType::kErase, r.top, r.bottom);
  }

  void fillRectangle(const CsiSequence& seq) {
    // DECFRA: param0 = fill char (printable), rect = params 1..4.
    uint32_t fill = uint32_t(seq.paramOrDefault(0, ' '));
    if (fill < 0x20 || fill == 0x7F) return;  // xterm: only printable fill
    Rect r = rectFrom(seq, 1);
    if (r.top > r.bottom || r.left > r.right) return;
    for (int row = r.top; row <= r.bottom; ++row) {
      for (int c = r.left; c <= r.right; ++c) {
        Cell cell = cur->cell(row, c);
        if (cell.flags & kCellProtected) continue;  // DECFRA respects protection
        uint16_t fl = uint16_t(unicodeWidth(fill) == 2 ? kCellWideLead : 0);
        if (charProtected) fl = uint16_t(fl | kCellProtected);
        Cell f{fill, curStyle, fl};
        cur->setCell(row, c, f);
        if ((fl & kCellWideLead) && c + 1 <= r.right && c + 1 < cols) {
          Cell trail{0, curStyle, kCellWideTrail};
          cur->setCell(row, c + 1, trail);
          ++c;
        }
      }
    }
    addMutation(MutationType::kErase, r.top, r.bottom);
  }

  void copyRectangle(const CsiSequence& seq) {
    // DECCRA: src rect = params 0..3, dst top/left = params 4/5 (1-based).
    // Page ids (params 6..9) are ignored — single page.
    Rect src = rectFrom(seq, 0);
    int dstTop = std::max(0, seq.paramOrDefault(4, 1) - 1);
    int dstLeft = std::max(0, seq.paramOrDefault(5, 1) - 1);
    if (src.top > src.bottom || src.left > src.right) return;
    // Copy to a scratch buffer first (src/dst may overlap).
    std::vector<Cell> buf;
    int h = src.bottom - src.top + 1;
    int w = src.right - src.left + 1;
    buf.reserve(size_t(h) * w);
    for (int row = src.top; row <= src.bottom; ++row) {
      for (int c = src.left; c <= src.right; ++c) buf.push_back(cur->cell(row, c));
    }
    for (int dy = 0; dy < h; ++dy) {
      int drow = dstTop + dy;
      if (drow >= rows) break;
      for (int dx = 0; dx < w; ++dx) {
        int dcol = dstLeft + dx;
        if (dcol >= cols) break;
        cur->setCell(drow, dcol, buf[size_t(dy) * w + size_t(dx)]);
      }
    }
    int lastRow = std::min(dstTop + h - 1, rows - 1);
    addMutation(MutationType::kCells, dstTop, std::max(lastRow, dstTop));
  }

  // ═══ v0.2: XTPUSHSGR / XTPOPSGR / XTREPORTSGR ═══
  void pushSgr() {
    if (sgrStack.size() >= kMaxSgrStack) return;  // bounded — drop silently
    sgrStack.push_back(curStyle);
  }

  void popSgr() {
    if (sgrStack.empty()) return;
    curStyle = sgrStack.back();
    sgrStack.pop_back();
  }

  void reportSgr() {
    // XTREPORTSGR: respond CSI # ; Ps1;Ps2;… ~ describing the current style.
    const Style& st = styles.get(curStyle);
    respondScratch.clear();
    respondScratch += "\x1B[#;";
    appendSgrParams(&respondScratch, st);
    respondScratch += '~';
    respond(respondScratch.c_str());
  }

  void appendSgrParams(std::string* out, const Style& st) {
    bool first = true;
    auto add = [&](int32_t p) {
      if (!first) *out += ';';
      first = false;
      *out += std::to_string(p);
    };
    bool any = false;
    if (st.attr & kAttrBold) add(1), any = true;
    if (st.attr & kAttrDim) add(2), any = true;
    if (st.attr & kAttrItalic) add(3), any = true;
    if (ulOf(st.attr) != kUlNone) add(4), any = true;
    if (st.attr & kAttrBlink) add(5), any = true;
    if (st.attr & kAttrInverse) add(7), any = true;
    if (st.attr & kAttrHidden) add(8), any = true;
    if (st.attr & kAttrStrike) add(9), any = true;
    if (st.fg.kind != kColorDefault) {
      if (st.fg.kind == kColorIndexed) add(st.fg.index < 8 ? 30 + st.fg.index : 90 + st.fg.index - 8);
      else { add(38); add(2); add(st.fg.r); add(st.fg.g); add(st.fg.b); }
      any = true;
    }
    if (st.bg.kind != kColorDefault) {
      if (st.bg.kind == kColorIndexed) add(st.bg.index < 8 ? 40 + st.bg.index : 100 + st.bg.index - 8);
      else { add(48); add(2); add(st.bg.r); add(st.bg.g); add(st.bg.b); }
      any = true;
    }
    if (!any) add(0);  // default style → SGR 0
  }

  // ═══ OSC (§20 + v0.2 hyperlinks / color queries) ═══
  void handleOsc(int32_t code, const std::vector<char16_t>& data) {
    switch (code) {
      case 0:
      case 1:
      case 2:
        titleStr = utf16ToUtf8(data);
        hasTitle = true;
        break;
      case 4: {  // OSC 4 — palette set/query: "idx;spec" or "idx;?"
        std::string s = utf16ToUtf8(data);
        size_t semi = s.find(';');
        if (semi != std::string::npos && semi + 1 < s.size() && s[semi + 1] == '?') {
          respondOscColorQuery(4, s.substr(0, semi));
        }
        break;  // palette SET is theme-side — ignored (headless engine)
      }
      case 8:  // OSC 8 — hyperlink: "params;uri" (empty uri closes the link)
        handleOscHyperlink(utf16ToUtf8(data));
        break;
      case 10:
      case 11:
      case 12: {  // fg / bg / cursor color queries (set → theme-side)
        std::string s = utf16ToUtf8(data);
        if (s == "?") respondOscColorQuery(code, "");
        break;
      }
      case 52: {  // clipboard write request (base64 payload)
        std::string s = utf16ToUtf8(data);
        size_t semi = s.find(';');
        std::string payload = semi == std::string::npos ? s : s.substr(semi + 1);
        if (!payload.empty()) {
          std::string decoded;
          if (base64Decode(payload, decoded)) {
            if (clipboard.size() >= kMaxPendingClipboard) clipboard.erase(clipboard.begin());
            clipboard.push_back(sanitizeUtf8(decoded));
          }
        }
        break;
      }
      default:
        break;  // other OSC ignored (104/110-112 color reset, 133 marks, …)
    }
  }

  // OSC 8 hyperlink open/close. Payload "params;uri" — params may carry an
  // explicit id (ignored: spans reference our own table ids). Bounded table:
  // beyond kMaxLinks the oldest URI slot is dropped; stale spans resolve to
  // no-link lazily (linkAt returns null for an out-of-range / cleared id).
  void handleOscHyperlink(const std::string& payload) {
    size_t semi = payload.find(';');
    std::string uri = semi == std::string::npos ? payload : payload.substr(semi + 1);
    if (uri.empty()) {
      activeLink = 0;  // close
      return;
    }
    if (uri.size() > 2048) uri.resize(2048);  // bounded
    if (linkUris.size() < kMaxLinks) {
      linkUris.push_back(std::move(uri));
      activeLink = uint16_t(linkUris.size());
      return;
    }
    linkUris.erase(linkUris.begin());  // evict the oldest slot
    linkUris.push_back(std::move(uri));
    activeLink = uint16_t(linkUris.size());
  }

  // OSC 4/10/11/12 "?" queries → placeholder theme colors. Apps (nvim, tmux)
  // probe these to pick light/dark palettes; answering keeps them responsive.
  void respondOscColorQuery(int32_t code, const std::string& idx) {
    respondScratch.clear();
    respondScratch += "\x1B]";
    respondScratch += std::to_string(code);
    respondScratch += ';';
    if (code == 4) {
      respondScratch += idx;
      respondScratch += ';';
      // Basic-16 / 256-color palette entries resolve via the same formula as
      // colorRgb(); anything else answers black (theme decides in the UI).
      uint32_t rgb = 0;
      if (!idx.empty()) {
        char* end = nullptr;
        long n = std::strtol(idx.c_str(), &end, 10);
        if (end && *end == '\0' && n >= 0 && n < 256) {
          Color c;
          c.kind = kColorIndexed;
          c.index = uint8_t(n);
          rgb = colorRgb(c);
        }
      }
      appendRgbSpec(&respondScratch, rgb);
    } else if (code == 10) {
      appendRgbSpec(&respondScratch, 0xFFFFFF);  // fg — light placeholder
    } else {
      appendRgbSpec(&respondScratch, 0x000000);  // bg / cursor — dark placeholder
    }
    respondScratch += '\x07';
    respond(respondScratch.c_str());
  }

  static void appendRgbSpec(std::string* out, uint32_t rgb) {
    char buf[24];
    std::snprintf(buf, sizeof(buf), "rgb:%04x/%04x/%04x", (rgb >> 16) & 0xFFFF,
                  (rgb >> 8) & 0xFFFF, rgb & 0xFFFF);
    *out += buf;
  }

  // ═══ ESC (§25 + DECKPAM/DECKPNM) ═══
  void handleEsc(char final, const char* inter, size_t nInter) {
    // T82: SCS — G0 charset designation (ESC ( 0 / B / A).
    if (nInter == 1 && inter[0] == char(0x28)) {
      switch (final) {
        case '0': g0 = G0::kDecGraphics; return;
        case 'B':
        case 'A': g0 = G0::kAscii; return;
        default: break;
      }
    }
    switch (final) {
      case 'c': reset(); break;  // RIS
      case '7':                  // DECSC
        savedCursor = cursor;
        savedStyle = curStyle;
        break;
      case '8':  // DECRC
        cursor = savedCursor;
        curStyle = savedStyle;
        break;
      case 'M':  // RI — reverse line feed
        if (cursor.row == scrollTop) cur->scrollDown(1, scrollTop, scrollBottom);
        else if (cursor.row > 0) --cursor.row;
        break;
      case '=': applicationKeypad = true; break;   // DECKPAM
      case '>': applicationKeypad = false; break;  // DECKPNM
      case 'D':  // IND
        ++cursor.row;
        if (cursor.row > scrollBottom) {
          cur->scrollUp(1, scrollTop, scrollBottom);
          cursor.row = scrollBottom;
        }
        break;
      case 'E':  // NEL — IND + CR (P3 fix: scrolls like IND)
        ++cursor.row;
        if (cursor.row > scrollBottom) {
          cur->scrollUp(1, scrollTop, scrollBottom);
          cursor.row = scrollBottom;
        }
        cursor.col = 0;
        break;
      case 'H':  // HTS
        if (cursor.col >= 0 && cursor.col < cols) tabStops[size_t(cursor.col)] = 1;
        break;
      default: break;  // unknown ESC ignored
    }
  }

  // ═══ ANSI modes — IRM(4) / LNM(20) ═══
  void setAnsiMode(const std::vector<int32_t>& params, bool enable) {
    for (int32_t p : params) {
      switch (p) {
        case 4: insertMode = enable; break;
        case 20: newlineMode = enable; break;
        default: break;
      }
    }
  }

  // ═══ DEC private modes (§3 + v0.2 foundation) ═══
  void setDecMode(const std::vector<int32_t>& params, bool enable) {
    for (int32_t p : params) {
      switch (p) {
        case 1: applicationCursor = enable; break;
        case 4: insertMode = enable; break;
        case 5: reverseVideo = enable; break;
        case 6: originMode = enable; break;
        case 7: autoWrap = enable; break;
        case 25: cursorVisible = enable; break;
        case 47:
        case 1047: switchAlternateScreen(enable, false); break;
        case 1048:  // cursor save/restore (xterm)
          if (enable) {
            savedCursor = cursor;
            savedStyle = curStyle;
          } else {
            cursor = savedCursor;
            curStyle = savedStyle;
          }
          break;
        case 1049: switchAlternateScreen(enable, true); break;
        case 1000:
          mouseModeV = enable ? 2 : 0;  // normal tracking
          break;
        case 1001:
          if (enable) mouseModeV = 2;  // highlight tracking → normal (byte-compat)
          else if (mouseModeV == 2) mouseModeV = 0;
          break;
        case 1002:
          mouseModeV = enable ? 3 : 0;  // button-event tracking
          break;
        case 1003:
          mouseModeV = enable ? 4 : 0;  // any-event tracking
          break;
        case 1005:
          mouseEncodingV = enable ? 1 : 0;  // UTF-8 coords
          break;
        case 1006:
          mouseEncodingV = enable ? 2 : 0;  // SGR
          break;
        case 1015:
          mouseEncodingV = enable ? 3 : 0;  // urxvt
          break;
        case 1004: focusReport = enable; break;
        case 1007: altScroll = enable; break;
        case 2004: bracketedPaste = enable; break;
        case 2026:  // synchronized output: batch → single FULL mutation on end
          if (enable) beginSync();
          else {
            endSync();
            syncEndJustNow = true;  // suppress the dispatch-trailing mutation
          }
          break;
        case 2027:  // modifyOtherKeys (DEC-style): on → level 2
          modifyLevel = enable ? 2 : 0;
          break;
        default: break;
      }
    }
  }

  // ═══ alternate screen (§19) ═══
  void switchAlternateScreen(bool toAlt, bool saveCursor) {
    if (toAlt && !alternateScreen) {
      if (saveCursor) {
        savedCursor = cursor;
        savedStyle = curStyle;
      }
      alt.clear();
      cur = &alt;
      alternateScreen = true;
      cursor.row = 0;
      cursor.col = 0;
      cursor.wrapPending = false;
      addMutation(MutationType::kFull, 0, 0);
    } else if (!toAlt && alternateScreen) {
      cur = &main;
      alternateScreen = false;
      if (saveCursor) {
        cursor = savedCursor;
        curStyle = savedStyle;
      }
      addMutation(MutationType::kFull, 0, 0);
    }
  }

  // ═══ resize / reset ═══
  void resize(int newRows, int newCols) {
    newRows = newRows < 1 ? 1 : newRows;
    newCols = newCols < 1 ? 1 : newCols;
    if (newRows == rows && newCols == cols) return;
    if (newCols != cols) {
      reflowResize(newRows, newCols);  // width change → rewrap, no loss
    } else {
      // Height-only change. Shrinking: the TOP rows move to scrollback so
      // the cursor's line stays on screen (bottom-anchored, xterm-style);
      // the alt screen scrolls its region (no scrollback there).
      if (newRows < rows) {
        int cut = rows - newRows;
        main.scrollUp(cut, 0, rows - 1);  // top rows preserved in scrollback
        if (alternateScreen) alt.scrollUp(cut, 0, rows - 1);
        cursor.row = std::max(0, cursor.row - cut);
      }
      main.resize(newRows, newCols);
      alt.resize(newRows, newCols);
    }
    rows = newRows;
    cols = newCols;
    setScrollRegion(0, newRows - 1);
    resizeTabs(newCols);
    if (cursor.row >= newRows) cursor.row = newRows - 1;
    if (cursor.col >= newCols) cursor.col = newCols - 1;
    searchHits.clear();  // global row numbering changed under us
    activeHit = -1;
    selActive = false;   // selection extent is row-dependent — drop it
    addMutation(MutationType::kResize, 0, newRows - 1);
  }

  // Width-change reflow: rebuild the MAIN screen from its logical lines at
  // the new width (scrollback + visible); the alt screen clips (xterm never
  // reflows the alternate screen — vim/less rely on that).
  void reflowResize(int newRows, int newCols) {
    // 1. Assemble the main screen's visual rows (scrollback, then visible)
    //    into logical lines. The last visible row never continues anywhere.
    std::vector<lines::RowRef> refs;
    int sb = int(main.scrollbackCount());
    refs.reserve(size_t(sb + rows));
    for (int i = 0; i < sb; ++i) {
      refs.push_back(lines::RowRef{main.scrollbackRowCells(i), main.cols(),
                                   main.scrollbackRowWrapped(i),
                                   main.scrollbackRowExtras(i)});
    }
    for (int r = 0; r < rows; ++r) {
      refs.push_back(
          lines::RowRef{main.rowCells(r), main.cols(),
                        r < rows - 1 ? main.rowWrapped(r) : false,
                        main.combiningExtras(r)});
    }
    std::vector<lines::LogicalLine> logical;
    lines::assemble(refs.data(), int(refs.size()), 0, kMaxSearchLinesRows, &logical);

    // 2. Combining runs in the global row numbering refs[] used (0..N-1).
    std::vector<CombRun> combs;
    for (size_t gi = 0; gi < refs.size(); ++gi) {
      if (const RowExtras* ex = refs[gi].extras) {
        for (const CombiningMark& m : ex->marks) {
          CombRun cr;
          cr.row = int(gi);
          cr.col = m.col;
          cr.cps = m.cps;
          combs.push_back(std::move(cr));
        }
      }
    }

    // 3. The main screen's cursor: when the alt screen is active the live
    //    cursor belongs to it; savedCursor approximates the main position.
    const Cursor& mc = alternateScreen ? savedCursor : cursor;
    int cursorGlobalRow = alternateScreen ? -1 : (sb + mc.row);
    if (!alternateScreen && (mc.row < 0 || mc.row >= rows)) cursorGlobalRow = -1;

    ReflowInput in;
    in.lines = std::move(logical);
    in.combs = std::move(combs);
    in.newCols = newCols;
    in.cursorRow = cursorGlobalRow;
    // wrapPending ⇒ the cursor logically sits one cell further (the last
    // column is occupied and the next char will wrap) — encode that as a
    // one-past-end column so the ordinal maps to the line end.
    in.cursorCol = mc.col + (mc.wrapPending ? 1 : 0);
    if (in.cursorCol > cols) in.cursorCol = cols;
    in.cursorFirstAbsRow = -1;  // no absolute fallback — re-clamped by caller
    ReflowOutput out = ReflowEngine::rewrap(in);

    // 4. Rebuild: choose the visible window so the CURSOR's row stays on
    //    screen (top-anchored when the cursor is near the top; otherwise the
    //    cursor sits on the bottom row and the overflow feeds scrollback —
    //    the classic terminal shrink behavior). Rebuild feeds scrollback
    //    rows first (oldest order == out.rows order), then the visible grid.
    main.resetTo(newRows, newCols);
    size_t total = out.rows.size();
    size_t visibleCount = std::min(total, size_t(newRows));
    size_t first = 0;
    if (total > visibleCount) {
      size_t cursorOut = size_t(std::max(0, out.cursorRow));
      if (cursorOut < visibleCount) {
        first = 0;  // top-anchored window already covers the cursor
      } else if (cursorOut + 1 <= total) {
        first = cursorOut + 1 - visibleCount;  // cursor on the bottom row
      } else {
        first = total - visibleCount;
      }
      if (first > total - visibleCount) first = total - visibleCount;
    }
    for (size_t i = 0; i < first; ++i) {
      auto ex = std::make_unique<RowExtras>();
      for (const CombRun& cr : out.rowCombs[i]) {
        if (cr.row == int(i)) ex->marks.push_back(CombiningMark{uint16_t(cr.col), cr.cps});
      }
      bool wrapped = (i + 1 < total) && out.lineOfRow[i] == out.lineOfRow[i + 1];
      std::vector<Cell> rowCells = out.rows[i];
      main.pushScrollbackRowCells(std::move(rowCells), std::move(ex), wrapped);
    }
    for (size_t i = first; i < first + visibleCount && i < total; ++i) {
      int r = int(i - first);
      auto ex = std::make_unique<RowExtras>();
      for (const CombRun& cr : out.rowCombs[i]) {
        if (cr.row == int(i)) ex->marks.push_back(CombiningMark{uint16_t(cr.col), cr.cps});
      }
      bool wrapped = (i + 1 < total) && out.lineOfRow[i] == out.lineOfRow[i + 1];
      main.loadRow(r, out.rows[i].data(), int(out.rows[i].size()), std::move(ex), wrapped);
    }

    // 5. Cursor mapping: reflow's row is output-global; make it screen-local
    //    (the window guarantees it is on screen; clamp anyway).
    int nRow = out.cursorRow < 0 ? 0 : out.cursorRow - int(first);
    nRow = std::max(0, std::min(nRow, newRows - 1));
    int nCol = std::max(0, std::min(out.cursorCol, newCols - 1));
    if (alternateScreen) {
      savedCursor.row = nRow;
      savedCursor.col = nCol;
    } else {
      cursor.row = nRow;
      cursor.col = nCol;
      cursor.wrapPending = false;
    }

    // 6. Alt screen: clip resize (content + wrap flags ride Screen::resize).
    alt.resize(newRows, newCols);
  }

  void reset() {
    main.clear();
    alt.clear();
    cur = &main;
    cursor = Cursor{};
    curStyle = styles.defaultId();
    setScrollRegion(0, rows - 1);
    alternateScreen = false;
    cursorVisible = true;
    autoWrap = true;
    originMode = false;
    insertMode = false;
    bracketedPaste = false;
    newlineMode = false;
    applicationCursor = false;
    reverseVideo = false;
    resetTabStops();
    g0 = G0::kAscii;
    savedCursor = Cursor{};
    savedStyle = styles.defaultId();
    cursorShapeV = CursorShape::kBar;
    lastPrintableCp = -1;
    clipboard.clear();
    utfCount = 0;
    utfExpected = 0;
    parser.reset();
    titleStr.clear();
    hasTitle = false;
    // v0.2 state
    applicationKeypad = false;
    focusReport = false;
    altScroll = false;
    endSync();
    syncing = false;
    syncFullPending = false;
    modifyLevel = 0;
    mouseModeV = 0;
    mouseEncodingV = 0;
    charProtected = false;
    linkUris.clear();
    activeLink = 0;
    sgrStack.clear();
    searchHits.clear();
    activeHit = -1;
    selActive = false;
    selAnchorRow = selEndRow = -1;
    addMutation(MutationType::kFull, 0, 0);
  }

  // ═══ bell / drains ═══
  int64_t drainBell() {
    if (!bellPending) return bellSeq;
    bellPending = false;
    ++bellSeq;
    return bellSeq;
  }

  // ═══ render projections ═══
  std::string renderedText() const {
    std::string out;
    for (int r = 0; r < rows; ++r) {
      if (r > 0) out.push_back('\n');
      out += cur->rowText(r);
    }
    return out;
  }

  void renderRowInto(const Cell* cells, const RowExtras* extras, uint16_t defaultStyleId,
                     std::vector<FlatRow>& outRows, std::vector<uint32_t>& combPool) const {
    // Trailing trim: a wide trail = content; ' ' with default style and no
    // combining = pure background (Kotlin renderRow parity).
    int last = cols - 1;
    while (last >= 0) {
      const Cell& c = cells[last];
      if (c.flags & kCellWideTrail) break;
      if (c.cp == uint32_t(' ') && c.style == defaultStyleId &&
          !(extras && extras->find(uint16_t(last)))) {
        --last;
        continue;
      }
      break;
    }
    FlatRow row;
    if (last >= 0) {
      row.cells.reserve(size_t(last + 1));
      for (int i = 0; i <= last; ++i) {
        if (cells[i].flags & kCellWideTrail) continue;  // rendered via its lead
        FlatCell fc{};
        fc.cp = cells[i].cp == 0 ? uint32_t(' ') : cells[i].cp;
        const std::vector<uint32_t>* comb = extras ? extras->find(uint16_t(i)) : nullptr;
        fc.combCount = comb ? uint32_t(comb->size()) : 0;
        fc.combOff = uint32_t(combPool.size());
        if (comb) combPool.insert(combPool.end(), comb->begin(), comb->end());
        const Style& st = styles.get(cells[i].style);
        fc.fg = colorArgb(st.fg);
        fc.bg = colorArgb(st.bg);
        uint16_t fl = 0;
        if (st.attr & kAttrBold) fl |= kRfBold;
        if (st.attr & kAttrDim) fl |= kRfDim;
        if (st.attr & kAttrItalic) fl |= kRfItalic;
        if (ulOf(st.attr) != kUlNone) fl |= kRfUnderline;
        if (st.attr & kAttrBlink) fl |= kRfBlink;
        if (st.attr & kAttrHidden) fl |= kRfHidden;
        if (st.attr & kAttrStrike) fl |= kRfStrike;
        if ((st.attr & kAttrInverse) || reverseVideo) fl |= kRfInverse;
        if (cells[i].flags & kCellWideLead) fl |= kRfWide;
        fc.flags = fl;
        fc.link = 0;
        if (extras) {
          if (const LinkSpan* ls = extras->linkAt(uint16_t(i))) fc.link = ls->linkId;
        }
        row.cells.push_back(fc);
      }
    }
    outRows.push_back(std::move(row));
  }

  FlatSnapshot renderSnapshotImpl(int maxSb) {
    FlatSnapshot snap;
    snap.rows = rows;
    snap.cols = cols;
    snap.cursorRow = cursor.row;
    snap.cursorCol = cursor.col;
    snap.cursorVisible = cursorVisible;
    snap.cursorShape = cursorShapeV;
    snap.alternateScreen = alternateScreen;
    snap.applicationCursor = applicationCursor;
    snap.bracketedPaste = bracketedPaste;
    snap.reverseVideo = reverseVideo;
    if (hasTitle) snap.title = titleStr;
    snap.scrollbackTotal = int32_t(main.scrollbackCount());
    snap.scrollbackBase = main.scrollbackLinesEver();
    snap.bellSeq = drainBell();  // parity: renderSnapshot consumes the bell
    const uint16_t defId = styles.defaultId();
    for (int r = 0; r < rows; ++r) {
      renderRowInto(cur->rowCells(r), cur->combiningExtras(r), defId, snap.visible, snap.combPool);
    }    if (maxSb > 0 && !alternateScreen) {
      int total = int(main.scrollbackCount());
      int from = total - maxSb;
      if (from < 0) from = 0;
      snap.scrollback.reserve(size_t(total - from));
      for (int i = from; i < total; ++i) {
        renderRowInto(main.scrollbackRowCells(i), main.scrollbackRowExtras(i), defId,
                      snap.scrollback, snap.combPool);
      }
    }
    // ── v0.2 projections ──
    snap.links = linkUris;  // id = index + 1; 0 = none (see FlatCell::link)
    if (selActive) {
      // Normalize so start <= end regardless of drag direction.
      int64_t sRow, eRow; int32_t sCol, eCol;
      if (selAnchorRow < selEndRow || (selAnchorRow == selEndRow && selAnchorCol <= selEndCol)) {
        sRow = selAnchorRow; sCol = selAnchorCol;
        eRow = selEndRow; eCol = selEndCol;
      } else {
        sRow = selEndRow; sCol = selEndCol;
        eRow = selAnchorRow; eCol = selAnchorCol;
      }
      snap.selStartRow = sRow;
      snap.selStartCol = sCol;
      snap.selEndRow = eRow;
      snap.selEndCol = eCol;
    }
    snap.searchHitCount = int32_t(searchHits.size());
    snap.activeSearchHit = activeHit;
    snap.mouseMode = int8_t(mouseModeV);
    snap.mouseEncoding = int8_t(mouseEncodingV);
    snap.focusReport = focusReport;
    snap.altScroll = altScroll;
    snap.applicationKeypad = applicationKeypad;
    snap.modifyLevel = modifyLevel;
    return snap;
  }

  // ═══ v0.2: global row addressing (search / selection coordinate space) ═══
  // Main-screen global numbering: 0 == oldest scrollback row; base =
  // linesEver - sbCount; screen rows continue contiguously (base + sbTotal …
  // base + sbTotal + rows). On the ALTERNATE screen the engine addresses
  // rows directly as screen rows (documented API contract) — the alt screen
  // has no scrollback and lives only while active.
  int64_t globalBase() const {
    return main.scrollbackLinesEver() - main.scrollbackCount();
  }

  int64_t globalRowOfScreen(int screenRow) const {
    return alternateScreen ? int64_t(screenRow) : globalBase() + main.scrollbackCount() + screenRow;
  }

  // Resolve a global row to cell storage; false = evicted / out of range.
  bool globalRowCells(int64_t g, const Cell** cells, const RowExtras** ex) const {
    *cells = nullptr;
    *ex = nullptr;
    if (alternateScreen) {
      if (g < 0 || g >= rows) return false;
      *cells = alt.rowCells(int(g));
      *ex = alt.combiningExtras(int(g));
      return true;
    }
    int64_t base = globalBase();
    if (g < base) return false;  // evicted
    int64_t sbTotal = main.scrollbackCount();
    if (g < base + sbTotal) {
      int i = int(g - base);
      *cells = main.scrollbackRowCells(i);
      *ex = main.scrollbackRowExtras(i);
      return true;
    }
    int r = int(g - base - sbTotal);
    if (r < 0 || r >= rows) return false;
    *cells = main.rowCells(r);
    *ex = main.combiningExtras(r);
    return true;
  }

  bool globalRowWrapped(int64_t g) const {
    if (alternateScreen) {
      return g >= 0 && g < rows ? alt.rowWrapped(int(g)) : false;
    }
    int64_t base = globalBase();
    if (g < base) return false;
    int64_t sbTotal = main.scrollbackCount();
    if (g < base + sbTotal) {
      // The LAST scrollback row continues into the screen only when the
      // screen's row 0 is a wrap continuation of it.
      int i = int(g - base);
      if (i == sbTotal - 1) return false;  // chain crosses into live rows: caller-side joins
      return main.scrollbackRowWrapped(i);
    }
    int r = int(g - base - sbTotal);
    if (r < 0 || r >= rows) return false;
    return r < rows - 1 ? main.rowWrapped(r) : false;
  }

  // ═══ v0.2: search (screen + scrollback, cross-wrap, case-folded) ═══
  int searchImpl(const char* pattern, bool caseInsensitive, bool wholeWord) {
    searchHits.clear();
    activeHit = -1;
    if (!pattern || pattern[0] == '\0') return 0;
    std::vector<lines::RowRef> refs;
    int64_t base;
    if (!alternateScreen) {
      int sb = int(main.scrollbackCount());
      base = globalBase();
      refs.reserve(size_t(sb + rows));
      for (int i = 0; i < sb; ++i) {
        refs.push_back(lines::RowRef{main.scrollbackRowCells(i), main.cols(),
                                     main.scrollbackRowWrapped(i),
                                     main.scrollbackRowExtras(i)});
      }
      for (int r = 0; r < rows; ++r) {
        refs.push_back(lines::RowRef{main.rowCells(r), main.cols(),
                                      r < rows - 1 ? main.rowWrapped(r) : false,
                                      main.combiningExtras(r)});
      }
    } else {
      base = 0;
      refs.reserve(size_t(rows));
      for (int r = 0; r < rows; ++r) {
        refs.push_back(lines::RowRef{alt.rowCells(r), alt.cols(),
                                      r < rows - 1 ? alt.rowWrapped(r) : false,
                                      alt.combiningExtras(r)});
      }
    }
    std::vector<lines::LogicalLine> logical;
    lines::assemble(refs.data(), int(refs.size()), base, kMaxSearchLinesRows, &logical);

    SearchQuery q;
    q.pattern = pattern;
    q.caseInsensitive = caseInsensitive;
    q.wholeWord = wholeWord;
    q.maxHits = 2000;
    SearchEngine eng;
    eng.reset(q);
    std::string text;
    std::vector<uint32_t> map;
    for (const lines::LogicalLine& line : logical) {
      text.clear();
      map.clear();
      lines::extractText(line, &text, &map);
      if (text.empty()) {
        eng.addLine(std::string_view(), nullptr);
        continue;
      }
      eng.addLine(text, map.data());
    }
    for (const SearchHit& h : eng.hits()) {
      if (h.lineId < 0 || size_t(h.lineId) >= logical.size()) continue;
      const lines::LogicalLine& line = logical[size_t(h.lineId)];
      if (h.startRow < 0 || size_t(h.startRow) >= line.rows.size()) continue;
      SearchMatchInfo m;
      m.startRow = line.firstAbsRow + h.startRow;
      m.startCol = h.startCol;
      m.endRow = line.firstAbsRow + h.endRow;
      m.endCol = h.endCol;
      searchHits.push_back(m);
    }
    return int(searchHits.size());
  }

  // ═══ v0.2: selection (touch copy/paste) ═══
  void beginSelection(int64_t g, int c) {
    selActive = true;
    selAnchorRow = g;
    selAnchorCol = c;
    selEndRow = g;
    selEndCol = c;
  }

  void extendSelection(int64_t g, int c) {
    if (!selActive) {
      beginSelection(g, c);
      return;
    }
    selEndRow = g;
    selEndCol = c;
  }

  void normalizedSelection(int64_t* sRow, int32_t* sCol, int64_t* eRow, int32_t* eCol) const {
    if (selAnchorRow < selEndRow ||
        (selAnchorRow == selEndRow && selAnchorCol <= selEndCol)) {
      *sRow = selAnchorRow; *sCol = selAnchorCol;
      *eRow = selEndRow; *eCol = selEndCol;
    } else {
      *sRow = selEndRow; *sCol = selEndCol;
      *eRow = selAnchorRow; *eCol = selAnchorCol;
    }
  }

  // Word-char predicate for selection expansion: ASCII alnum/_ plus every
  // non-blank code point >= 0x80 (CJK / emoji are words — mobile keyboards
  // produce them; splitting them would make double-tap selection useless).
  static bool isWordCp(uint32_t cp) {
    if (cp < 0x80) {
      return SearchEngine::isWordChar(char(cp));
    }
    return cp != uint32_t(' ');
  }

  void expandSelectionWord(int64_t g, int c) {
    if (!globalRowCellsExists(g)) return;
    beginSelection(g, c);
    // walk left
    int64_t row = g; int col = c;
    while (true) {
      if (col == 0) {
        int64_t prev = row - 1;
        if (prev < 0 || !globalRowWrapped(prev)) break;
        row = prev;
        col = cols;
        continue;
      }
      const Cell* cells; const RowExtras* ex;
      if (!globalRowCells(row, &cells, &ex)) break;
      int cc = col - 1;
      if (cc > 0 && (cells[cc].flags & kCellWideTrail)) --cc;
      if (!isWordCp(cells[cc].cp)) break;
      col = cc;
    }
    selAnchorRow = row;
    selAnchorCol = col;
    // walk right
    row = g; col = c;
    while (true) {
      const Cell* cells; const RowExtras* ex;
      if (!globalRowCells(row, &cells, &ex)) break;
      if (col >= cols) {
        if (!globalRowWrapped(row)) break;
        ++row;
        col = 0;
        continue;
      }
      const Cell& cell = cells[col];
      if (cell.flags & kCellWideTrail) {
        // Standing on a wide trail: its lead was already accepted — move past.
        ++col;
      } else {
        if (!isWordCp(cell.cp)) break;
        ++col;
        if ((cell.flags & kCellWideLead) && col < cols) ++col;  // skip the trail
      }
      if (col >= cols) {
        if (!globalRowWrapped(row)) break;
        ++row;
        col = 0;
      }
    }
    selEndRow = row;
    selEndCol = col;
  }

  bool globalRowCellsExists(int64_t g) const {
    const Cell* cells; const RowExtras* ex;
    return globalRowCells(g, &cells, &ex);
  }

  void expandSelectionLine(int64_t g, int c) {
    if (!globalRowCellsExists(g)) return;
    beginSelection(g, c);
    int64_t row = g;
    while (row > 0 && globalRowWrapped(row - 1)) --row;  // line start
    selAnchorRow = row;
    selAnchorCol = 0;
    row = g;
    while (globalRowWrapped(row)) ++row;  // line end (row = first NON-wrapped)
    selEndRow = row;
    selEndCol = cols;
  }

  std::string selectionTextImpl() const {
    std::string out;
    if (!selActive) return out;
    int64_t sRow, eRow; int32_t sCol, eCol;
    normalizedSelection(&sRow, &sCol, &eRow, &eCol);
    for (int64_t g = sRow; g <= eRow; ++g) {
      const Cell* cells; const RowExtras* ex;
      if (!globalRowCells(g, &cells, &ex)) continue;  // evicted rows skipped
      int c0 = (g == sRow) ? sCol : 0;
      int c1 = (g == eRow) ? std::min<int>(eCol, cols) : cols;
      size_t rowStart = out.size();
      for (int c = c0; c < c1 && c < cols; ++c) {
        if (cells[c].flags & kCellWideTrail) continue;
        uint32_t cp = cells[c].cp == 0 ? uint32_t(' ') : cells[c].cp;
        appendUtf8(&out, cp);
        if (ex) {
          if (const std::vector<uint32_t>* comb = ex->find(uint16_t(c))) {
            for (uint32_t mc : *comb) appendUtf8(&out, mc);
          }
        }
      }
      // Right-trim the row's trailing blanks (a fully-selected row runs to
      // EOL — copying EOL padding is noise; explicit in-row selection with
      // a trailing space at eCol keeps it because the trim stops at the last
      // non-blank which may be the selected space itself only if… simplest:
      // trim blanks that came from unpadded cells, i.e. cells beyond the
      // row's natural content).
      while (out.size() > rowStart && out.back() == ' ') out.pop_back();
      // Wrap continuation → no separator; hard line end → '\n'.
      if (g != eRow && !globalRowWrapped(g)) out.push_back('\n');
    }
    return out;
  }

  static void appendUtf8(std::string* out, uint32_t cp) {
    if (cp < 0x80) {
      out->push_back(char(cp));
    } else if (cp < 0x800) {
      out->push_back(char(0xC0 | (cp >> 6)));
      out->push_back(char(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
      out->push_back(char(0xE0 | (cp >> 12)));
      out->push_back(char(0x80 | ((cp >> 6) & 0x3F)));
      out->push_back(char(0x80 | (cp & 0x3F)));
    } else {
      out->push_back(char(0xF0 | (cp >> 18)));
      out->push_back(char(0x80 | ((cp >> 12) & 0x3F)));
      out->push_back(char(0x80 | ((cp >> 6) & 0x3F)));
      out->push_back(char(0x80 | (cp & 0x3F)));
    }
  }

  // ═══ v0.2: hyperlink lookup (lazy staleness resolution) ═══
  const char* linkAtImpl(int screenRow, int col) const {
    if (screenRow < 0 || screenRow >= rows || col < 0 || col >= cols) return nullptr;
    const RowExtras* ex = cur->combiningExtras(screenRow);
    if (!ex) return nullptr;
    const LinkSpan* span = ex->linkAt(uint16_t(col));
    if (!span || span->linkId == 0) return nullptr;
    if (size_t(span->linkId - 1) >= linkUris.size()) return nullptr;  // evicted
    const std::string& uri = linkUris[size_t(span->linkId - 1)];
    return uri.empty() ? nullptr : uri.c_str();
  }

  int activeLinkCountImpl() const {
    int n = 0;
    for (int r = 0; r < rows; ++r) {
      if (const RowExtras* ex = cur->combiningExtras(r)) n += int(ex->links.size());
    }
    return n;
  }

  // ═══ v0.2: input encoders (modes honored) ═══
  std::vector<uint8_t> encodeMouseImpl(int type, int button, int mods, int col, int row) const {
    if (mouseModeV == 0) return {};
    mouse::MouseEvent e;
    e.type = mouseTypeOf(type);
    e.button = button;
    e.mods.shift = (mods & 1) != 0;
    e.mods.alt = (mods & 2) != 0;
    e.mods.ctrl = (mods & 4) != 0;
    e.mods.meta = (mods & 8) != 0;
    e.col = col;
    e.row = row;
    return mouse::encode(e, mouseModeOf(mouseModeV), mouseEncodingOf(mouseEncodingV));
  }

  std::vector<uint8_t> encodeFocusImpl(bool focused) const {
    if (!focusReport) return {};
    return input::encodeFocus(focused);
  }

  std::vector<uint8_t> encodeKeyImpl(int key, int mods) const {
    input::InputModes m;
    m.applicationCursorKeys = applicationCursor;
    m.applicationKeypad = applicationKeypad;
    m.modifyLevel = static_cast<input::ModifyLevel>(modifyLevel);
    input::Modifiers km;
    km.shift = (mods & 1) != 0;
    km.alt = (mods & 2) != 0;
    km.ctrl = (mods & 4) != 0;
    km.meta = (mods & 8) != 0;
    return input::encodeKey(static_cast<input::Key>(uint16_t(key)), km, m);
  }

  std::vector<uint8_t> encodePasteImpl(const char* data, size_t len) const {
    return input::encodePasteUtf8(data, len, bracketedPaste);
  }

  std::vector<uint8_t> encodeWheelImpl(int dir) const {
    if (mouseModeV != 0) {
      // Mouse tracking on: the wheel IS a mouse event (button 4/5).
      return encodeMouseImpl(dir == 0 ? 3 : 4, dir == 0 ? 4 : 5, 0, 1, 1);
    }
    if (altScroll && alternateScreen) {
      // 1007: wheel on the alt screen → arrow keys (app-cursor aware).
      std::vector<uint8_t> out;
      if (dir == 0) {
        if (applicationCursor) {
          const uint8_t seq[] = {0x1B, 'O', 'A'};
          out.assign(seq, seq + 3);
        } else {
          const uint8_t seq[] = {0x1B, '[', 'A'};
          out.assign(seq, seq + 3);
        }
      } else {
        if (applicationCursor) {
          const uint8_t seq[] = {0x1B, 'O', 'B'};
          out.assign(seq, seq + 3);
        } else {
          const uint8_t seq[] = {0x1B, '[', 'B'};
          out.assign(seq, seq + 3);
        }
      }
      return out;
    }
    return {};  // host scrolls its viewport
  }

  // ═══ v0.2: session persistence ═══
  std::vector<uint8_t> saveSessionImpl(int maxSb) const {
    session::SessionState st;
    st.rows = rows;
    st.cols = cols;
    // The live cursor belongs to the CURRENT screen; a session snapshot of
    // the main screen records the main cursor (savedCursor when on alt).
    const Cursor& mc = alternateScreen ? savedCursor : cursor;
    st.cursorRow = mc.row;
    st.cursorCol = mc.col;
    st.wrapPending = mc.wrapPending;
    st.cursorVisible = cursorVisible;
    st.applicationCursor = applicationCursor;
    st.bracketedPaste = bracketedPaste;
    st.alternateScreen = false;  // sessions restore to the MAIN screen
    st.reverseVideo = reverseVideo;
    st.originMode = originMode;
    st.cursorShape = cursorShapeV;
    st.scrollTop = scrollTop;
    st.scrollBottom = scrollBottom;
    st.savedCursorRow = savedCursor.row;
    st.savedCursorCol = savedCursor.col;
    st.savedWrapPending = savedCursor.wrapPending;
    st.savedOriginMode = false;  // DECSC does not save origin in our engine
    // Styles: dump in id order (load re-interns in the same order).
    st.styles.reserve(styles.size());
    for (size_t id = 0; id < styles.size(); ++id) st.styles.push_back(styles.get(uint16_t(id)));
    // Grid (with the row wrap flag smuggled on the last cell's flags).
    st.grid.reserve(size_t(rows) * cols);
    for (int r = 0; r < rows; ++r) {
      for (int c = 0; c < cols; ++c) {
        Cell cell = main.cell(r, c);
        if (c == cols - 1 && main.rowWrapped(r)) cell.flags = uint16_t(cell.flags | kCellWrappedSession);
        st.grid.push_back(cell);
      }
    }
    // Visible-screen combining marks.
    for (int r = 0; r < rows; ++r) {
      if (const RowExtras* ex = main.combiningExtras(r)) {
        for (const CombiningMark& m : ex->marks) {
          session::Mark mk;
          mk.row = r;
          mk.col = m.col;
          mk.cps = m.cps;
          st.gridMarks.push_back(std::move(mk));
        }
      }
    }
    // Scrollback (bounded, oldest first, wrap flag smuggled likewise).
    int sbTotal = int(main.scrollbackCount());
    int keep = maxSb < 0 ? sbTotal : std::min(sbTotal, maxSb);
    st.scrollback.reserve(size_t(keep));
    for (int i = sbTotal - keep; i < sbTotal; ++i) {
      session::SessionRow row;
      row.cells.assign(main.scrollbackRowCells(i), main.scrollbackRowCells(i) + cols);
      if (main.scrollbackRowWrapped(i)) {
        Cell& last = row.cells[size_t(cols - 1)];
        last.flags = uint16_t(last.flags | kCellWrappedSession);
      }
      if (const RowExtras* ex = main.scrollbackRowExtras(i)) {
        for (const CombiningMark& m : ex->marks) {
          session::Mark mk;
          mk.row = int(st.scrollback.size());
          mk.col = m.col;
          mk.cps = m.cps;
          row.marks.push_back(std::move(mk));
        }
      }
      st.scrollback.push_back(std::move(row));
    }
    st.scrollbackLinesEver = main.scrollbackLinesEver();
    st.tabStops.assign(tabStops.begin(), tabStops.end());
    st.title = titleStr;
    st.g0DecGraphics = (g0 == G0::kDecGraphics);
    st.mouseMode = mouseModeV;
    st.mouseEncoding = mouseEncodingV;
    st.lastPrintable = lastPrintableCp > 0 ? uint32_t(lastPrintableCp) : 0;
    st.sgrStack.reserve(sgrStack.size());
    for (uint16_t id : sgrStack) st.sgrStack.push_back(styles.get(id));
    std::vector<uint8_t> out;
    session::save(st, &out);
    return out;
  }

  // Rehydrate into a FRESH Impl (called from Engine::restoreSession).
  // Returns false on any inconsistency (style ids must reproduce exactly).
  static bool restoreInto(const session::SessionState& st, int maxSb, Impl* im) {
    if (st.rows < 1 || st.cols < 1 || st.grid.size() != size_t(st.rows) * size_t(st.cols)) {
      return false;
    }
    if (st.styles.empty() || !(st.styles[0] == kDefaultStyle)) return false;  // id 0 = default
    // Re-intern styles in id order. Insertion order defines ids, so the i-th
    // intern MUST return id i — anything else means duplicate styles in the
    // save (impossible from our save path) or a foreign file.
    for (size_t i = 0; i < st.styles.size(); ++i) {
      uint16_t id = im->styles.intern(st.styles[i]);
      if (id != uint16_t(i)) return false;
    }

    // ── rebuild the main screen ──
    im->rows = st.rows;
    im->cols = st.cols;
    im->main.resetTo(st.rows, st.cols);
    im->alt.resetTo(st.rows, st.cols);
    im->setScrollRegion(st.scrollTop, st.scrollBottom);
    // Global row numbering continues at the saved base so restored search /
    // selection coordinates line up with pre-save values.
    im->main.forceScrollbackBase(st.scrollbackLinesEver - int64_t(st.scrollback.size()));
    size_t savedSb = st.scrollback.size();
    for (size_t i = 0; i < savedSb; ++i) {
      const session::SessionRow& row = st.scrollback[i];
      if (row.cells.size() != size_t(st.cols)) return false;
      std::vector<Cell> cells = row.cells;
      bool wrapped = false;
      Cell& last = cells[size_t(st.cols - 1)];
      if (last.flags & kCellWrappedSession) {
        wrapped = true;
        last.flags = uint16_t(last.flags & ~kCellWrappedSession);
      }
      auto ex = std::make_unique<RowExtras>();
      for (const session::Mark& mk : row.marks) {
        if (mk.col < 0 || mk.col >= st.cols) return false;
        ex->marks.push_back(CombiningMark{uint16_t(mk.col), mk.cps});
      }
      if (ex->empty()) ex.reset();
      im->main.pushScrollbackRowCells(std::move(cells), std::move(ex), wrapped);
      if (maxSb >= 0 && im->main.scrollbackCount() > maxSb) {
        return false;  // save lied about the bound — reject (defensive)
      }
    }
    // Visible grid.
    for (int r = 0; r < st.rows; ++r) {
      std::vector<Cell> cells(st.cols);
      bool wrapped = false;
      for (int c = 0; c < st.cols; ++c) {
        cells[size_t(c)] = st.grid[size_t(r) * size_t(st.cols) + size_t(c)];
        Cell& cell = cells[size_t(c)];
        if (cell.style >= st.styles.size()) return false;  // style id out of range
        if (c == st.cols - 1 && (cell.flags & kCellWrappedSession)) {
          wrapped = true;
          cell.flags = uint16_t(cell.flags & ~kCellWrappedSession);
        }
      }
      auto ex = std::make_unique<RowExtras>();
      for (const session::Mark& mk : st.gridMarks) {
        if (mk.row == r) {
          if (mk.col < 0 || mk.col >= st.cols) return false;
          ex->marks.push_back(CombiningMark{uint16_t(mk.col), mk.cps});
        }
      }
      if (ex->empty()) ex.reset();
      im->main.loadRow(r, cells.data(), st.cols, std::move(ex), wrapped);
    }

    // ── modes / cursor / misc ──
    im->cursor.row = std::max(0, std::min(st.cursorRow, st.rows - 1));
    im->cursor.col = std::max(0, std::min(st.cursorCol, st.cols - 1));
    im->cursor.wrapPending = st.wrapPending;
    im->cursorVisible = st.cursorVisible;
    im->applicationCursor = st.applicationCursor;
    im->bracketedPaste = st.bracketedPaste;
    im->alternateScreen = false;
    im->cur = &im->main;
    im->reverseVideo = st.reverseVideo;
    im->originMode = st.originMode;
    im->cursorShapeV = st.cursorShape;
    im->savedCursor.row = std::max(0, std::min(st.savedCursorRow, st.rows - 1));
    im->savedCursor.col = std::max(0, std::min(st.savedCursorCol, st.cols - 1));
    im->savedCursor.wrapPending = st.savedWrapPending;
    // tabs
    im->tabStops.assign(size_t(st.cols), 0);
    for (size_t i = 0; i < st.tabStops.size() && i < im->tabStops.size(); ++i) {
      im->tabStops[i] = st.tabStops[i] ? 1 : 0;
    }
    // title / charsets / mouse / misc
    im->titleStr = st.title;
    im->hasTitle = !st.title.empty();
    im->g0 = st.g0DecGraphics ? G0::kDecGraphics : G0::kAscii;
    im->mouseModeV = st.mouseMode;
    im->mouseEncodingV = st.mouseEncoding;
    im->lastPrintableCp = st.lastPrintable != 0 ? int32_t(st.lastPrintable) : -1;
    // SGR stack (interned ids reproduce: same table, same order)
    im->sgrStack.clear();
    for (const Style& s : st.sgrStack) {
      im->sgrStack.push_back(im->styles.intern(s));
    }
    im->addMutation(MutationType::kFull, 0, st.rows - 1);
    return true;
  }
};

// ─────────────────────────────────────────────────────────────────────────
Engine::Engine(int rows, int cols, int maxScrollback)
    : impl_(std::make_unique<Impl>(rows, cols, maxScrollback)) {}

Engine::~Engine() = default;
Engine::Engine(Engine&&) noexcept = default;
Engine& Engine::operator=(Engine&&) noexcept = default;

void Engine::feed(const uint8_t* data, size_t len) {
  size_t i = 0;
  while (i < len) {
    // Fast path: a run of plain printable ASCII while the parser idles in
    // GROUND with no pending UTF-8 — byte-identical semantics to the slow
    // path (which would emit Printable(cp) per byte), minus the per-char
    // indirection.
    if (impl_->parserIsGroundAndIdle() && data[i] >= 0x20 && data[i] < 0x7F) {
      size_t start = i;
      do {
        ++i;
      } while (i < len && data[i] >= 0x20 && data[i] < 0x7F);
      impl_->printAsciiRun(data + start, i - start);
      continue;
    }
    if (impl_->feedByte(data[i])) {
      ++i;
    }  // else: re-process the same byte (invalid UTF-8 continuation)
  }
}

void Engine::flush() {
  if (impl_->utfCount > 0) {
    impl_->feedCp(0xFFFD);
    impl_->utfCount = 0;
    impl_->utfExpected = 0;
  }
}

void Engine::resize(int rows, int cols) { impl_->resize(rows, cols); }
void Engine::reset() { impl_->reset(); }

int Engine::rows() const { return impl_->rows; }
int Engine::cols() const { return impl_->cols; }
int Engine::cursorRow() const { return impl_->cursor.row; }
int Engine::cursorCol() const { return impl_->cursor.col; }
bool Engine::cursorVisible() const { return impl_->cursorVisible; }
bool Engine::applicationCursor() const { return impl_->applicationCursor; }
bool Engine::bracketedPaste() const { return impl_->bracketedPaste; }
bool Engine::alternateScreen() const { return impl_->alternateScreen; }
bool Engine::reverseVideo() const { return impl_->reverseVideo; }
CursorShape Engine::cursorShape() const { return impl_->cursorShapeV; }
const char* Engine::title() const { return impl_->hasTitle ? impl_->titleStr.c_str() : nullptr; }

std::string Engine::renderedText() const { return impl_->renderedText(); }

std::vector<std::string> Engine::scrollbackText(int maxLines) const {
  std::vector<std::string> out;
  if (maxLines <= 0) return out;
  int total = int(impl_->main.scrollbackCount());
  int from = total - maxLines;
  if (from < 0) from = 0;
  out.reserve(size_t(total - from));
  for (int i = from; i < total; ++i) out.push_back(impl_->main.scrollbackRowText(i));
  return out;
}

FlatSnapshot Engine::renderSnapshot(int maxScrollbackLines) {
  return impl_->renderSnapshotImpl(maxScrollbackLines);
}

int Engine::scrollbackTotal() const { return int(impl_->main.scrollbackCount()); }
int64_t Engine::scrollbackBase() const { return impl_->main.scrollbackLinesEver(); }
int64_t Engine::drainBell() { return impl_->drainBell(); }

std::vector<std::string> Engine::drainClipboardRequests() {
  std::vector<std::string> out = impl_->clipboard;
  impl_->clipboard.clear();
  return out;
}

std::vector<Mutation> Engine::drainMutations() {
  std::vector<Mutation> out = impl_->mutations;
  impl_->mutations.clear();
  return out;
}

std::vector<uint8_t> Engine::pollResponses() {
  std::vector<uint8_t> out = impl_->responses;
  impl_->responses.clear();
  return out;
}

size_t Engine::cellFootprint() const {
  return impl_->main.cellFootprint() + impl_->alt.cellFootprint();
}

size_t Engine::pendingMutationCount() const { return impl_->mutations.size(); }

// ═══ v0.2 foundation API ═══

std::vector<uint8_t> Engine::encodeMouseEvent(int type, int button, int mods,
                                              int col, int row) const {
  return impl_->encodeMouseImpl(type, button, mods, col, row);
}

std::vector<uint8_t> Engine::encodeFocusEvent(bool focused) const {
  return impl_->encodeFocusImpl(focused);
}

std::vector<uint8_t> Engine::encodeKey(int key, int mods) const {
  return impl_->encodeKeyImpl(key, mods);
}

std::vector<uint8_t> Engine::encodePasteUtf8(const char* data, size_t len) const {
  return impl_->encodePasteImpl(data, len);
}

std::vector<uint8_t> Engine::encodeWheel(int dir) const {
  return impl_->encodeWheelImpl(dir);
}

int Engine::search(const char* patternUtf8, bool caseInsensitive, bool wholeWord) {
  return impl_->searchImpl(patternUtf8, caseInsensitive, wholeWord);
}

void Engine::clearSearch() {
  impl_->searchHits.clear();
  impl_->activeHit = -1;
}

int Engine::searchHitCount() const { return int(impl_->searchHits.size()); }

SearchMatchInfo Engine::searchHit(int i) const {
  if (i < 0 || size_t(i) >= impl_->searchHits.size()) return SearchMatchInfo{};
  return impl_->searchHits[size_t(i)];
}

void Engine::setActiveSearchHit(int index) {
  if (index >= -1 && index < int(impl_->searchHits.size())) impl_->activeHit = index;
}

int Engine::activeSearchHit() const { return impl_->activeHit; }

void Engine::beginSelection(int64_t globalRow, int col) {
  impl_->beginSelection(globalRow, col);
}

void Engine::extendSelection(int64_t globalRow, int col) {
  impl_->extendSelection(globalRow, col);
}

void Engine::clearSelection() {
  impl_->selActive = false;
  impl_->selAnchorRow = impl_->selEndRow = -1;
}

void Engine::expandSelectionWord(int64_t globalRow, int col) {
  impl_->expandSelectionWord(globalRow, col);
}

void Engine::expandSelectionLine(int64_t globalRow, int col) {
  impl_->expandSelectionLine(globalRow, col);
}

std::string Engine::selectionText() const { return impl_->selectionTextImpl(); }

const char* Engine::linkAt(int screenRow, int col) const {
  return impl_->linkAtImpl(screenRow, col);
}

std::vector<uint8_t> Engine::saveSession(int maxScrollbackRows) const {
  return impl_->saveSessionImpl(maxScrollbackRows);
}

std::unique_ptr<Engine> Engine::restoreSession(const uint8_t* data, size_t len,
                                               int maxScrollback) {
  session::SessionState st;
  if (!session::load(data, len, &st)) return nullptr;
  auto eng = std::make_unique<Engine>(st.rows > 0 ? st.rows : 1,
                                      st.cols > 0 ? st.cols : 1, maxScrollback);
  if (!Impl::restoreInto(st, maxScrollback, eng->impl_.get())) {
    return nullptr;  // unique_ptr frees the partial engine
  }
  return eng;
}

int Engine::activeLinkCount() const { return impl_->activeLinkCountImpl(); }

}  // namespace apex::vt
