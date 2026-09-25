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
#include <cstring>

#include "vt_parser.h"
#include "vt_screen.h"
#include "vt_style.h"
#include "vt_width.h"

namespace apex::vt {

namespace {

constexpr size_t kMaxPendingMutations = 4096;  // parity with Kotlin
constexpr size_t kMaxPendingClipboard = 8;     // parity with Kotlin
constexpr int kMaxRepRepeat = 1024;             // T85 REP guard

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
  Impl(int rows, int cols, int maxScrollback)
      : main(rows, cols, maxScrollback, true, styles),
        alt(rows, cols, 0, false, styles),
        maxScrollback(maxScrollback),
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
    // BoundedMutationList parity: on overflow, clear + fold to FULL, then
    // append the new element (Kotlin folds to [FULL, element]).
    if (mutations.size() >= kMaxPendingMutations) {
      mutations.clear();
      mutations.push_back(Mutation{MutationType::kFull, 0, 0});
    }
    mutations.push_back(Mutation{type, first, last});
  }
  void mutationRows(int r) { addMutation(MutationType::kCells, r, r); }

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
    int overlap = std::min(cols, newCols);
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
      Cell cell{cp, curStyle, width == 2 ? uint16_t(kCellWideLead) : uint16_t(0)};
      cur->put(r, c, cell);
      lastBaseRow = r;
      lastBaseCol = c;
      mutationRows(r);
      cursor.col = c + width > cols - 1 ? cols - 1 : c + width;
      cursor.wrapPending = false;
      return;
    }

    int prow = cursor.row, pcol = cursor.col;
    if (cursor.wrapPending && autoWrap) {
      ++prow;
      pcol = 0;
      cursor.wrapPending = false;
      if (prow > scrollBottom) {
        cur->scrollUp(1, scrollTop, scrollBottom);
        prow = scrollBottom;
      }
    }
    if (width == 2 && pcol >= cols - 1) {  // wide char at last column → wrap (§9)
      ++prow;
      pcol = 0;
      if (prow > scrollBottom) {
        cur->scrollUp(1, scrollTop, scrollBottom);
        prow = scrollBottom;
      }
    }

    Cell cell{cp, curStyle, width == 2 ? uint16_t(kCellWideLead) : uint16_t(0)};
    cur->put(prow, pcol, cell);
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
        if (rowEx) rowEx->removeCol(uint16_t(pcol - 1));
      }
      if (rowEx) rowEx->removeCol(uint16_t(pcol));
      dst.cp = cp;
      dst.style = curStyle;
      dst.flags = 0;
      lastBaseRow = prow;
      lastBaseCol = pcol;
      lastPrintableCp = int32_t(cp);
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
      case 'J': eraseDisplay(seq.param(0, 0)); break;  // ED
      case 'K': eraseLine(seq.param(0, 0)); break;    // EL
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
      case 'r': {                      // DECSTBM
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
      case 'q':  // DECSCUSR — CSI Ps SP q
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == ' ') {
          switch (seq.paramOrDefault(0, 0)) {
            case 3:
            case 4: cursorShapeV = CursorShape::kUnderline; break;
            case 5:
            case 6: cursorShapeV = CursorShape::kBar; break;
            default: cursorShapeV = CursorShape::kBlock; break;  // 0/1/2 + other
          }
        }
        break;
      case 'c':  // DA1 / DA2
        if (seq.privateMarker == '>') {
          respond("\x1B[>0;276;0c");
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
      case 'p':  // DECSTR — CSI ! p
        if (seq.intermediates.size() == 1 && seq.intermediates[0] == '!') softReset();
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
      case 'u':  // restore
        cursor = savedCursor;
        curStyle = savedStyle;
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
    mutationRows(cursor.row);  // parity: ALWAYS, even for unknown CSI
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
    addMutation(MutationType::kFull, 0, 0);
  }

  // ═══ OSC (§20) ═══
  void handleOsc(int32_t code, const std::vector<char16_t>& data) {
    switch (code) {
      case 0:
      case 1:
      case 2:
        titleStr = utf16ToUtf8(data);
        hasTitle = true;
        break;
      case 8:  // hyperlink — not stored (future flag on cells)
        break;
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
        break;  // other OSC ignored
    }
  }

  // ═══ ESC (§25) ═══
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

  // ═══ DEC private modes (§3) ═══
  void setDecMode(const std::vector<int32_t>& params, bool enable) {
    for (int32_t p : params) {
      switch (p) {
        case 1: applicationCursor = enable; break;
        case 4: insertMode = enable; break;
        case 5: reverseVideo = enable; break;
        case 6: originMode = enable; break;
        case 7: autoWrap = enable; break;
        case 25: cursorVisible = enable; break;
        case 2004: bracketedPaste = enable; break;
        case 47:
        case 1047: switchAlternateScreen(enable, false); break;
        case 1049: switchAlternateScreen(enable, true); break;
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
    main.resize(newRows, newCols);
    alt.resize(newRows, newCols);
    rows = newRows;
    cols = newCols;
    setScrollRegion(0, newRows - 1);
    resizeTabs(newCols);
    if (cursor.row >= newRows) cursor.row = newRows - 1;
    if (cursor.col >= newCols) cursor.col = newCols - 1;
    addMutation(MutationType::kResize, 0, newRows - 1);
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
        uint16_t flags = 0;
        if (st.attr & kAttrBold) flags |= kRfBold;
        if (st.attr & kAttrDim) flags |= kRfDim;
        if (st.attr & kAttrItalic) flags |= kRfItalic;
        if (ulOf(st.attr) != kUlNone) flags |= kRfUnderline;
        if (st.attr & kAttrBlink) flags |= kRfBlink;
        if (st.attr & kAttrHidden) flags |= kRfHidden;
        if (st.attr & kAttrStrike) flags |= kRfStrike;
        if ((st.attr & kAttrInverse) || reverseVideo) flags |= kRfInverse;
        if (cells[i].flags & kCellWideLead) flags |= kRfWide;
        fc.flags = flags;
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
    return snap;
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

}  // namespace apex::vt
