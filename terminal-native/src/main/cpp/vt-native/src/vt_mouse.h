// apex-vt-mouse — mouse-event encoding for the PTY input path.
//
// Contract: serialize a MouseEvent according to the tracking mode the
// application negotiated (X10 / normal / button / any-event) and the wire
// encoding it requested (X11 bytes / UTF-8 / SGR / urxvt extended). Gating
// happens first: an event the mode does not report yields an empty vector —
// the caller just drops it. Coordinates are 1-based screen cells.
// No exceptions, no I/O, no global state; see vt_input.h for the input
// half of this path.
#pragma once

#include "vt_input.h"

#include <cstdint>
#include <vector>

namespace apex::vt::mouse {

// Tracking modes — DECSET 9 / 1000 / 1002 / 1003.
enum class Mode : uint8_t {
  kOff = 0,    // tracking disabled (default)
  kX10,        // mode 9:  press-only, no modifiers, no wheel, X11-style bytes
  kNormal,     // mode 1000: press + release (+ wheel)
  kButton,     // mode 1002: normal + motion while a button is held
  kAny,        // mode 1003: button + motion with no button held
};

// Wire encodings — DECSET 1005 (UTF-8) / 1006 (SGR) / 1015 (urxvt).
enum class Encoding : uint8_t {
  kX11 = 0,    // default: ESC [ M Cb Cx Cy (raw bytes, coords < 224 only)
  kUtf8,       // mode 1005: same values as X11, serialized as UTF-8 code points
  kSgr,        // mode 1006: ESC [ < Cb ; Cx ; Cy (M|m) — 1-based decimal
  kUrxvt,      // mode 1015: ESC [ Cb ; Cx ; Cy M — 1-based decimal
};

enum class Type : uint8_t {
  kPress, kRelease, kMotion,
  kWheelUp, kWheelDown, kWheelLeft, kWheelRight,
};

struct MouseEvent {
  Type type = Type::kPress;
  int button = 0;            // 0/1/2 = primary/middle/secondary; motion with
                             // no button held uses -1
  input::Modifiers mods;     // shift/meta/ctrl participate; alt is not
                             // reportable in mouse protocols
  int col = 1, row = 1;      // 1-based screen coordinates
};

// Gate by mode, then serialize with [enc]. Empty result = this mode does not
// report the event (or it is not representable in the encoding, e.g. X11
// coordinates beyond 223).
std::vector<uint8_t> encode(const MouseEvent& e, Mode m, Encoding enc);

}  // namespace apex::vt::mouse
