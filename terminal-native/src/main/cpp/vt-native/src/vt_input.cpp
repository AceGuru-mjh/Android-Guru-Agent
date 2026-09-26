// apex-vt-input — key-event encoder implementation (see vt_input.h).
//
// Byte-exact xterm contract implemented here:
//   - Arrows/Home/End:      normal `CSI <L>`; DECCKM `SS3 <L>`; any modifier
//                           upgrades to the parameterized `CSI 1;<m><L>` form
//                           (modifiers override DECCKM, exactly like xterm).
//   - Ins/Del/PgUp/PgDn:    `CSI <n>~` / `CSI <n>;<m>~` (codes 2/3/5/6).
//   - F1..F4:               `SS3 P/Q/R/S`; modified `CSI 1;<m>P…`.
//   - F5..F12:              `CSI <n>~` with codes 15,17,18,19,20,21,23,24.
//   - Enter/Tab/Backspace/Escape/Space: legacy control bytes; alt/meta = ESC
//     prefix; ctrl has per-key translations (Enter→LF, Backspace→BS,
//     Space→NUL). shift+Tab is the classic back-tab `CSI Z`.
//   - Keypad: DECKPAM → SS3 + the xterm table (0..9 = p..y, '.' n, '/' o,
//     '*' j, '-' k, '+' l, Enter M, separator ',' → l — xterm maps BOTH '+'
//     and ',' to SS3 'l', historic behavior kept deliberately); numeric mode
//     → plain ASCII. Modified keypad keys degrade to the text path because
//     application keypad has no modified spelling.
//   - modifyOtherKeys 1: Enter/Tab/Backspace/Escape with ctrl or alt →
//     `CSI 27;<m>;<asciicode>~`.
//   - modifyOtherKeys 2: those keys plus Space with ANY modifier →
//     `CSI 27;<m>;<ucs>~`; function keys keep the parameterized forms.
//   - Kitty protocol: `CSI <ucs>;<m+1>u` for the five text keys and
//     `CSI <fn>;<m+1>u` for editing/function keys. Applied only to MODIFIED
//     keys — unmodified keys keep the legacy bytes (the conservative
//     "disambiguate" tier; callers enable this level only after the
//     application negotiated the protocol).
//
// Known limitations (documented deliberately):
//   - The Kitty functional-code table below (Up=1, Down=2, Right=3, Left=4,
//     PgUp=5, PgDn=6, Home=7, End=8, Ins=2, Del=3, F1..F12=13..24) follows
//     the project's agreed convention. Real Kitty assigns distinct 573xx
//     codes to editing keys; the simplified table shares code points with
//     text keys (Down=Ins=2, Right=Del=3, F1=Enter=13), so it is only
//     lossless when the peer uses the same table.
//   - Keypad digit/operator keys with ctrl at legacy levels return empty
//     (no safe legacy spelling exists; ctrl+digit belongs to the text path).

#include "vt_input.h"

namespace apex::vt::input {
namespace {

using Bytes = std::vector<uint8_t>;

// ── Small append helpers ──────────────────────────────────────────────────

void addCsi(Bytes& b) { b.push_back(0x1b); b.push_back('['); }

void addSs3(Bytes& b) { b.push_back(0x1b); b.push_back('O'); }

void addCh(Bytes& b, char c) { b.push_back(uint8_t(c)); }

void addStr(Bytes& b, const char* s) {
  while (*s != '\0') b.push_back(uint8_t(*s++));
}

// Non-negative decimal (0..2^31-1); mouse/key params only.
void addNum(Bytes& b, int v) {
  char tmp[10];
  int n = 0;
  do {
    tmp[n++] = char('0' + (v % 10));
    v /= 10;
  } while (v > 0);
  while (n > 0) b.push_back(uint8_t(tmp[--n]));
}

// `CSI 27;<m>;<code>~` — modifyOtherKeys level 1/2 payload.
void addCsi27(Bytes& b, int modParam, int code) {
  addCsi(b);
  addNum(b, 27);
  addCh(b, ';');
  addNum(b, modParam);
  addCh(b, ';');
  addNum(b, code);
  addCh(b, '~');
}

// `CSI <code>;<modParam+1>u` — Kitty keyboard-protocol payload. The modifier
// is 1-based shifted by one (kitty: mods = m + 1).
void addKittyU(Bytes& b, int code, int modParam) {
  addCsi(b);
  addNum(b, code);
  addCh(b, ';');
  addNum(b, modParam + 1);
  addCh(b, 'u');
}

// ── Key tables ────────────────────────────────────────────────────────────

// CSI-final / SS3-final letter keys. Letter matches the classic A/B/C/D/H/F
// finals; the mode only selects the CSI vs SS3 introducer.
struct LetterKey {
  Key key;
  char letter;
};
constexpr LetterKey kLetterKeys[] = {
    {Key::kUp, 'A'},   {Key::kDown, 'B'},  {Key::kRight, 'C'}, {Key::kLeft, 'D'},
    {Key::kHome, 'H'}, {Key::kEnd, 'F'},
};

// `CSI <n>~` keys; modifier form `CSI <n>;<m>~`.
struct TildeKey {
  Key key;
  int code;
};
constexpr TildeKey kTildeKeys[] = {
    {Key::kInsert, 2}, {Key::kDelete, 3}, {Key::kPageUp, 5}, {Key::kPageDown, 6},
};

// F1..F4: unmodified `SS3 P/Q/R/S`, modified `CSI 1;<m>P/Q/R/S`.
constexpr LetterKey kF1F4Keys[] = {
    {Key::kF1, 'P'}, {Key::kF2, 'Q'}, {Key::kF3, 'R'}, {Key::kF4, 'S'},
};

// F5..F12: `CSI <n>~` / `CSI <n>;<m>~` — xterm functional tilde codes.
constexpr TildeKey kF5F12Keys[] = {
    {Key::kF5, 15}, {Key::kF6, 17}, {Key::kF7, 18}, {Key::kF8, 19},
    {Key::kF9, 20}, {Key::kF10, 21}, {Key::kF11, 23}, {Key::kF12, 24},
};

// Kitty functional key codes (project convention — see file header): the
// editing block is 1..8, F1..F12 are 13..24.
constexpr TildeKey kKittyFnKeys[] = {
    {Key::kUp, 1},     {Key::kDown, 2},    {Key::kRight, 3},   {Key::kLeft, 4},
    {Key::kPageUp, 5}, {Key::kPageDown, 6}, {Key::kHome, 7},   {Key::kEnd, 8},
    {Key::kInsert, 2}, {Key::kDelete, 3},
    {Key::kF1, 13},    {Key::kF2, 14},     {Key::kF3, 15},     {Key::kF4, 16},
    {Key::kF5, 17},    {Key::kF6, 18},     {Key::kF7, 19},     {Key::kF8, 20},
    {Key::kF9, 21},    {Key::kF10, 22},    {Key::kF11, 23},    {Key::kF12, 24},
};

// Numeric ⇄ application keypad table. `num` is the numeric-mode ASCII byte,
// `app` the DECKPAM SS3-final. xterm maps BOTH '+' and the keypad separator
// ',' to SS3 'l' (historic quirk) — kept here on purpose and asserted by the
// tests. Keypad Enter: numeric CR, application SS3 'M'.
struct KeypadKey {
  Key key;
  char num;
  char app;
};
constexpr KeypadKey kKeypadKeys[] = {
    {Key::kNumpad0, '0', 'p'}, {Key::kNumpad1, '1', 'q'}, {Key::kNumpad2, '2', 'r'},
    {Key::kNumpad3, '3', 's'}, {Key::kNumpad4, '4', 't'}, {Key::kNumpad5, '5', 'u'},
    {Key::kNumpad6, '6', 'v'}, {Key::kNumpad7, '7', 'w'}, {Key::kNumpad8, '8', 'x'},
    {Key::kNumpad9, '9', 'y'},
    {Key::kNumpadDecimal, '.', 'n'},   {Key::kNumpadDivide, '/', 'o'},
    {Key::kNumpadMultiply, '*', 'j'},  {Key::kNumpadSubtract, '-', 'k'},
    {Key::kNumpadAdd, '+', 'l'},       {Key::kNumpadSeparator, ',', 'l'},
    {Key::kNumpadEnter, '\r', 'M'},
};

// The five text keys (code point == ASCII code for all of them).
struct TextKey {
  Key key;
  int ucs;
};
constexpr TextKey kTextKeys[] = {
    {Key::kEnter, 13}, {Key::kTab, 9}, {Key::kBackspace, 127},
    {Key::kEscape, 27}, {Key::kSpace, 32},
};

template <typename T, size_t N>
constexpr size_t arrayLen(const T (&)[N]) {
  return N;
}

// ── Table lookups (linear — every table is tiny) ──────────────────────────

const LetterKey* findLetter(Key k, const LetterKey* table, size_t n) {
  for (size_t i = 0; i < n; ++i) {
    if (table[i].key == k) return &table[i];
  }
  return nullptr;
}

const TildeKey* findTilde(Key k, const TildeKey* table, size_t n) {
  for (size_t i = 0; i < n; ++i) {
    if (table[i].key == k) return &table[i];
  }
  return nullptr;
}

const KeypadKey* findKeypad(Key k) {
  for (size_t i = 0; i < arrayLen(kKeypadKeys); ++i) {
    if (kKeypadKeys[i].key == k) return &kKeypadKeys[i];
  }
  return nullptr;
}

// Unicode code point of a text key (Enter/Tab/Backspace/Escape/Space), -1 if
// [k] is none of them.
int textKeyUcs(Key k) {
  for (size_t i = 0; i < arrayLen(kTextKeys); ++i) {
    if (kTextKeys[i].key == k) return kTextKeys[i].ucs;
  }
  return -1;
}

// Level-1 key set: Enter/Tab/Backspace/Escape (+ keypad Enter as Enter).
// Space deliberately excluded — it keeps the legacy ctrl/alt spellings.
int level1Ascii(Key k) {
  if (k == Key::kEnter || k == Key::kNumpadEnter) return 13;
  if (k == Key::kTab) return 9;
  if (k == Key::kBackspace) return 127;
  if (k == Key::kEscape) return 27;
  return -1;
}

}  // namespace

// ── Public API ────────────────────────────────────────────────────────────

uint8_t modifierParam(const Modifiers& m) {
  int v = 1;
  if (m.shift) v += 1;
  if (m.alt) v += 2;
  if (m.ctrl) v += 4;
  if (m.meta) v += 8;
  return uint8_t(v);
}

std::vector<uint8_t> encodeKey(Key k, Modifiers m, const InputModes& modes) {
  Bytes out;
  if (k == Key::kNone) return out;

  const bool anyMod = m.shift || m.ctrl || m.alt || m.meta;
  const uint8_t mp = modifierParam(m);

  // ── 1. Kitty keyboard protocol (modified keys only) ────────────────────
  // Unmodified keys fall through to the legacy encodings below — the
  // conservative tier: the peer still receives plain CSI A / CR / SS3 P.
  if (modes.modifyLevel == ModifyLevel::kKittyProtocol && anyMod) {
    int code = textKeyUcs(k);
    if (code < 0 && k == Key::kNumpadEnter) code = 13;          // Enter
    if (code < 0) {
      if (const TildeKey* fn = findTilde(k, kKittyFnKeys, arrayLen(kKittyFnKeys))) {
        code = fn->code;                                        // function keys
      } else if (const KeypadKey* kp = findKeypad(k)) {
        code = int(uint8_t(kp->num));                           // keypad text
      }
    }
    if (code >= 0) {
      addKittyU(out, code, int(mp));
      return out;
    }
    return out;  // not encodable in kitty form
  }

  // ── 2. modifyOtherKeys level 2: text keys with ANY modifier ────────────
  // Function keys intentionally keep the parameterized legacy forms.
  if (modes.modifyLevel == ModifyLevel::kModifyOtherKeys2 && anyMod) {
    int ucs = textKeyUcs(k);
    if (ucs < 0 && k == Key::kNumpadEnter) ucs = 13;
    if (ucs >= 0) {
      addCsi27(out, int(mp), ucs);
      return out;
    }
  }

  // ── 3. modifyOtherKeys level 1: ctrl/alt combos on the four keys ───────
  if (modes.modifyLevel == ModifyLevel::kModifyOtherKeys1 && (m.ctrl || m.alt)) {
    const int ascii = level1Ascii(k);
    if (ascii > 0) {
      addCsi27(out, int(mp), ascii);
      return out;
    }
  }

  // ── 4. Keypad ──────────────────────────────────────────────────────────
  if (const KeypadKey* kp = findKeypad(k)) {
    if (k == Key::kNumpadEnter && anyMod) {
      // No modified keypad spelling: behave as the main Enter key.
      return encodeKey(Key::kEnter, m, modes);
    }
    if (!anyMod) {
      if (modes.applicationKeypad) {
        addSs3(out);
        addCh(out, kp->app);
      } else {
        out.push_back(uint8_t(kp->num));
      }
      return out;
    }
    // Modified digit/operator keys degrade to the text path on the key's
    // ASCII value; application keypad does not participate.
    if (m.ctrl) {
      return out;  // no safe legacy ctrl translation for non-letters
    }
    if (m.alt || m.meta) {
      out.push_back(0x1b);  // legacy ESC prefix
    }
    out.push_back(uint8_t(kp->num));
    return out;
  }

  // ── 5. Text keys with legacy modifier rules ────────────────────────────
  //   alt/meta: ESC prefix      ctrl: per-key translation      shift: identity
  switch (k) {
    case Key::kEnter:
      // xterm: Ctrl+Return sends LF.
      if (m.alt || m.meta) out.push_back(0x1b);
      out.push_back(m.ctrl ? uint8_t(0x0A) : uint8_t(0x0D));
      return out;
    case Key::kTab:
      // Pure shift+Tab is the back-tab; other combos keep \t (ctrl has no
      // legacy spelling) with the ESC prefix when alt/meta is held.
      if (m.shift && !m.ctrl && !m.alt && !m.meta) {
        addCsi(out);
        addCh(out, 'Z');
        return out;
      }
      if (m.alt || m.meta) out.push_back(0x1b);
      out.push_back(uint8_t('\t'));
      return out;
    case Key::kBackspace:
      if (m.alt || m.meta) out.push_back(0x1b);
      out.push_back(m.ctrl ? uint8_t(0x08) : uint8_t(0x7F));
      return out;
    case Key::kEscape:
      out.push_back(0x1b);  // modifiers are not encodable for Escape
      return out;
    case Key::kSpace:
      if (m.alt || m.meta) out.push_back(0x1b);
      out.push_back(m.ctrl ? uint8_t(0x00) : uint8_t(' '));
      return out;
    default:
      break;
  }

  // ── 6. Cursor keys (Up/Down/Right/Left/Home/End) ───────────────────────
  if (const LetterKey* lk = findLetter(k, kLetterKeys, arrayLen(kLetterKeys))) {
    if (anyMod) {
      addCsi(out);
      addStr(out, "1;");
      addNum(out, int(mp));
      addCh(out, lk->letter);
    } else if (modes.applicationCursorKeys) {
      addSs3(out);
      addCh(out, lk->letter);
    } else {
      addCsi(out);
      addCh(out, lk->letter);
    }
    return out;
  }

  // ── 7. Editing keys (Ins/Del/PgUp/PgDn) — DECCKM never applies ─────────
  if (const TildeKey* tk = findTilde(k, kTildeKeys, arrayLen(kTildeKeys))) {
    addCsi(out);
    addNum(out, tk->code);
    if (anyMod) {
      addCh(out, ';');
      addNum(out, int(mp));
    }
    addCh(out, '~');
    return out;
  }

  // ── 8. F1..F4: SS3 finals, parameterized when modified ─────────────────
  if (const LetterKey* fk = findLetter(k, kF1F4Keys, arrayLen(kF1F4Keys))) {
    if (anyMod) {
      addCsi(out);
      addStr(out, "1;");
      addNum(out, int(mp));
      addCh(out, fk->letter);
    } else {
      addSs3(out);
      addCh(out, fk->letter);
    }
    return out;
  }

  // ── 9. F5..F12: tilde codes, parameterized when modified ───────────────
  if (const TildeKey* fk = findTilde(k, kF5F12Keys, arrayLen(kF5F12Keys))) {
    addCsi(out);
    addNum(out, fk->code);
    if (anyMod) {
      addCh(out, ';');
      addNum(out, int(mp));
    }
    addCh(out, '~');
    return out;
  }

  return out;  // unknown key: not encodable
}

std::vector<uint8_t> encodeFocus(bool focused) {
  Bytes out;
  addCsi(out);
  addCh(out, focused ? 'I' : 'O');
  return out;
}

size_t utf8ValidPrefix(const char* data, size_t len) {
  if (data == nullptr || len == 0) return 0;
  size_t i = 0;
  while (i < len) {
    const uint8_t b = uint8_t(data[i]);
    if (b < 0x80) {  // ASCII
      ++i;
      continue;
    }
    // Lead byte classification: [need] continuation bytes follow; the first
    // continuation is constrained to [lo, hi] to reject overlong forms,
    // surrogates (ED A0..) and > U+10FFFF (F4 90..).
    size_t need = 0;
    uint8_t lo = 0x80;
    uint8_t hi = 0xBF;
    if (b >= 0xC2 && b <= 0xDF) {
      need = 1;                        // U+0080 .. U+07FF
    } else if (b == 0xE0) {
      need = 2; lo = 0xA0;             // U+0800 .. (no overlong)
    } else if (b >= 0xE1 && b <= 0xEC) {
      need = 2;                        // U+1000 .. U+CFFF
    } else if (b == 0xED) {
      need = 2; hi = 0x9F;             // U+D000 .. (no surrogates)
    } else if (b >= 0xEE && b <= 0xEF) {
      need = 2;                        // U+E000 .. U+FFFF
    } else if (b == 0xF0) {
      need = 3; lo = 0x90;             // U+10000 .. (no overlong)
    } else if (b >= 0xF1 && b <= 0xF3) {
      need = 3;                        // U+40000 .. U+FFFFF
    } else if (b == 0xF4) {
      need = 3; hi = 0x8F;             // U+100000 .. U+10FFFF
    } else {
      return i;  // 0x80..0xC1, 0xF5..0xFF: stray / always-invalid lead
    }
    if (i + 1 + need > len) return i;  // truncated at end of input
    const uint8_t c1 = uint8_t(data[i + 1]);
    if (c1 < lo || c1 > hi) return i;
    for (size_t j = 2; j <= need; ++j) {
      const uint8_t c = uint8_t(data[i + j]);
      if (c < 0x80 || c > 0xBF) return i;
    }
    i += 1 + need;
  }
  return i;  // whole input is valid
}

std::vector<uint8_t> encodePasteUtf8(const char* data, size_t len, bool bracketed) {
  Bytes out;
  if (data == nullptr) len = 0;
  // 1) C0 / DEL / ESC filter — anti injection. Keep \r \n \t: valid UTF-8
  //    never contains a byte < 0x20 or 0x7F, so this pass cannot damage
  //    multi-byte sequences.
  for (size_t i = 0; i < len; ++i) {
    const uint8_t b = uint8_t(data[i]);
    if (b == 0x09 || b == 0x0A || b == 0x0D || (b >= 0x20 && b != 0x7F)) {
      out.push_back(b);
    }
  }
  // 2) UTF-8 validity truncation (drops truncated / invalid tails).
  out.resize(utf8ValidPrefix(reinterpret_cast<const char*>(out.data()), out.size()));
  // 3) Bracketed-paste wrap. Even an empty payload keeps the markers: the
  //    application sees an explicit (empty) paste rather than nothing.
  if (bracketed) {
    const uint8_t kOpen[] = {0x1b, '[', '2', '0', '0', '~'};
    const uint8_t kClose[] = {0x1b, '[', '2', '0', '1', '~'};
    out.insert(out.begin(), kOpen, kOpen + sizeof(kOpen));
    out.insert(out.end(), kClose, kClose + sizeof(kClose));
  }
  return out;
}

}  // namespace apex::vt::input
