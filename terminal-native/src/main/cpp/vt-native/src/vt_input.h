// apex-vt-input — key-event encoding for the PTY input path (Android side).
//
// Contract: pure functions that turn abstract key events (Android IME /
// soft-keyboard) plus the terminal's currently negotiated modes into the exact
// byte sequences xterm-compatible applications expect on stdin:
//   - DECCKM  (application cursor keys): ESC O A… instead of ESC [ A…
//   - DECKPAM (application keypad):      SS3 keypad table instead of ASCII
//   - modifyOtherKeys level 1/2 and the Kitty keyboard protocol
// plus paste sanitization (anti sequence-injection), focus reporting and a
// reusable UTF-8 prefix validator. An empty result means "not encodable" —
// the caller drops the event or routes it through the text path.
// No exceptions, no I/O, no global state.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace apex::vt::input {

// Abstract key identity. Printable text arrives through the paste/text path,
// not through this enum — only keys with dedicated terminal encodings.
enum class Key : uint16_t {
  kNone = 0,
  kUp, kDown, kRight, kLeft,
  kHome, kEnd, kPageUp, kPageDown, kInsert, kDelete,
  kEnter, kTab, kBackspace, kEscape, kSpace,
  kF1, kF2, kF3, kF4, kF5, kF6, kF7, kF8, kF9, kF10, kF11, kF12,
  kNumpad0, kNumpad1, kNumpad2, kNumpad3, kNumpad4,
  kNumpad5, kNumpad6, kNumpad7, kNumpad8, kNumpad9,
  kNumpadDecimal, kNumpadDivide, kNumpadMultiply, kNumpadSubtract,
  kNumpadAdd, kNumpadEnter, kNumpadSeparator,
};

struct Modifiers {
  bool shift = false;
  bool ctrl = false;
  bool alt = false;
  bool meta = false;
};

// modifyOtherKeys (xterm `CSI > 1;mode c`) and the Kitty keyboard protocol
// form a progression of encodings for modified keys.
enum class ModifyLevel : uint8_t {
  kNone = 0,                    // traditional xterm encoding
  kLegacy = 0,                  // alias of kNone (same negotiated level)
  kModifyOtherKeys1 = 1,        // CSI 27;<m>;<asciicode>~ (ctrl/alt combos on
                                // Enter/Tab/Backspace/Escape)
  kModifyOtherKeys2 = 2,        // CSI 27;<m>;<ucs>~ (any modifier on the text
                                // keys; function keys stay parameterized)
  kKittyProtocol = 3,           // CSI <ucs>;<m+1>u (functional-code form)
};

struct InputModes {
  bool applicationCursorKeys = false;  // DECCKM (mode 1)
  bool applicationKeypad = false;      // DECKPAM (mode 66 / ESC =)
  ModifyLevel modifyLevel = ModifyLevel::kNone;
};

// Main entry: encode [k] under [m] and [modes]. Empty result = not encodable
// (caller drops the event or falls back to the text path).
std::vector<uint8_t> encodeKey(Key k, Modifiers m, const InputModes& modes);

// Paste sanitization + bracketed wrapping: strips every C0 byte except
// \r \n \t (ESC included — prevents escape-sequence injection), truncates to
// the valid UTF-8 prefix, and — when [bracketed] — wraps the payload in
// ESC[200~ … ESC[201~ (caller checks mode 1004).
std::vector<uint8_t> encodePasteUtf8(const char* data, size_t len, bool bracketed);

// Focus events: CSI I (gained) / CSI O (lost). Caller checks mode 1004 first.
std::vector<uint8_t> encodeFocus(bool focused);

// Length of the longest valid UTF-8 prefix of [data]: rejects stray
// continuations, overlong forms, surrogates and > U+10FFFF; a truncated
// final sequence ends the prefix. Null / empty input yields 0.
size_t utf8ValidPrefix(const char* data, size_t len);

// Modifier parameter byte: 1 + (shift | alt<<1 | ctrl<<2 | meta<<3).
uint8_t modifierParam(const Modifiers& m);

}  // namespace apex::vt::input
