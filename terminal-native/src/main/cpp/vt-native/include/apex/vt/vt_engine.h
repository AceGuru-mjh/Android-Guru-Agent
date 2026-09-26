// apex-vt-native — public C++ API.
//
// A C++17, dependency-free, zero-allocation-hot-path VT/ANSI terminal
// emulation core. Semantic port of the Kotlin TerminalCore 2.0 engine
// (Android-Guru-Agent, ATR 2.1 PR #53 + T82/T85 hardening) with a flat-POD
// storage model designed for JNI embedding on Android.
//
//   PTY bytes → feed() → Utf8Decoder → VtParser → Screen → renderSnapshot()
//
// v0.2 foundation additions (all engine-level, host-invoked — never on the
// PTY hot path):
//   * mouse tracking modes 1000/1001/1002/1003 + encodings 1005/1006/1015
//     → encodeMouseEvent() produces the bytes the host writes back to the PTY
//   * focus reporting (1004), alternate scroll (1007), sync output (2026)
//   * OSC 8 hyperlinks (bounded URI table + per-row sparse spans)
//   * DECRQM/DECSED/DECSEL/DECSCA, rect ops (DECERA/DECSERA/DECCRA/DECFRA),
//     XTPUSHSGR/XTPOPSGR, OSC 4/10/11/12 color queries
//   * text search over screen + scrollback (cross-wrap, case-folded)
//   * resize reflow (rewrap — content survives rotation / split-screen)
//   * session save/restore (binary, CRC-guarded)
//   * key input encoding (DECCKM/DECKPAM/modifyOtherKeys/Kitty) + paste
//     wrapping + wheel→arrow alternate scroll
//
// Robustness contract (Spec §24/§25): never crashes on malformed input —
// unknown sequences are ignored, parser state resets, all buffers bounded.
#pragma once

#include "vt_types.h"
#include "vt_version.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace apex::vt {

// Forward declarations of the input/mouse vocabulary (see vt_input.h /
// vt_mouse.h — internal headers, but the enums are part of the public API).
namespace input {
enum class Key : uint16_t;
struct Modifiers;
}  // namespace input
namespace mouse {
enum class Type : uint8_t;
}  // namespace mouse

// ─── Flat render projection (UI / JNI friendly) ──────────────────────────
// flags — parity with Kotlin RenderCell.FLAG_*.
struct FlatCell {
  uint32_t cp;        // base code point (0 → ' ')
  uint32_t combOff;   // offset into FlatSnapshot::combPool
  uint32_t combCount;
  uint32_t fg;        // 0xAARRGGBB (0 = theme default)
  uint32_t bg;
  uint16_t flags;     // RenderFlag bitmask
  uint16_t link;      // OSC 8 hyperlink id (1-based index into links)
};

struct FlatRow {
  std::vector<FlatCell> cells;  // trailing default-blanks trimmed, trails skipped
};

struct SearchMatchInfo {
  int64_t startRow = 0;   // global row (0 == oldest retained at search time)
  int32_t startCol = 0;
  int64_t endRow = 0;
  int32_t endCol = 0;     // one-past-end column
};

struct FlatSnapshot {
  int32_t rows = 0, cols = 0;
  int32_t cursorRow = 0, cursorCol = 0;
  bool cursorVisible = true;
  CursorShape cursorShape = CursorShape::kBar;
  bool alternateScreen = false;
  bool applicationCursor = false;
  bool bracketedPaste = false;
  bool reverseVideo = false;
  std::string title;  // empty = none
  int32_t scrollbackTotal = 0;
  int64_t scrollbackBase = 0;
  int64_t bellSeq = 0;
  std::vector<uint32_t> combPool;  // combining marks, referenced by FlatCell
  std::vector<FlatRow> visible;
  std::vector<FlatRow> scrollback;  // oldest first

  // ── v0.2 additions (defaults keep the v0.1 projection contract) ──
  // Hyperlinks live on screen (id → URI). FlatCell::link is a 1-based index
  // into this list; 0 = no link. Rows may reference links at any position.
  std::vector<std::string> links;
  int64_t selStartRow = -1;   // selection extent, global rows; -1 = none
  int64_t selEndRow = -1;
  int32_t selStartCol = 0;
  int32_t selEndCol = 0;
  int32_t searchHitCount = 0;   // engine-side hit count (searchHits())
  int32_t activeSearchHit = -1;
  // Input-encoding mode mirror: the host routes keyboard/wheel through
  // encode*() and mirrors these values into its UI state if needed.
  int8_t mouseMode = 0;         // 0 off, 1 X10, 2 normal, 3 button, 4 any
  int8_t mouseEncoding = 0;     // 0 X11, 1 UTF-8, 2 SGR, 3 urxvt
  bool focusReport = false;     // mode 1004
  bool altScroll = false;       // mode 1007
  bool applicationKeypad = false;
  uint8_t modifyLevel = 0;      // 0 none, 1/2 modifyOtherKeys, 3 Kitty
};

// ─── Engine ───────────────────────────────────────────────────────────────
class Engine {
 public:
  // rows/cols >= 1; maxScrollback <= 0 disables scrollback capture.
  Engine(int rows, int cols, int maxScrollback = 1000);
  ~Engine();

  Engine(const Engine&) = delete;
  Engine& operator=(const Engine&) = delete;
  Engine(Engine&&) noexcept;
  Engine& operator=(Engine&&) noexcept;

  // Feed raw PTY bytes (may split UTF-8 / escape sequences across calls).
  void feed(const uint8_t* data, size_t len);
  // Flush an incomplete UTF-8 sequence as U+FFFD (stream end).
  void flush();
  // Resize (SIGWINCH): keeps top-left content, resets scroll region/tabs.
  void resize(int rows, int cols);
  // RIS — full reset (screen, modes, styles, scrollback, parser, title).
  void reset();

  // ─── cheap state accessors ───
  int rows() const;
  int cols() const;
  int cursorRow() const;
  int cursorCol() const;
  bool cursorVisible() const;       // DECTCEM (25)
  bool applicationCursor() const;   // DECCKM (1)
  bool bracketedPaste() const;      // mode 2004
  bool alternateScreen() const;     // modes 47/1047/1049
  bool reverseVideo() const;        // DECSCNM (5)
  CursorShape cursorShape() const;  // DECSCUSR
  const char* title() const;        // nullptr = none

  // ─── projections ───
  // Visible screen as plain text, rows joined by '\n' (trailing blanks trim).
  std::string renderedText() const;
  // Last [maxLines] scrollback rows as plain text, oldest first (main screen).
  std::vector<std::string> scrollbackText(int maxLines) const;
  // Full styled snapshot for the UI grid renderer. DRAINS the bell (parity
  // with Kotlin renderSnapshot — bellSeq increments once per audible BEL).
  FlatSnapshot renderSnapshot(int maxScrollbackLines);

  // ─── drains (consumptive reads) ───
  int scrollbackTotal() const;        // main screen only
  int64_t scrollbackBase() const;     // monotonic lines-ever-scrolled
  int64_t drainBell();                // bellSeq after consuming a pending BEL
  std::vector<std::string> drainClipboardRequests();  // OSC 52 payload queue
  std::vector<Mutation> drainMutations();             // bounded dirty regions
  // Terminal self-generated responses (DA1/DA2/DSR-CPR) — the host writes
  // these back to the PTY. Buffer drained by this call.
  std::vector<uint8_t> pollResponses();

  // ─── diagnostics (tests / fuzz) ───
  // Total bytes of POD cell storage (visible + scrollback) — leak guard.
  size_t cellFootprint() const;
  size_t pendingMutationCount() const;

  // ═══ v0.2 foundation API (host-invoked; never on the PTY hot path) ═══

  // ─── mouse / focus / keyboard input encoding ───
  // Encode a mouse event against the CURRENT tracking mode + encoding.
  // Empty result = the event is not reportable (mode off / gate mismatch).
  // type: 0 press, 1 release, 2 motion, 3 wheel-up, 4 wheel-down,
  //       5 wheel-left, 6 wheel-right. col/row 1-based screen coords.
  std::vector<uint8_t> encodeMouseEvent(int type, int button, int mods,
                                        int col, int row) const;
  // Focus change (mode 1004). Empty when reporting is off.
  std::vector<uint8_t> encodeFocusEvent(bool focused) const;
  // Key press → xterm/Kitty-compatible bytes honoring DECCKM / DECKPAM /
  // modifyOtherKeys / Kitty level. key uses the input::Key ordinal; mods is
  // a bitfield: 1 shift, 2 alt, 4 ctrl, 8 meta.
  std::vector<uint8_t> encodeKey(int key, int mods) const;
  // Paste text (UTF-8) sanitized + bracket-wrapped per mode 2004.
  std::vector<uint8_t> encodePasteUtf8(const char* data, size_t len) const;
  // Physical wheel event: routes to mouse encoding when tracking is on,
  // else to arrow keys when alternate scroll (1007) is active on the alt
  // screen. Empty = the host should scroll its viewport instead.
  std::vector<uint8_t> encodeWheel(int dir /*0 up, 1 down*/) const;

  // ─── search (screen + scrollback, cross-wrap, case-folded) ───
  // Returns the hit count (0 when the pattern is empty). Hits stay valid
  // while content is only APPENDED; any reflow / full reset clears them.
  int search(const char* patternUtf8, bool caseInsensitive, bool wholeWord);
  void clearSearch();
  int searchHitCount() const;
  // Hit [i] in global coordinates (see FlatSnapshot::scrollbackBase).
  SearchMatchInfo searchHit(int i) const;
  // Highlight cursor: -1 = none. UIs render the active hit differently.
  void setActiveSearchHit(int index);
  int activeSearchHit() const;

  // ─── selection (touch copy/paste) ───
  // Global row coordinates (same numbering as search hits).
  void beginSelection(int64_t globalRow, int col);
  void extendSelection(int64_t globalRow, int col);
  void clearSelection();
  // Expand to the word / whole logical line around (globalRow, col).
  void expandSelectionWord(int64_t globalRow, int col);
  void expandSelectionLine(int64_t globalRow, int col);
  // Plain-text content of the selection: wrapped rows joined WITHOUT a
  // separator (logical line reassembly), hard line ends with '\n'.
  std::string selectionText() const;
  // OSC 8 URI active at a screen position (row/col are screen coordinates),
  // or nullptr. Invalidation is lazy (structure-changing mutations drop
  // spans in their rows).
  const char* linkAt(int screenRow, int col) const;

  // ─── session persistence (Android process-death recovery) ───
  // Serialize the MAIN screen + scrollback (bounded), modes, styles, tabs,
  // title. Alternate-screen content is transient (xterm semantics) and not
  // saved. Returns the byte blob for host-side storage.
  std::vector<uint8_t> saveSession(int maxScrollbackRows) const;
  // Rehydrate an engine from a saveSession() blob. Returns nullptr on any
  // validation failure (corruption never crashes). maxScrollback applies to
  // the rebuilt ring.
  static std::unique_ptr<Engine> restoreSession(const uint8_t* data, size_t len,
                                                int maxScrollback);

  // ─── hyperlink diagnostics ───
  // Number of links currently reachable from screen/scrollback spans.
  int activeLinkCount() const;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace apex::vt
