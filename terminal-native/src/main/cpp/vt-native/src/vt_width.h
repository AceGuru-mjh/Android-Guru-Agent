// apex-vt-width — Unicode display width + DEC Special Graphics.
//
// Exact port of the Kotlin UnicodeWidth / DEC_SPECIAL_GRAPHICS tables used by
// TerminalCore 2.0 (simplified East Asian Width; common cases: CJK, emoji,
// combining, variation selectors).
#pragma once

#include <cstdint>

namespace apex::vt {

// Display width: 0 (combining/ZWJ/VS/control), 1 (normal), 2 (CJK/emoji).
int unicodeWidth(uint32_t cp);

// Combining char (width 0 and >= U+0300)?
inline bool isCombining(uint32_t cp) { return cp >= 0x300 && unicodeWidth(cp) == 0; }

// DEC Special Graphics glyph substitution; returns 0 when unmapped.
uint32_t decSpecialGraphics(uint32_t cp);

}  // namespace apex::vt
