// apex-vt-native — public C++ API.
//
// A C++17, dependency-free, zero-allocation-hot-path VT/ANSI terminal
// emulation core. Semantic port of the Kotlin TerminalCore 2.0 engine
// (Android-Guru-Agent, ATR 2.1 PR #53 + T82/T85 hardening) with a flat-POD
// storage model designed for JNI embedding on Android.
//
//   PTY bytes → feed() → Utf8Decoder → VtParser → Screen → renderSnapshot()
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

// ─── Flat render projection (UI / JNI friendly) ──────────────────────────
// flags — parity with Kotlin RenderCell.FLAG_*.
struct FlatCell {
  uint32_t cp;        // base code point (0 → ' ')
  uint32_t combOff;   // offset into FlatSnapshot::combPool
  uint32_t combCount;
  uint32_t fg;        // 0xAARRGGBB (0 = theme default)
  uint32_t bg;
  uint16_t flags;     // RenderFlag bitmask
};

struct FlatRow {
  std::vector<FlatCell> cells;  // trailing default-blanks trimmed, trails skipped
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

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace apex::vt
