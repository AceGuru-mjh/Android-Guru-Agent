#include "vt_width.h"

#include <cstddef>

namespace apex::vt {
namespace {

struct Range {
  uint32_t lo, hi;
};

// Sorted — binary-searched.
constexpr Range kZeroWidth[] = {
    {0x00300, 0x0036F},  // combining diacritical
    {0x01AB0, 0x01AFF},  // combining diacritical extended
    {0x01DC0, 0x01DFF},  // combining diacritical supplemental
    {0x0200D, 0x0200D},  // ZWJ
    {0x020D0, 0x020FF},  // combining symbols
    {0x0FE00, 0x0FE0F},  // variation selectors VS1..VS16
    {0x0FE20, 0x0FE2F},  // combining half marks
    {0x1F3FB, 0x1F3FF},  // emoji skin-tone modifiers
    {0xE0100, 0xE01EF},  // supplementary variation selectors
};

// Sorted — binary-searched.
constexpr Range kWideWidth[] = {
    {0x01100, 0x0115F},  // Hangul Jamo
    {0x02E80, 0x0303E},  // CJK radicals
    {0x03041, 0x033FF},  // Hiragana / Katakana / CJK symbols
    {0x03400, 0x04DBF},  // CJK Ext A
    {0x04E00, 0x09FFF},  // CJK Unified
    {0x0A000, 0x0A4CF},  // Yi
    {0x0AC00, 0x0D7A3},  // Hangul Syllables
    {0x0F900, 0x0FAFF},  // CJK Compatibility
    {0x0FE30, 0x0FE4F},  // CJK Compatibility Forms
    {0x0FF00, 0x0FF60},  // Fullwidth Forms
    {0x0FFE0, 0x0FFE6},  // Fullwidth Signs
    {0x1F300, 0x1FAFF},  // Emoji + symbols (wide)
    {0x20000, 0x3FFFD},  // CJK Ext B-F
};

bool inRanges(const Range* ranges, size_t n, uint32_t cp) {
  size_t lo = 0, hi = n;
  while (lo < hi) {
    size_t mid = lo + (hi - lo) / 2;
    if (cp < ranges[mid].lo) {
      hi = mid;
    } else if (cp > ranges[mid].hi) {
      lo = mid + 1;
    } else {
      return true;
    }
  }
  return false;
}

// DEC Special Graphics — index = ASCII code, value = replacement code point.
constexpr uint32_t kDecGraphicsFrom[] = {
    0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C,
    0x6D, 0x6E, 0x6F, 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79,
    0x7A, 0x7B, 0x7C, 0x7D, 0x7E,
};
constexpr uint32_t kDecGraphicsTo[] = {
    0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1, 0x2424, 0x240B,
    0x2518, 0x2510, 0x250C, 0x2514, 0x253C, 0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD,
    0x251C, 0x2524, 0x2534, 0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3,
    0x00B7,
};

}  // namespace

int unicodeWidth(uint32_t cp) {
  if (cp < 0x20) return 0;          // C0
  if (cp >= 0x7F && cp <= 0x9F) return 0;  // DEL + C1
  if (cp < 0x300) return 1;         // ASCII + Latin-1
  if (inRanges(kZeroWidth, sizeof(kZeroWidth) / sizeof(kZeroWidth[0]), cp)) return 0;
  if (inRanges(kWideWidth, sizeof(kWideWidth) / sizeof(kWideWidth[0]), cp)) return 2;
  return 1;
}

uint32_t decSpecialGraphics(uint32_t cp) {
  constexpr size_t kN = sizeof(kDecGraphicsFrom) / sizeof(kDecGraphicsFrom[0]);
  for (size_t i = 0; i < kN; ++i) {
    if (kDecGraphicsFrom[i] == cp) return kDecGraphicsTo[i];
  }
  return 0;
}

}  // namespace apex::vt
