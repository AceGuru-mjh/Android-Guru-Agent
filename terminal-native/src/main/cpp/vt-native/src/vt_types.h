// apex-vt-internal-types — flat POD cell model (internal storage detail).
#pragma once

#include "apex/vt/vt_types.h"

namespace apex::vt {

// ─── Cell flags (Cell::flags) ────────────────────────────────────────────
enum CellFlag : uint16_t {
  kCellWideLead = 1u << 0,   // lead cell of a width-2 char (carries the code point)
  kCellWideTrail = 1u << 1,  // continuation cell of a wide char (no independent char)
  kCellProtected = 1u << 2,  // DECSCA: erased only by ED/EL (never DECSED/DECSEL)
};

// ─── Cell — 8 bytes, whole-screen flat array element ────────────────────
struct Cell {
  uint32_t cp;      // base code point; ' ' = blank, 0 = unset (wide trail)
  uint16_t style;   // interned style id (StyleTable)
  uint16_t flags;   // CellFlag bitmask
};
static_assert(sizeof(Cell) == 8, "Cell must stay 8 bytes");

}  // namespace apex::vt
