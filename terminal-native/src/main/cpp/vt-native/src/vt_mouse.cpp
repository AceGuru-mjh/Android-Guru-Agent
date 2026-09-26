// apex-vt-mouse — mouse-event encoder implementation (see vt_mouse.h).
//
// Wire contracts (byte-exact xterm):
//   X11   `ESC [ M` Cb Cx Cy — three raw bytes. Cb = 32 + (btnBits | mods),
//         +32 extra for motion; release uses button bits 3; wheel uses
//         button bits 64+idx (up=0, down=1, left=2, right=3 — X11 buttons
//         4..7 mapped the way xterm does, so wheel-up is Cb = 0x60). Cx/Cy =
//         32 + (col-1); col/row above 223 (byte value > 254) are not
//         representable → empty result (bounded behavior, documented).
//   UTF-8 mode 1005: same Cb/Cx/Cy VALUES as X11, serialized as UTF-8 code
//         points — covers coordinates up to 2015; beyond that → empty.
//   SGR   mode 1006: `ESC [ <` Cb `;` col `;` row ('M' press/motion/wheel,
//         'm' release). Cb = btnBits | shift*4 | meta*8 | ctrl*16
//         (| 32 for motion, 64+idx for wheel). Release carries the REAL
//         button. Motion with no button uses button bits 3 → Cb 35.
//   urxvt mode 1015: `ESC [` Cb `;` col `;` row 'M' — Cb same value as X11,
//         coordinates 1-based decimal.
// Modifier bits are shift=4, meta=8, ctrl=16 (alt is not reportable — the
// protocols simply have no bit for it). X10 mode (DECSET 9) reports presses
// only, strips modifiers and never reports wheel or release.

#include "vt_mouse.h"

namespace apex::vt::mouse {
namespace {

using Bytes = std::vector<uint8_t>;

constexpr int kMaxX11Coord = 254;   // highest encodable X11 coordinate byte (32+222)
constexpr int kMaxUtf8Coord = 2015;  // UTF-8 mode coordinate limit (32+1983)

void addCsi(Bytes& b) { b.push_back(0x1b); b.push_back('['); }

void addCh(Bytes& b, char c) { b.push_back(uint8_t(c)); }

void addNum(Bytes& b, int v) {
  char tmp[10];
  int n = 0;
  do {
    tmp[n++] = char('0' + (v % 10));
    v /= 10;
  } while (v > 0);
  while (n > 0) b.push_back(uint8_t(tmp[--n]));
}

// UTF-8 serialization of a code point (mouse values stay < 0x800).
void addUtf8(Bytes& b, int cp) {
  if (cp < 0x80) {
    b.push_back(uint8_t(cp));
  } else if (cp < 0x800) {
    b.push_back(uint8_t(0xC0 | (cp >> 6)));
    b.push_back(uint8_t(0x80 | (cp & 0x3F)));
  } else {
    b.push_back(uint8_t(0xE0 | (cp >> 12)));
    b.push_back(uint8_t(0x80 | ((cp >> 6) & 0x3F)));
    b.push_back(uint8_t(0x80 | (cp & 0x3F)));
  }
}

// Wheel index: up=0 down=1 left=2 right=3.
int wheelIndex(Type t) {
  switch (t) {
    case Type::kWheelUp: return 0;
    case Type::kWheelDown: return 1;
    case Type::kWheelLeft: return 2;
    case Type::kWheelRight: return 3;
    default: return -1;
  }
}

bool isWheel(Type t) { return wheelIndex(t) >= 0; }

// Button-field sanity for the event type. Press/release: 0..2; motion: -1..2
// (-1 = no button held); wheel: any (field ignored).
bool validButtons(const MouseEvent& e) {
  switch (e.type) {
    case Type::kPress:
    case Type::kRelease:
      return e.button >= 0 && e.button <= 2;
    case Type::kMotion:
      return e.button >= -1 && e.button <= 2;
    default:
      return true;  // wheel
  }
}

// Mode gating matrix:
//   kX10:    press only (no release / motion / wheel; modifiers stripped).
//   kNormal: everything except motion.
//   kButton: kNormal + motion with a held button (button >= 0).
//   kAny:    kButton + motion with no button (button < 0).
bool reported(const MouseEvent& e, Mode m) {
  if (!validButtons(e)) return false;
  switch (m) {
    case Mode::kOff:
      return false;
    case Mode::kX10:
      return e.type == Type::kPress;
    case Mode::kNormal:
      return e.type != Type::kMotion;
    case Mode::kButton:
      return e.type != Type::kMotion || e.button >= 0;
    case Mode::kAny:
      return true;
  }
  return false;
}

// Mouse modifier bits: shift=4, meta=8, ctrl=16 (no alt bit exists).
int modBits(const input::Modifiers& m) {
  return (m.shift ? 4 : 0) | (m.meta ? 8 : 0) | (m.ctrl ? 16 : 0);
}

}  // namespace

std::vector<uint8_t> encode(const MouseEvent& e, Mode m, Encoding enc) {
  Bytes out;
  if (!reported(e, m)) return out;

  const bool motion = e.type == Type::kMotion;
  const bool release = e.type == Type::kRelease;
  const bool x10 = (m == Mode::kX10);  // X10 strips modifiers entirely
  const int mods = x10 ? 0 : modBits(e.mods);

  // Button field shared by all encodings (bits 0..1 = button, 64+ = wheel).
  int buttonBits;
  if (isWheel(e.type)) {
    buttonBits = 64 + wheelIndex(e.type);
  } else if (motion) {
    buttonBits = (e.button < 0) ? 3 : e.button;  // 3 = "no button" (legacy)
  } else if (release && enc == Encoding::kSgr) {
    buttonBits = e.button;  // SGR release keeps the real button
  } else if (release) {
    buttonBits = 3;         // legacy release is always button 3
  } else {
    buttonBits = e.button;  // press
  }

  // Legacy encodings add the 32 base offset (+32 more for motion) on top of
  // the raw bits; SGR uses the raw bits with the motion flag OR'd in.
  const int cbLegacy = 32 + buttonBits + mods + (motion ? 32 : 0);
  const int cbSgr = buttonBits | mods | (motion ? 32 : 0);

  // 1-based coordinates, clamped at the top-left corner.
  const int col = e.col < 1 ? 1 : e.col;
  const int row = e.row < 1 ? 1 : e.row;
  const int cx = 32 + (col - 1);
  const int cy = 32 + (row - 1);

  switch (enc) {
    case Encoding::kX11: {
      // col/row above 223 cannot be represented (byte 255 is reserved by
      // the protocol) — bounded behavior: drop the event.
      if (cx > kMaxX11Coord || cy > kMaxX11Coord) return out;
      addCsi(out);
      addCh(out, 'M');
      out.push_back(uint8_t(cbLegacy));
      out.push_back(uint8_t(cx));
      out.push_back(uint8_t(cy));
      return out;
    }
    case Encoding::kUtf8: {
      if (cx > kMaxUtf8Coord || cy > kMaxUtf8Coord) return out;
      addCsi(out);
      addCh(out, 'M');
      addUtf8(out, cbLegacy);
      addUtf8(out, cx);
      addUtf8(out, cy);
      return out;
    }
    case Encoding::kSgr: {
      addCsi(out);
      addCh(out, '<');
      addNum(out, cbSgr);
      addCh(out, ';');
      addNum(out, col);
      addCh(out, ';');
      addNum(out, row);
      addCh(out, release ? 'm' : 'M');
      return out;
    }
    case Encoding::kUrxvt: {
      addCsi(out);
      addNum(out, cbLegacy);
      addCh(out, ';');
      addNum(out, col);
      addCh(out, ';');
      addNum(out, row);
      addCh(out, 'M');
      return out;
    }
  }
  return out;
}

}  // namespace apex::vt::mouse
