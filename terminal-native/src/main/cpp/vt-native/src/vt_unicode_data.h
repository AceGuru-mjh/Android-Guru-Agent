// apex-vt-unicode-data — Unicode 15.1 property tables (EAW + UAX #29).
//
// Full-data upgrade of the simplified tables in vt_width.h: complete East
// Asian Width, the Grapheme_Cluster_Break property classes of UAX #29, and
// the emoji properties needed for emoji presentation / ZWJ-sequence handling.
// This is the foundation for cursor motion, selection, copy and correct
// emoji / ZWJ / combining rendering on a mobile terminal.
//
// Contract:
//  * zero allocation, zero exceptions — all lookups are binary searches over
//    constexpr sorted range tables (hot-path safe, same style as vt_width.cpp);
//  * tables are static (Unicode 15.1); a data refresh is a drop-in edit;
//  * displayWidth(): 2 = EAW Wide/Fullwidth, 1 = Na/H/N/A (ambiguous defaults
//    to 1), 0 = combining marks (Mn/Me), format chars (Cf), ZWJ, variation
//    selectors, emoji modifiers and C0/C1/DEL controls.
#pragma once

#include <cstdint>

namespace apex::vt::uni {

// Data vintage of every table below.
const char* unicodeVersion();

// ─── East Asian Width (UAX #11, complete 15.1 W/F set) ───────────────────
// 2 = Wide/Fullwidth, 1 = Narrow/Halfwidth/Neutral/Ambiguous,
// 0 = Mn/Me/Cf (combining + format), ZWJ, variation selectors, emoji
// modifiers, and C0/C1/DEL control characters.
// Note: Regional indicators (U+1F1E6..1F1FF) are EAW Neutral → 1 (xterm
// parity: flags render from a single lead column).
int displayWidth(uint32_t cp);

// ─── UAX #29 Grapheme_Cluster_Break classes ──────────────────────────────
// Single primary class per code point; where Extend and other properties
// overlap (e.g. emoji modifiers are Extend + Extended_Pictographic) Extend
// wins and the pictographic flag stays queryable via isExtendedPictographic().
enum class Gc : uint8_t {
  kOther = 0,              // GB999 default (incl. ExtPict-only code points
                           // → kExtendedPictographic below)
  kControl,                // Cc (minus CR/LF) + Control-class Cf/Zl/Zp
  kCr,                     // U+000D
  kLf,                     // U+000A
  kL, kV, kT, kLv, kLvt,   // Hangul jamo / syllable classes (arithmetic)
  kExtend,                 // Mn, Me + Extend-class Mc/Cf/Sk (VS, ZWNJ, tags,
                           // skin tones, halfwidth katakana voiced marks)
  kZwj,                    // U+200D
  kRegionalIndicator,      // U+1F1E6..1F1FF
  kPrepend,                // prepended concatenation marks
  kSpacingMark,            // spacing combining marks (Mc)
  kExtendedPictographic,   // ExtPict property and no stronger class
};

Gc graphemeClass(uint32_t cp);

// Emoji_Presentation=Yes (emoji-style by default — U+2764, U+231A, most
// U+1F300+). False for text-presentation symbols (U+2665, U+00A9).
bool isEmojiPresentationDefault(uint32_t cp);

// Extended_Pictographic (GB11 emoji ZWJ-sequence chains). Includes the
// unassigned ranges of the emoji planes per emoji-data.txt convention.
bool isExtendedPictographic(uint32_t cp);

// Emoji_Component (valid building block of an emoji ZWJ sequence): keycap
// bases, ZWJ, U+20E3, skin-tone modifiers, regional indicators, tag chars
// and all default-emoji-presentation pictographs.
bool isEmojiComponent(uint32_t cp);

// U+1F1E6..1F1FF.
bool isRegionalIndicator(uint32_t cp);

// CJK ideographs for double-click word selection: radicals + CJK symbols
// (U+2E80..U+303E), strokes, UAX #29 ideographic blocks, compat blocks and
// planes 2/3 ideograph areas. Kana/Hangul are intentionally excluded.
bool isIdeographic(uint32_t cp);

// Word-joiner style invisibles: U+2060 WORD JOINER, U+FEFF (ZWNBSP).
bool isWordJoiner(uint32_t cp);

}  // namespace apex::vt::uni
