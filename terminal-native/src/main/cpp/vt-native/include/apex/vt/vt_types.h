// apex-vt-types — public shared enums / structs (render flags, mutations,
// colors, cursor shape). Internal cell storage lives in src/vt_types.h.
#pragma once

#include <cstdint>

namespace apex::vt {

// ─── Color ───────────────────────────────────────────────────────────────
enum ColorKind : uint8_t {
  kColorDefault = 0,  // theme default
  kColorIndexed = 1,  // 16/256-color palette
  kColorRgb = 2,      // 24-bit truecolor
};

struct Color {
  uint8_t kind = kColorDefault;
  uint8_t index = 0;  // kColorIndexed: 0..255
  uint8_t r = 0, g = 0, b = 0;  // kColorRgb

  bool operator==(const Color& o) const {
    return kind == o.kind && index == o.index && r == o.r && g == o.g && b == o.b;
  }
  bool operator!=(const Color& o) const { return !(*this == o); }
};

// ─── Style attr bitfield (Style::attr, 16-bit) ───────────────────────────
enum StyleAttr : uint16_t {
  kAttrBold = 1u << 0,
  kAttrDim = 1u << 1,
  kAttrItalic = 1u << 2,
  kAttrBlink = 1u << 3,
  kAttrInverse = 1u << 4,
  kAttrHidden = 1u << 5,
  kAttrStrike = 1u << 6,
};

// Underline style — packed into Style::attr bits 7..9 (3 bits).
enum Underline : uint16_t {
  kUlNone = 0,
  kUlSingle = 1,
  kUlDouble = 2,
  kUlCurly = 3,
  kUlDotted = 4,
  kUlDashed = 5,
};
constexpr uint16_t kUlShift = 7;
constexpr uint16_t ulOf(uint16_t attr) { return (attr >> kUlShift) & 7u; }

struct Style {
  Color fg;
  Color bg;
  uint16_t attr = 0;  // StyleAttr bits | (Underline << kUlShift)

  bool operator==(const Style& o) const {
    return fg == o.fg && bg == o.bg && attr == o.attr;
  }
  bool operator!=(const Style& o) const { return !(*this == o); }
};
constexpr Style kDefaultStyle{};

// ─── Render projection flags — parity with Kotlin RenderCell.FLAG_* ──────
enum RenderFlag : uint16_t {
  kRfBold = 1,
  kRfDim = 1u << 1,
  kRfItalic = 1u << 2,
  kRfUnderline = 1u << 3,
  kRfBlink = 1u << 4,
  kRfHidden = 1u << 5,
  kRfStrike = 1u << 6,
  kRfInverse = 1u << 7,
  kRfWide = 1u << 8,
};

// ─── Screen mutation — parity with Kotlin ScreenMutation (8 types) ───────
enum class MutationType : uint8_t {
  kCells = 0,
  kScrollUp = 1,
  kScrollDown = 2,
  kErase = 3,
  kInsertLines = 4,
  kDeleteLines = 5,
  kResize = 6,
  kFull = 7,
};

struct Mutation {
  MutationType type;
  int32_t firstRow;
  int32_t lastRow;
};

// ─── Cursor shape (DECSCUSR) — parity with Kotlin CursorStyle ────────────
enum class CursorShape : uint8_t {
  kBlock = 0,
  kUnderline = 1,
  kBar = 2,
};

// ─── Standard 16-color palette (parity with Kotlin TerminalColor.BASIC_16)
struct RgbTriplet {
  uint8_t r, g, b;
};
constexpr RgbTriplet kBasic16[16] = {
    {0x00, 0x00, 0x00}, {0x80, 0x00, 0x00}, {0x00, 0x80, 0x00}, {0x80, 0x80, 0x00},
    {0x00, 0x00, 0x80}, {0x80, 0x00, 0x80}, {0x00, 0x80, 0x80}, {0xC0, 0xC0, 0xC0},
    {0x80, 0x80, 0x80}, {0xFF, 0x00, 0x00}, {0x00, 0xFF, 0x00}, {0xFF, 0xFF, 0x00},
    {0x00, 0x00, 0xFF}, {0xFF, 0x00, 0xFF}, {0x00, 0xFF, 0xFF}, {0xFF, 0xFF, 0xFF},
};

// Resolve a Color to 0xRRGGBB; kColorDefault → 0 (theme decides).
inline uint32_t colorRgb(const Color& c) {
  switch (c.kind) {
    case kColorIndexed:
      if (c.index < 16) {
        const RgbTriplet& t = kBasic16[c.index];
        return (uint32_t(t.r) << 16) | (uint32_t(t.g) << 8) | t.b;
      }
      if (c.index < 232) {
        uint32_t i = c.index - 16;
        uint32_t r = (i / 36) % 6 * 51;
        uint32_t g = (i / 6) % 6 * 51;
        uint32_t b = i % 6 * 51;
        return (r << 16) | (g << 8) | b;
      }
      {  // grayscale ramp 232..255
        uint32_t v = (uint32_t(c.index) - 232) * 10 + 8;
        return (v << 16) | (v << 8) | v;
      }
    case kColorRgb:
      return (uint32_t(c.r) << 16) | (uint32_t(c.g) << 8) | c.b;
    default:
      return 0;
  }
}

// ARGB for the RenderCell projection: default → 0, else 0xFF000000 | rgb.
inline uint32_t colorArgb(const Color& c) {
  uint32_t rgb = colorRgb(c);
  return rgb == 0 ? 0 : (0xFF000000u | rgb);
}

}  // namespace apex::vt
