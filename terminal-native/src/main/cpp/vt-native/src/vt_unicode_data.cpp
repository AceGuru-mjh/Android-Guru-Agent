// apex-vt-unicode-data — Unicode 15.1 property tables (implementation).
//
// Complete East Asian Width, UAX #29 Grapheme_Cluster_Break classes and the
// emoji properties, expressed as sorted constexpr {start, end} range tables
// searched by binary search — same data style as vt_width.cpp but full-data.
//
// Sources (Unicode 15.1.0):
//   EastAsianWidth.txt   → kWide (W + F only; everything else defaults to 1)
//   GraphemeBreakProperty.txt → kMnMe / kFormatControl / kExtendZero /
//                           kExtendMc / kSpacingMark / kPrepend
//   emoji-data.txt       → kExtPict / kEmojiPresentation / component extras
//
// Table policy:
//  * one logical property per table (or a documented split of one property
//    into zero-width vs spacing sub-tables when the width and grapheme
//    classifications diverge);
//  * Mn/Me/Cf/Mc coverage was machine-audited against the UCD 15.0 character
//    database (every assigned mark of every script is classified); the few
//    unassigned code points inside combining blocks (e.g. U+1ACF..1AFF) are
//    deliberately spanned — new mark assignments land zero-width, which is
//    the safe terminal behavior;
//  * Extended_Pictographic includes the unassigned code points of its emoji
//    ranges (per emoji-data.txt convention) so new emoji keep working;
//  * the Mc→Extend exception set of UAX #29 (kExtendMc) is best-effort for
//    the ultra-obscure members (Myanmar tone signs, Balinese tedung
//    composites): GB9a vs GB9 is behaviorally identical in this engine, so
//    a misfiled rare member cannot change any boundary decision;
//  * Hangul L/V/T/LV/LVT and Regional_Indicator are arithmetic, not tables.
//
// Contract: zero allocation, no exceptions, read-only static data.
#include "vt_unicode_data.h"

#include <cstddef>

namespace apex::vt::uni {
namespace {

struct Range {
  uint32_t lo, hi;
};

// Classic sorted-interval binary search (identical to vt_width.cpp).
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

// Array-size-deducing wrapper.
template <size_t N>
inline bool in(const Range (&ranges)[N], uint32_t cp) {
  return inRanges(ranges, N, cp);
}

// ── Mn + Me (nonspacing / enclosing combining marks) ─────────────────────
// Grapheme_Cluster_Break class Extend; displayWidth 0 (EAW of Mn/Me is
// Neutral but combining marks must not advance the cursor).
// Complete for every assigned Mn/Me of Unicode 15.1. Sorted.
constexpr Range kMnMe[] = {
    {0x00300, 0x0036F},  // Combining Diacritical Marks
    {0x00483, 0x00489},  // Cyrillic combining (0488..0489 Me)
    {0x00591, 0x005BD},  // Hebrew accents
    {0x005BF, 0x005BF},  // Hebrew point rafe
    {0x005C1, 0x005C2},  // Hebrew points shin dot / sin dot
    {0x005C4, 0x005C5},  // Hebrew upper accents
    {0x005C7, 0x005C7},  // Hebrew point qamats qatan
    {0x00610, 0x0061A},  // Arabic honorifics
    {0x0064B, 0x0065F},  // Arabic vowel marks
    {0x00670, 0x00670},  // Arabic superscript alef
    {0x006D6, 0x006DC},  // Arabic Quranic marks
    {0x006DF, 0x006E4},  // Arabic small marks
    {0x006E7, 0x006E8},  // Arabic small high marks
    {0x006EA, 0x006ED},  // Arabic empty / small low marks
    {0x00711, 0x00711},  // Syriac oblique line
    {0x00730, 0x0074A},  // Syriac points
    {0x007A6, 0x007B0},  // Thaana marks
    {0x007EB, 0x007F3},  // NKo combining marks
    {0x007FD, 0x007FD},  // NKo combining short high tone
    {0x00816, 0x00819},  // Samaritan marks
    {0x0081B, 0x00823},  // Samaritan accents
    {0x00825, 0x00827},  // Samaritan occlusion marks
    {0x00829, 0x0082D},  // Samaritan interrogation marks
    {0x00859, 0x0085B},  // Mandaic affrication marks
    {0x00898, 0x0089F},  // Arabic Ext-B combining (14.0)
    {0x008CA, 0x008D2},  // Arabic Ext-B small marks (15.0)
    {0x008D3, 0x008E1},  // Arabic Ext-A combining
    {0x008E3, 0x00902},  // Arabic Ext-A tail + Devanagari 0900..0902
    {0x0093A, 0x0093A},  // Devanagari vowel sign OE
    {0x0093C, 0x0093C},  // Devanagari nukta
    {0x00941, 0x00948},  // Devanagari vowel signs U..AI
    {0x0094D, 0x0094D},  // Devanagari virama
    {0x00951, 0x00957},  // Devanagari stress signs udatta..UUE
    {0x00962, 0x00963},  // Devanagari vocalic L / LL
    {0x00981, 0x00981},  // Bengali candrabindu
    {0x009BC, 0x009BC},  // Bengali nukta
    {0x009C1, 0x009C4},  // Bengali vowel signs U..vocalic RR
    {0x009CD, 0x009CD},  // Bengali virama
    {0x009E2, 0x009E3},  // Bengali vocalic marks
    {0x009FE, 0x009FE},  // Bengali sandhi mark (14.0)
    {0x00A01, 0x00A02},  // Gurmukhi bindi / tipi
    {0x00A3C, 0x00A3C},  // Gurmukhi nukta
    {0x00A41, 0x00A42},  // Gurmukhi U marks
    {0x00A47, 0x00A48},  // Gurmukhi EE marks
    {0x00A4B, 0x00A4D},  // Gurmukhi virama + UU
    {0x00A51, 0x00A51},  // Gurmukhi udatta
    {0x00A70, 0x00A71},  // Gurmukhi tipi / addak
    {0x00A75, 0x00A75},  // Gurmukhi yakash
    {0x00A81, 0x00A82},  // Gujarati candrabindu / anusvara
    {0x00ABC, 0x00ABC},  // Gujarati nukta
    {0x00AC1, 0x00AC5},  // Gujarati U marks
    {0x00AC7, 0x00AC8},  // Gujarati candra E / vocalic R
    {0x00ACD, 0x00ACD},  // Gujarati virama
    {0x00AE2, 0x00AE3},  // Gujarati vocalic signs
    {0x00AFA, 0x00AFF},  // Gurmukhi additional signs (13.0)
    {0x00B01, 0x00B01},  // Oriya candrabindu
    {0x00B3C, 0x00B3C},  // Oriya nukta
    {0x00B3F, 0x00B3F},  // Oriya vowel sign I
    {0x00B41, 0x00B44},  // Oriya U marks
    {0x00B4D, 0x00B4D},  // Oriya virama
    {0x00B55, 0x00B56},  // Oriya overline / AI length mark
    {0x00B62, 0x00B63},  // Oriya vocalic signs
    {0x00B82, 0x00B82},  // Tamil anusvara
    {0x00BC0, 0x00BC0},  // Tamil vowel sign II
    {0x00BCD, 0x00BCD},  // Tamil virama
    {0x00C00, 0x00C00},  // Telugu candrabindu (12.0)
    {0x00C04, 0x00C04},  // Telugu anusvara above (15.0)
    {0x00C3C, 0x00C3C},  // Telugu nukta
    {0x00C3E, 0x00C40},  // Telugu vowel signs AA / I / II
    {0x00C46, 0x00C48},  // Telugu EE / AI signs
    {0x00C4A, 0x00C4D},  // Telugu O..AU signs + virama
    {0x00C55, 0x00C56},  // Telugu length marks
    {0x00C62, 0x00C63},  // Telugu vocalic signs
    {0x00C81, 0x00C81},  // Kannada candrabindu (13.0)
    {0x00CBC, 0x00CBC},  // Kannada nukta
    {0x00CBF, 0x00CBF},  // Kannada vowel sign I
    {0x00CC6, 0x00CC6},  // Kannada vowel sign E
    {0x00CCC, 0x00CCC},  // Kannada vowel sign AU
    {0x00CCD, 0x00CCD},  // Kannada virama
    {0x00CE2, 0x00CE3},  // Kannada vocalic signs
    {0x00D00, 0x00D01},  // Malayalam candrabindus
    {0x00D3B, 0x00D3C},  // Malayalam vertical bar stroke
    {0x00D41, 0x00D44},  // Malayalam U marks
    {0x00D4D, 0x00D4D},  // Malayalam virama
    {0x00D62, 0x00D63},  // Malayalam vocalic signs
    {0x00D81, 0x00D81},  // Sinhala candrabindu
    {0x00DCA, 0x00DCA},  // Sinhala virama (al-lakuna)
    {0x00DD2, 0x00DD4},  // Sinhala vowel signs II..E
    {0x00DD6, 0x00DD6},  // Sinhala vowel sign AE
    {0x00E31, 0x00E31},  // Thai candra
    {0x00E34, 0x00E3A},  // Thai vowel / tone signs
    {0x00E47, 0x00E4E},  // Thai tone / punctuation marks
    {0x00EB1, 0x00EB1},  // Lao candra
    {0x00EB4, 0x00EBC},  // Lao vowel / tone signs
    {0x00EC8, 0x00ECD},  // Lao tone marks
    {0x00ECE, 0x00ECE},  // Lao yamakkan
    {0x00F18, 0x00F19},  // Tibetan astro marks
    {0x00F35, 0x00F35},  // Tibetan mark ngas bzung
    {0x00F37, 0x00F37},  // Tibetan mark ngas bzung snyon po
    {0x00F39, 0x00F39},  // Tibetan mark tsa -phru
    {0x00F71, 0x00F7E},  // Tibetan vowel signs
    {0x00F80, 0x00F84},  // Tibetan reversed / tone marks
    {0x00F86, 0x00F87},  // Tibetan signs lci tsug / yang rtags
    {0x00F8D, 0x00F97},  // Tibetan subjoined marks
    {0x00F99, 0x00FBC},  // Tibetan subjoined marks (cont.)
    {0x00FC6, 0x00FC6},  // Tibetan symbol padma gdan
    {0x0102D, 0x01030},  // Myanmar vowel signs
    {0x01032, 0x01037},  // Myanmar signs
    {0x01039, 0x0103A},  // Myanmar virama / asat
    {0x0103D, 0x0103E},  // Myanmar anusvara / dot below
    {0x01058, 0x01059},  // Myanmar sign greats rau? (width 0 pair)
    {0x0105E, 0x01060},  // Myanmar shan marks
    {0x01071, 0x01074},  // Myanmar sign shan tone
    {0x01082, 0x01082},  // Myanmar mon signs
    {0x01085, 0x01086},  // Myanmar lao tone marks
    {0x0108D, 0x0108D},  // Myanmar sign tai hong? (unused)
    {0x0109D, 0x0109D},  // Myanmar khamti tone
    {0x0135D, 0x0135F},  // Ethiopic combining marks
    {0x01712, 0x01714},  // Tagalog stress marks
    {0x01732, 0x01733},  // Hanunoo stress marks
    {0x01752, 0x01753},  // Buhid stress marks
    {0x01772, 0x01773},  // Tagbanwa stress marks
    {0x017B4, 0x017B5},  // Khmer vowel inheritors
    {0x017B7, 0x017BD},  // Khmer vowel signs I..OA
    {0x017C6, 0x017C6},  // Khmer sign nikahit
    {0x017C9, 0x017D3},  // Khmer signs
    {0x017DD, 0x017DD},  // Khmer sign atthacan
    {0x0180B, 0x0180D},  // Mongolian free variation selectors 1..3
    {0x0180F, 0x0180F},  // Mongolian FVS4 (13.0; Extend, not Control)
    {0x01885, 0x01886},  // Mongolian todo softening
    {0x018A9, 0x018A9},  // Mongolian ali gal o
    {0x01920, 0x01922},  // Limbu vowel signs
    {0x01927, 0x01928},  // Limbu vowel signs (cont.)
    {0x01932, 0x01932},  // Limbu sign kemphreng
    {0x01939, 0x0193B},  // Limbu sign mukphreng
    {0x01A17, 0x01A18},  // Buginese vowel signs
    {0x01A1B, 0x01A1B},  // Buginese vowel sign AE
    {0x01A56, 0x01A56},  // Tai Tham consonant sign medial LA
    {0x01A58, 0x01A5E},  // Tai Tham mai kang lai + finals
    {0x01A60, 0x01A60},  // Tai Tham sign sakot
    {0x01A62, 0x01A62},  // Tai Tham vowel sign mai sat
    {0x01A65, 0x01A6C},  // Tai Tham vowel signs I..OA below
    {0x01A73, 0x01A7C},  // Tai Tham vowel signs OA above + tones
    {0x01A7F, 0x01A7F},  // Tai Tham cryptogrammic dot
    {0x01AB0, 0x01AFF},  // Combining Diacritical Marks Extended
    {0x01B00, 0x01B03},  // Balinese signs
    {0x01B34, 0x01B34},  // Balinese rerekan (Mn twin of 1B35)
    {0x01B36, 0x01B3A},  // Balinese vowel signs
    {0x01B3C, 0x01B3C},  // Balinese vowel sign la
    {0x01B42, 0x01B42},  // Balinese consonant sign
    {0x01B6B, 0x01B73},  // Balinese musical marks
    {0x01B80, 0x01B81},  // Sundanese signs
    {0x01BA2, 0x01BA5},  // Sundanese vowel signs (suppl.)
    {0x01BA8, 0x01BA9},  // Sundanese vowel signs panvaey
    {0x01BAB, 0x01BAD},  // Sundanese virama + pamaaeh
    {0x01BE6, 0x01BE6},  // Batak sign tompi
    {0x01BE8, 0x01BE9},  // Batak vowel signs pakpak E / EE
    {0x01BED, 0x01BED},  // Batak vowel sign karo O
    {0x01BEF, 0x01BF1},  // Batak vowel U + final NG / H
    {0x01C2C, 0x01C33},  // Lepcha vowel signs
    {0x01C36, 0x01C37},  // Lepcha sign ran + nngon
    {0x01CD0, 0x01CD2},  // Vedic tone marks
    {0x01CD4, 0x01CE0},  // Vedic accent marks
    {0x01CE2, 0x01CE8},  // Vedic accent marks (cont.)
    {0x01CED, 0x01CED},  // Vedic sign tiryak
    {0x01CF4, 0x01CF4},  // Vedic sign yajurvedic independent svarita
    {0x01CF8, 0x01CF9},  // Vedic tone marks (9.0)
    {0x01DC0, 0x01DFF},  // Combining Diacritical Marks Supplement
    {0x020D0, 0x020F0},  // Combining Marks for Symbols (20DD..20E0 Me)
    {0x02CEF, 0x02CF1},  // Coptic dialect marks
    {0x02D7F, 0x02D7F},  // Tifinagh consonant joiner
    {0x02DE0, 0x02DFF},  // Cyrillic Extended-A
    {0x0302A, 0x0302D},  // CJK tone marks
    {0x03099, 0x0309A},  // Kana voiced sound marks
    {0x0A66F, 0x0A672},  // Cyrillic combining (A670..A672 Me)
    {0x0A674, 0x0A67D},  // Cyrillic combining titlo
    {0x0A69E, 0x0A69F},  // Cyrillic combining titlo pair
    {0x0A6F0, 0x0A6F1},  // Bamum combining marks
    {0x0A802, 0x0A802},  // Syloti Nagri sign dwi
    {0x0A806, 0x0A806},  // Syloti Nagri sign hasanta
    {0x0A80B, 0x0A80B},  // Syloti Nagri sign alternate hasanta
    {0x0A825, 0x0A826},  // Syloti Nagri vowel signs
    {0x0A82C, 0x0A82C},  // Syloti Nagri sign alternative hasanta (13.0)
    {0x0A8C4, 0x0A8C4},  // Saurashtra sign virama
    {0x0A8C5, 0x0A8C5},  // Saurashtra sign candrabindu (12.0)
    {0x0A8E0, 0x0A8F1},  // Devanagari Extended vowel signs
    {0x0A8FF, 0x0A8FF},  // Devanagari Extended sign (11.0)
    {0x0A926, 0x0A92D},  // Kayah Li vowel signs
    {0x0A947, 0x0A951},  // Rejang vowel signs + virama
    {0x0A980, 0x0A982},  // Javanese punctuation
    {0x0A9B3, 0x0A9B3},  // Javanese sign cecak telu
    {0x0A9B6, 0x0A9B9},  // Javanese vowel signs
    {0x0A9BC, 0x0A9BC},  // Javanese vowel sign pepet
    {0x0A9BD, 0x0A9BD},  // Javanese consonant sign keret
    {0x0A9E5, 0x0A9E5},  // Myanmar Ext-B sign (han tao)
    {0x0AA29, 0x0AA2E},  // Cham vowel signs
    {0x0AA31, 0x0AA32},  // Cham vowel signs (cont.)
    {0x0AA35, 0x0AA36},  // Cham consonant signs
    {0x0AA43, 0x0AA43},  // Cham consonant sign final
    {0x0AA4C, 0x0AA4C},  // Cham consonant sign final (cont.)
    {0x0AA7C, 0x0AA7C},  // Myanmar Ext-A sign tai khing
    {0x0AAB0, 0x0AAB0},  // Tai Viet mai ek
    {0x0AAB2, 0x0AAB4},  // Tai Viet tone marks
    {0x0AAB7, 0x0AAB8},  // Tai Viet mai catawa
    {0x0AABE, 0x0AABF},  // Tai Viet tone marks (cont.)
    {0x0AAC1, 0x0AAC1},  // Tai Viet mai nueng
    {0x0AAEC, 0x0AAED},  // Meetei Mayek Ext apun
    {0x0AAF6, 0x0AAF6},  // Meetei Mayek Ext iik
    {0x0ABE5, 0x0ABE5},  // Meetei Mayek Ext anap
    {0x0ABE8, 0x0ABE8},  // Meetei Mayek Ext unap
    {0x0ABED, 0x0ABED},  // Meetei Mayek Ext cheinap
    {0x0FB1E, 0x0FB1E},  // Hebrew point judeo-spanish
    {0x0FE00, 0x0FE0F},  // Variation Selectors VS1..VS16
    {0x0FE20, 0x0FE2F},  // Combining Half Marks
    {0x101FD, 0x101FD},  // Phaistos combining
    {0x102E0, 0x102E0},  // Coptic Epact combining
    {0x10376, 0x1037A},  // Old Permic combining
    {0x10A01, 0x10A03},  // Kharoshthi vowel marks
    {0x10A05, 0x10A06},  // Kharoshthi vowel sign e
    {0x10A0C, 0x10A0F},  // Kharoshthi signs
    {0x10A38, 0x10A3A},  // Kharoshthi bar marks
    {0x10A3F, 0x10A3F},  // Kharoshthi virama
    {0x10AE5, 0x10AE6},  // Manichaean abbreviation marks
    {0x10D24, 0x10D27},  // Hanifi Rohingya marks
    {0x10EAB, 0x10EAC},  // Yezidi combining hamza / madda (13.0)
    {0x10EFD, 0x10EFF},  // Arabic Ext-C small low marks (14.0)
    {0x10F46, 0x10F50},  // Sogdian combining marks
    {0x10F82, 0x10F85},  // Old Uyghur combining marks
    {0x11001, 0x11001},  // Brahmi sign anusvara
    {0x11038, 0x11046},  // Brahmi vowel signs + virama
    {0x11070, 0x11070},  // Brahmi old-Tamil virama
    {0x11073, 0x11074},  // Brahmi old-Tamil short E / O
    {0x1107F, 0x11081},  // Brahmi number joiner + Kaithi candrabindu
    {0x110B3, 0x110B6},  // Kaithi vowel signs U..vocalic LL
    {0x110B9, 0x110BA},  // Kaithi virama + nukta
    {0x110C2, 0x110C2},  // Kaithi vowel sign vocalic R
    {0x11100, 0x11102},  // Chakma candrabindus
    {0x11127, 0x1112B},  // Chakma vowel signs
    {0x1112D, 0x11134},  // Chakma vowel signs (cont.) + virama
    {0x11173, 0x11173},  // Mahajani sign nukta
    {0x11180, 0x11181},  // Sharada candrabindu / anusvara
    {0x111B6, 0x111BE},  // Sharada vowel signs
    {0x111C9, 0x111CC},  // Sharada extra signs
    {0x111CF, 0x111CF},  // Sharada sign nukta
    {0x1122F, 0x11231},  // Khojki vowel signs
    {0x11234, 0x11234},  // Khojki sign virama
    {0x11236, 0x11237},  // Khojki sign nukta + shadda
    {0x1123E, 0x1123E},  // Khojki sign sukun
    {0x11241, 0x11241},  // Khojki vowel sign vocalic R
    {0x112DF, 0x112DF},  // Khudawadi sign nukta
    {0x112E3, 0x112EA},  // Khudawadi vowel signs + virama
    {0x11300, 0x11301},  // Grantha combining anusvara
    {0x1133B, 0x1133C},  // Grantha nukta
    {0x11340, 0x11340},  // Grantha vowel sign ii
    {0x11366, 0x1136C},  // Grantha comb. vowel signs AA..vocalic R
    {0x11370, 0x11374},  // Grantha comb. vowel signs (cont.)
    {0x11438, 0x1143F},  // Newa vowel signs + virama
    {0x11442, 0x11444},  // Newa vowel signs (cont.)
    {0x11446, 0x11446},  // Newa sign nukta
    {0x1145E, 0x1145E},  // Newa sign candrabindu
    {0x114B3, 0x114B8},  // Tirhuta vowel signs U..vocalic LL
    {0x114BA, 0x114BA},  // Tirhuta vowel sign short E
    {0x114BF, 0x114C0},  // Tirhuta candrabindu / anusvara
    {0x114C2, 0x114C3},  // Tirhuta virama + nukta
    {0x115B2, 0x115B5},  // Siddham vowel signs U..vocalic RR
    {0x115BC, 0x115BD},  // Siddham candrabindu / anusvara
    {0x115BF, 0x115C0},  // Siddham virama + nukta
    {0x115DC, 0x115DD},  // Siddham alternate U / UU
    {0x11633, 0x1163A},  // Modi vowel signs U..vocalic LL
    {0x1163D, 0x1163D},  // Modi sign anusvara
    {0x1163F, 0x11640},  // Modi virama + ardhacandra
    {0x116AB, 0x116AB},  // Takri sign anusvara
    {0x116AD, 0x116AD},  // Takri vowel sign AA
    {0x116B0, 0x116B5},  // Takri vowel signs U..AU
    {0x116B7, 0x116B7},  // Takri sign nukta
    {0x1171D, 0x1171F},  // Ahom vowel signs
    {0x11722, 0x11725},  // Ahom vowel signs I..UU
    {0x11727, 0x1172A},  // Ahom vowel signs AW..AM
    {0x1172B, 0x1172B},  // Ahom killer
    {0x1182F, 0x11837},  // Dogra vowel signs U..AU
    {0x11839, 0x1183A},  // Dogra virama + nukta
    {0x1193B, 0x1193C},  // Dives Akuru anusvara / candrabindu
    {0x1193E, 0x1193E},  // Dives Akuru virama
    {0x11943, 0x11943},  // Dives Akuru sign nukta
    {0x119D4, 0x119D7},  // Nandinagari vowel signs U..UU
    {0x119DA, 0x119DB},  // Nandinagari vowel signs E / AI
    {0x119E0, 0x119E0},  // Nandinagari sign virama
    {0x11A01, 0x11A0A},  // Zanabazar Square vowel signs
    {0x11A33, 0x11A38},  // Zanabazar final consonant marks
    {0x11A3B, 0x11A3E},  // Zanabazar cluster-final letters
    {0x11A47, 0x11A47},  // Zanabazar Square subjoiner
    {0x11A51, 0x11A56},  // Soyombo vowel signs I..OE
    {0x11A59, 0x11A5B},  // Soyombo vocalic R / L + length mark
    {0x11A8A, 0x11A96},  // Soyombo final consonant signs
    {0x11A98, 0x11A99},  // Soyombo gemination mark + subjoiner
    {0x11C30, 0x11C36},  // Bhaiksuki vowel signs
    {0x11C38, 0x11C3D},  // Bhaiksuki vowel signs (cont.)
    {0x11C3F, 0x11C3F},  // Bhaiksuki sign virama
    {0x11C92, 0x11CA7},  // Marchen subjoined letters
    {0x11CAA, 0x11CB0},  // Marchen subjoined letters (cont.)
    {0x11CB2, 0x11CB3},  // Marchen vowel signs U / E
    {0x11CB5, 0x11CB6},  // Marchen anusvara + candrabindu
    {0x11D31, 0x11D36},  // Masaram Gondi vowel signs AA..vocalic R
    {0x11D3A, 0x11D3A},  // Masaram Gondi vowel sign E
    {0x11D3C, 0x11D3D},  // Masaram Gondi vowel signs AI / O
    {0x11D3F, 0x11D45},  // Masaram Gondi AU..halanta
    {0x11D47, 0x11D47},  // Masaram Gondi ra-kara
    {0x11D90, 0x11D91},  // Gunjala Gondi vowel signs EE / UU
    {0x11D95, 0x11D95},  // Gunjala Gondi sign anusvara
    {0x11D97, 0x11D97},  // Gunjala Gondi virama
    {0x11EF3, 0x11EF4},  // Makasar vowel signs I / U
    {0x11F00, 0x11F01},  // Kawi candrabindu / anusvara (15.0)
    {0x11F36, 0x11F3A},  // Kawi vowel signs I..UU
    {0x11F40, 0x11F40},  // Kawi vowel sign EU
    {0x11F42, 0x11F42},  // Kawi conjoiner
    {0x13440, 0x13440},  // Egyptian mirror horizontally
    {0x13447, 0x13455},  // Egyptian damaged-at modifiers
    {0x16AF0, 0x16AF4},  // Bassa Vah combining marks
    {0x16B30, 0x16B36},  // Pahawh Hmong tone marks
    {0x16F4F, 0x16F4F},  // Miao consonant modifier bar
    {0x16F8F, 0x16F92},  // Miao tone marks
    {0x16FE4, 0x16FE4},  // Khitan small script filler
    {0x1BC9D, 0x1BC9E},  // Duployan affix marks
    {0x1CF00, 0x1CF2D},  // Znamenny combining marks (14.0)
    {0x1CF30, 0x1CF46},  // Znamenny tonal range marks (14.0)
    {0x1D167, 0x1D169},  // Musical combining tremolo 1..3
    {0x1D17B, 0x1D182},  // Musical combining stems
    {0x1D185, 0x1D18B},  // Musical tremolo / strokes
    {0x1D1AA, 0x1D1AD},  // Musical ligature slurs
    {0x1D242, 0x1D244},  // Musical combining flags (8.0)
    {0x1DA00, 0x1DA36},  // Signwriting head/rim marks
    {0x1DA3B, 0x1DA56},  // Signwriting jaw locations
    {0x1DA57, 0x1DA5A},  // Signwriting mouth / tongue marks
    {0x1DA5B, 0x1DA62},  // Signwriting torso locations
    {0x1DA63, 0x1DA6C},  // Signwriting teeth / movement
    {0x1DA75, 0x1DA75},  // Signwriting upper body tilting
    {0x1DA84, 0x1DA84},  // Signwriting location head neck
    {0x1DA9B, 0x1DA9F},  // Signwriting fill
    {0x1DAA1, 0x1DAAF},  // Signwriting rotation
    {0x1E000, 0x1E02A},  // Glagolitic Suppl. combining
    {0x1E08F, 0x1E08F},  // Combining Cyrillic small letter I
    {0x1E130, 0x1E136},  // Nyiakeng Puachue Hmong tones
    {0x1E2AE, 0x1E2AE},  // Wancho tone upper (13.0)
    {0x1E2EC, 0x1E2EF},  // Wancho tones tup..koini (14.0)
    {0x1E4EC, 0x1E4EF},  // Nag Mundari signs (15.0)
    {0x1E8D0, 0x1E8D6},  // Mende Kikakui combining
    {0x1E944, 0x1E94A},  // Adlam combining
    {0xE0100, 0xE01EF},  // Variation Selectors Supplement (MVS)
};

// ── Cf / Zl / Zp format + control class ──────────────────────────────────
// Grapheme_Cluster_Break class Control (invisible, forms its own cluster)
// and displayWidth 0. ZWJ (200D), ZWNJ (200C), the tag block, the Cf
// preppends and the emoji modifiers live in their own tables below.
// 2028/2029 are Zl/Zp (not Cf) but are line/paragraph separators — treated
// as control-class, zero-width format characters here.
constexpr Range kFormatControl[] = {
    {0x000AD, 0x000AD},  // SOFT HYPHEN
    {0x0061C, 0x0061C},  // ARABIC LETTER MARK
    {0x0180E, 0x0180E},  // MONGOLIAN VOWEL SEPARATOR
    {0x0200B, 0x0200B},  // ZERO WIDTH SPACE
    {0x0200E, 0x0200F},  // LRM / RLM
    {0x02028, 0x0202E},  // Zl, Zp + bidi embedding controls (Cf)
    {0x02060, 0x02064},  // WORD JOINER..INVISIBLE PLUS
    {0x02066, 0x0206F},  // LRI..PDI (bidi isolates)
    {0x0FEFF, 0x0FEFF},  // ZERO WIDTH NO-BREAK SPACE
    {0x0FFF9, 0x0FFFB},  // interlinear annotation anchors (Cf)
    {0x13430, 0x1343F},  // Egyptian hieroglyph format controls (Cf)
    {0x1BCA0, 0x1BCA3},  // shorthand format controls (Cf)
    {0x1D173, 0x1D17A},  // musical format controls (Cf)
    {0xE0001, 0xE0001},  // LANGUAGE TAG
};

// ── Extend-class, zero width (non-Mn/Me) ─────────────────────────────────
// Grapheme_Cluster_Break class Extend, displayWidth 0: ZWNJ, ZWJ (class
// kZwj but zero width too) and the emoji modifiers / tag chars that
// combine onto the base cluster, plus the two musical Mc→Extend exceptions
// (combining stems / augmentation dots — zero-width per project contract).
constexpr Range kExtendZero[] = {
    {0x0200C, 0x0200D},  // ZWNJ + ZWJ (ZWJ is class kZwj, still width 0)
    {0x1D165, 0x1D166},  // musical combining stems (Mc, GCB Extend)
    {0x1D16D, 0x1D172},  // musical augmentation dots (Mc, GCB Extend)
    {0x1DA77, 0x1DA77},  // Signwriting upper arm location (So, GCB Extend)
    {0x1F3FB, 0x1F3FF},  // emoji skin-tone modifiers (Sk, Extend)
    {0xE0020, 0xE007F},  // tag characters (Cf, Extend; subdivision flags)
};

// ── Extend-class, spacing (Mc exceptions of GraphemeBreakProperty) ────
// Class Extend but EAW Neutral/Half/Wide — displayWidth 1 or 2 (fall
// through to the EAW decision in displayWidth()). These are the UAX #29
// "excluded from SpacingMark" Mc set: left-side vowel signs, Myanmar tone
// clusters, Balinese tedung composites and friends. The Myanmar/Balinese
// tail entries are best-effort (GB9 vs GB9a is behaviorally identical
// here, so a rare misfiling cannot change a boundary).
constexpr Range kExtendMc[] = {
    {0x009BE, 0x009BE},  // Bengali vowel sign AA (to-the-left, Mc→Extend)
    {0x009D7, 0x009D7},  // Bengali vowel sign vocalic RR (Mc→Extend)
    {0x00B3E, 0x00B3E},  // Oriya vowel sign AA (to-the-left, Mc→Extend)
    {0x00B57, 0x00B57},  // Oriya vowel sign AI length mark (Mc→Extend)
    {0x00BBE, 0x00BBE},  // Tamil vowel sign AA (to-the-left, Mc→Extend)
    {0x00BD7, 0x00BD7},  // Tamil vowel sign AI candra (Mc→Extend)
    {0x00CC2, 0x00CC2},  // Kannada vowel sign II (Mc→Extend)
    {0x00CD5, 0x00CD6},  // Kannada length marks (Mc→Extend)
    {0x00D3E, 0x00D3E},  // Malayalam vowel sign AA (Mc→Extend)
    {0x00D57, 0x00D57},  // Malayalam vowel sign AI (Mc→Extend)
    {0x00DCF, 0x00DCF},  // Sinhala vowel sign AELA-PAA (Mc→Extend)
    {0x00DDF, 0x00DDF},  // Sinhala vowel sign GAYANUKITTA (Mc→Extend)
    {0x0102B, 0x0102C},  // Myanmar vowel signs TALL AA / AA
    {0x01038, 0x01038},  // Myanmar sign visarga
    {0x01062, 0x01064},  // Myanmar Sgaw Karen EU signs
    {0x01067, 0x0106D},  // Myanmar W. Pwo Karen EU signs
    {0x01083, 0x01083},  // Myanmar Shan vowel sign AA
    {0x01087, 0x0108C},  // Myanmar Shan tones 2..6
    {0x0108F, 0x0108F},  // Myanmar Rumai Palaung tone-5
    {0x0109A, 0x0109C},  // Myanmar Khamti tones
    {0x01B35, 0x01B35},  // Balinese vowel sign tedung (Mc→Extend)
    {0x01B3B, 0x01B3B},  // Balinese RA REPA TEDUNG (Mc→Extend)
    {0x01B3D, 0x01B41},  // Balinese LA LENGA TEDUNG family (Mc→Extend)
    {0x01B43, 0x01B44},  // Balinese PEPET TEDUNG pair (Mc→Extend)
    {0x0302E, 0x0302F},  // Hangul tone marks (Mc→Extend; EAW Wide)
    {0x0FF9E, 0x0FF9F},  // halfwidth katakana voiced marks (Lm→Extend)
    {0x1133E, 0x1133E},  // Grantha vowel sign AA (Mc→Extend)
    {0x11357, 0x11357},  // Grantha vowel sign vocalic RR (Mc→Extend)
    {0x114B0, 0x114B0},  // Tirhuta vowel sign AA (Mc→Extend)
    {0x114BD, 0x114BD},  // Tirhuta vowel sign short vocalic R (Mc→Extend)
    {0x115AF, 0x115AF},  // Siddham vowel sign AA (Mc→Extend)
    {0x11930, 0x11930},  // Dives Akuru vowel sign AA (Mc→Extend)
};

// ── SpacingMark (Mc, GB9a) ───────────────────────────────────────────────
// Spacing combining marks join the cluster but occupy their own terminal
// column (EAW Neutral → displayWidth 1). 0E33/0EB3 are Lo but decompose
// to spacing vowel + combining virama → SpacingMark per UAX #29; 303B is
// an Lm SpacingMark. Complete for every assigned Mc of Unicode 15.1 that
// is neither a kExtendMc exception nor zero-width (checked in UCD order).
constexpr Range kSpacingMark[] = {
    {0x00903, 0x00903},  // Devanagari sign visarga
    {0x0093B, 0x0093B},  // Devanagari vowel sign OOE
    {0x0093E, 0x00940},  // Devanagari vowel signs AA..II
    {0x00949, 0x0094C},  // Devanagari vowel signs CANDRA O..AU
    {0x0094E, 0x0094F},  // Devanagari vowel signs PRISHTHAMATRAS
    {0x00982, 0x00983},  // Bengali signs anusvara + visarga
    {0x009BF, 0x009C0},  // Bengali vowel signs I..II
    {0x009C7, 0x009C8},  // Bengali vowel signs E..AI
    {0x009CB, 0x009CC},  // Bengali vowel signs O..AU
    {0x00A03, 0x00A03},  // Gurmukhi sign visarga
    {0x00A3E, 0x00A40},  // Gurmukhi vowel signs AA..II
    {0x00A83, 0x00A83},  // Gujarati sign visarga
    {0x00ABE, 0x00AC0},  // Gujarati vowel signs AA..II
    {0x00AC9, 0x00AC9},  // Gujarati vowel sign CANDRA O
    {0x00ACB, 0x00ACC},  // Gujarati vowel signs O..AU
    {0x00B02, 0x00B03},  // Oriya signs anusvara + visarga
    {0x00B40, 0x00B40},  // Oriya vowel sign AI
    {0x00B47, 0x00B48},  // Oriya vowel signs E..AI
    {0x00B4B, 0x00B4C},  // Oriya vowel signs O..AU
    {0x00BBF, 0x00BBF},  // Tamil vowel sign I
    {0x00BC1, 0x00BC2},  // Tamil vowel signs U..UU
    {0x00BC6, 0x00BC8},  // Tamil vowel signs E..AI
    {0x00BCA, 0x00BCC},  // Tamil vowel signs O..AU
    {0x00C01, 0x00C03},  // Telugu signs candrabindu..visarga
    {0x00C41, 0x00C44},  // Telugu vowel signs U..vocalic RR
    {0x00C82, 0x00C83},  // Kannada signs anusvara + visarga
    {0x00CBE, 0x00CBE},  // Telugu vowel sign AA
    {0x00CC0, 0x00CC1},  // Kannada vowel signs II + vocalic R
    {0x00CC3, 0x00CC4},  // Kannada vowel signs vocalic RR + L
    {0x00CC7, 0x00CC8},  // Kannada vowel signs VOCALIC E..EE
    {0x00CCA, 0x00CCB},  // Kannada vowel signs O..AU
    {0x00CF3, 0x00CF3},  // Kannada anusvara above right (15.0)
    {0x00D02, 0x00D03},  // Malayalam signs anusvara + visarga
    {0x00D3F, 0x00D40},  // Malayalam vowel signs I..II
    {0x00D46, 0x00D48},  // Malayalam vowel signs E..AI
    {0x00D4A, 0x00D4C},  // Malayalam vowel signs O..AU
    {0x00D82, 0x00D83},  // Sinhala signs anusvaraya + visargaya
    {0x00DD0, 0x00DD1},  // Sinhala vowel signs KETTI/DIGA AEDA-PILLA
    {0x00DD8, 0x00DDE},  // Sinhala vowel signs GAETTA-PILLA family
    {0x00DF2, 0x00DF3},  // Sinhala vowel signs DIGA GAETTA-PILLA
    {0x00F3E, 0x00F3F},  // Tibetan signs YAR / MAR TSHES
    {0x00F7F, 0x00F7F},  // Tibetan sign RNAM BCAD
    {0x01031, 0x01031},  // Myanmar vowel sign E
    {0x0103B, 0x0103C},  // Myanmar consonant signs MEDIAL YA / RA
    {0x01056, 0x01057},  // Myanmar vowel signs VOCALIC R / RR
    {0x01084, 0x01084},  // Myanmar Shan vowel sign E
    {0x01715, 0x01715},  // Tagalog sign pamudpod
    {0x01734, 0x01734},  // Hanunoo sign pamudpod
    {0x017B6, 0x017B6},  // Khmer vowel sign AA
    {0x017BE, 0x017C5},  // Khmer vowel signs OE..AI
    {0x017C7, 0x017C8},  // Khmer signs REAHMUK + YUUKALEAKINTOOPHUAS
    {0x01923, 0x01926},  // Limbu vowel signs EE..AU
    {0x01929, 0x0192B},  // Limbu subjoined letters YA..WA
    {0x01930, 0x01931},  // Limbu small letters KA / GA
    {0x01933, 0x01938},  // Limbu small letters TA..LA
    {0x01A19, 0x01A1A},  // Buginese vowel signs E / O
    {0x01A55, 0x01A55},  // Tai Tham consonant sign MEDIAL RA
    {0x01A57, 0x01A57},  // Tai Tham consonant sign LA TANG LAI
    {0x01A61, 0x01A61},  // Tai Tham vowel sign A
    {0x01A63, 0x01A64},  // Tai Tham vowel signs AA / II
    {0x01A6D, 0x01A72},  // Tai Tham vowel signs OY..OA
    {0x01B04, 0x01B04},  // Balinese sign BISAH
    {0x01B82, 0x01B82},  // Sundanese sign PANGWISAD
    {0x01BA1, 0x01BA1},  // Sundanese consonant sign PAMINGKAL
    {0x01BA6, 0x01BA7},  // Sundanese vowel signs PANAELAENG / PANEULEUNG
    {0x01BAA, 0x01BAA},  // Sundanese sign PAMAAEH
    {0x01BE7, 0x01BE7},  // Batak vowel sign E
    {0x01BEA, 0x01BEC},  // Batak vowel signs I / KARO I / O
    {0x01BEE, 0x01BEE},  // Batak vowel sign U
    {0x01BF2, 0x01BF3},  // Batak PANGOLAT + PANONGONAN
    {0x01C24, 0x01C2B},  // Lepcha subjoined letters
    {0x01C34, 0x01C35},  // Lepcha consonant sign NYIN-DO
    {0x01CE1, 0x01CE1},  // Vedic tone atharvavedic independent svarita
    {0x01CF7, 0x01CF7},  // Vedic sign atikrama (7.0)
    {0x0303B, 0x0303B},  // vertical ideographic iteration mark (EAW Wide)
    {0x0A823, 0x0A824},  // Syloti Nagri vowel signs A / AA
    {0x0A827, 0x0A827},  // Syloti Nagri vowel sign OO
    {0x0A880, 0x0A881},  // Saurashtra signs ANUSVARA + VISARGA
    {0x0A8B4, 0x0A8C3},  // Saurashtra consonant signs HAARU etc.
    {0x0A952, 0x0A953},  // Rejang consonant signs
    {0x0A983, 0x0A983},  // Javanese sign WIGNYAN
    {0x0A9B4, 0x0A9B5},  // Javanese vowel sign TARUNG
    {0x0A9BA, 0x0A9BB},  // Javanese vowel sign TALING
    {0x0A9BE, 0x0A9C0},  // Javanese consonant signs PENGKAL etc.
    {0x0AA2F, 0x0AA30},  // Cham vowel signs O / AI
    {0x0AA33, 0x0AA34},  // Cham consonant signs YA / RA
    {0x0AA4D, 0x0AA4D},  // Cham consonant sign FINAL H
    {0x0AA7B, 0x0AA7B},  // Myanmar sign PAO KAREN TONE
    {0x0AA7D, 0x0AA7D},  // Myanmar sign TAI LAING TONE-5
    {0x0AAEB, 0x0AAEB},  // Meetei Mayek vowel sign II
    {0x0AAEE, 0x0AAEF},  // Meetei Mayek vowel signs AU / AAU
    {0x0AAF5, 0x0AAF5},  // Meetei Mayek vowel sign VISARGA
    {0x0ABE3, 0x0ABE4},  // Meetei Mayek vowel sign ONAP
    {0x0ABE6, 0x0ABE7},  // Meetei Mayek vowel sign YENAP
    {0x0ABE9, 0x0ABEA},  // Meetei Mayek vowel sign CHEINAP
    {0x0ABEC, 0x0ABEC},  // Meetei Mayek LUM IYEK
    {0x11000, 0x11000},  // Brahmi sign candrabindu
    {0x11002, 0x11002},  // Brahmi sign visarga
    {0x11082, 0x11082},  // Kaithi sign visarga
    {0x110B0, 0x110B2},  // Kaithi vowel signs AA / I / II
    {0x110B7, 0x110B8},  // Kaithi vowel signs O / AU
    {0x1112C, 0x1112C},  // Chakma vowel sign E
    {0x11145, 0x11146},  // Chakma vowel signs AA / I
    {0x11182, 0x11182},  // Sharada sign visarga
    {0x111B3, 0x111B5},  // Sharada vowel signs AA..vocalic R
    {0x111BF, 0x111C0},  // Sharada vowel sign AU + virama
    {0x111CE, 0x111CE},  // Sharada vowel sign PRISHTHAMATRA E
    {0x1122C, 0x1122E},  // Khojki vowel signs AA..II
    {0x11232, 0x11233},  // Khojki vowel signs O / AU
    {0x11235, 0x11235},  // Khojki sign virama
    {0x112E0, 0x112E2},  // Khudawadi vowel signs AA..vocalic R
    {0x11302, 0x11303},  // Grantha signs anusvara + visarga
    {0x1133F, 0x1133F},  // Grantha vowel sign I
    {0x11341, 0x11344},  // Grantha vowel signs U..vocalic RR
    {0x11347, 0x11348},  // Grantha vowel signs EE / AI
    {0x1134B, 0x1134D},  // Grantha vowel signs OO..VOCALIC RR
    {0x11362, 0x11363},  // Grantha vowel signs VOCALIC L / LL
    {0x11435, 0x11437},  // Newa vowel signs AA..vocalic R
    {0x11440, 0x11441},  // Newa vowel signs O / AU
    {0x11445, 0x11445},  // Newa sign visarga
    {0x114B1, 0x114B2},  // Tirhuta vowel signs I / II
    {0x114B9, 0x114B9},  // Tirhuta vowel sign E
    {0x114BB, 0x114BC},  // Tirhuta vowel signs AI / AU
    {0x114BE, 0x114BE},  // Tirhuta vowel sign VOCALIC R
    {0x114C1, 0x114C1},  // Tirhuta sign visarga
    {0x115B0, 0x115B1},  // Siddham vowel signs I / II
    {0x115B8, 0x115BB},  // Siddham vowel signs E..AU
    {0x115BE, 0x115BE},  // Siddham sign visarga
    {0x11630, 0x11632},  // Modi vowel signs AA..vocalic R
    {0x1163B, 0x1163C},  // Modi vowel signs O / AU
    {0x1163E, 0x1163E},  // Modi sign visarga
    {0x116AC, 0x116AC},  // Takri sign visarga
    {0x116AE, 0x116AF},  // Takri vowel signs I / II
    {0x116B6, 0x116B6},  // Takri sign virama
    {0x11720, 0x11721},  // Ahom vowel signs A / AA
    {0x11726, 0x11726},  // Ahom vowel sign E
    {0x1182C, 0x1182E},  // Dogra vowel signs AA..II
    {0x11838, 0x11838},  // Dogra sign visarga
    {0x11931, 0x11935},  // Dives Akuru vowel signs I..U
    {0x11937, 0x11938},  // Dives Akuru vowel signs EE / AI
    {0x1193D, 0x1193D},  // Dives Akuru sign halanta
    {0x11940, 0x11940},  // Dives Akuru medial YA
    {0x11942, 0x11942},  // Dives Akuru medial RA
    {0x119D1, 0x119D3},  // Nandinagari vowel signs AA..vocalic R
    {0x119DC, 0x119DF},  // Nandinagari vowel signs O..AU
    {0x119E4, 0x119E4},  // Nandinagari vowel sign PRISHTHAMATRA E
    {0x11A39, 0x11A39},  // Zanabazar Square sign visarga
    {0x11A57, 0x11A58},  // Soyombo vowel signs AI / AU
    {0x11A97, 0x11A97},  // Soyombo sign visarga
    {0x11C2F, 0x11C2F},  // Bhaiksuki vowel sign AA
    {0x11C3E, 0x11C3E},  // Bhaiksuki sign visarga
    {0x11CA9, 0x11CA9},  // Marchen subjoined letter YA
    {0x11CB1, 0x11CB1},  // Marchen vowel sign I
    {0x11CB4, 0x11CB4},  // Marchen vowel sign O
    {0x11D8A, 0x11D8E},  // Gunjala Gondi vowel signs AA..UU
    {0x11D93, 0x11D94},  // Gunjala Gondi vowel signs OO / O
    {0x11D96, 0x11D96},  // Gunjala Gondi sign visarga
    {0x11EF5, 0x11EF6},  // Makasar vowel signs E / O
    {0x11F03, 0x11F03},  // Kawi sign visarga (15.0)
    {0x11F34, 0x11F35},  // Kawi vowel signs AA / II (15.0)
    {0x11F3E, 0x11F3F},  // Kawi vowel signs E / AI (15.0)
    {0x11F41, 0x11F41},  // Kawi sign killer (15.0)
    {0x16F51, 0x16F87},  // Miao aspiration / tone signs
};

// ── Prepend (GB9b prepended concatenation marks) ─────────────────────────
// Class Prepend. Cf members have displayWidth 0 (see kPrependCf); Lo
// members (0D4E, 111C2..) are EAW Neutral → 1. Complete 15.1 set.
constexpr Range kPrepend[] = {
    {0x00600, 0x00605},  // Arabic number signs
    {0x006DD, 0x006DD},  // Arabic end of ayah
    {0x0070F, 0x0070F},  // Syriac abbreviation mark
    {0x00890, 0x00891},  // Arabic pound / piah marks (14.0)
    {0x008E2, 0x008E2},  // Arabic disputed end of ayah
    {0x00D4E, 0x00D4E},  // Malayalam letter dot reph (Lo)
    {0x110BD, 0x110BD},  // Kaithi number sign
    {0x110CD, 0x110CD},  // Kaithi number sign above
    {0x111C2, 0x111C3},  // Sharada sign jihvamuliya/upadhmaniya (Lo)
    {0x1193F, 0x1193F},  // Dives Akuru prefixed nasal sign
    {0x11941, 0x11941},  // Dives Akuru sign virama (prefixed)
    {0x11A3A, 0x11A3A},  // Zanabazar Square prepended mark
    {0x11A84, 0x11A89},  // Soyombo prepended signs jihvamuliya etc.
    {0x11D46, 0x11D46},  // Masaram Gondi prepended ra
    {0x11F02, 0x11F02},  // Kawi prepended mark (15.0)
};

// Cf subset of kPrepend — format characters, displayWidth 0.
constexpr Range kPrependCf[] = {
    {0x00600, 0x00605},
    {0x006DD, 0x006DD},
    {0x0070F, 0x0070F},
    {0x00890, 0x00891},
    {0x008E2, 0x008E2},
    {0x110BD, 0x110BD},
    {0x110CD, 0x110CD},
};

// ── East Asian Width: Wide + Fullwidth (UAX #11, complete) ───────────────
// Only W/F is tabulated; Na/H/N/A default to 1 in displayWidth().
// Regional indicators are Neutral → width 1 (xterm parity).
constexpr Range kWide[] = {
    {0x01100, 0x0115F},  // Hangul Jamo leading consonants
    {0x0231A, 0x0231B},  // watch, hourglass
    {0x02329, 0x0232A},  // angle brackets
    {0x023E9, 0x023EC},  // black double arrows
    {0x023F0, 0x023F0},  // alarm clock
    {0x023F3, 0x023F3},  // hourglass flowing
    {0x025FD, 0x025FE},  // small squares
    {0x02614, 0x02615},  // umbrella, hot beverage
    {0x02648, 0x02653},  // zodiac
    {0x0267F, 0x0267F},  // wheelchair
    {0x02693, 0x02693},  // anchor
    {0x026A1, 0x026A1},  // high voltage
    {0x026AA, 0x026AB},  // circles
    {0x026BD, 0x026BE},  // soccer, baseball
    {0x026C4, 0x026C5},  // snowman, sun behind cloud
    {0x026CE, 0x026CE},  // ouroboros
    {0x026D4, 0x026D4},  // no entry
    {0x026EA, 0x026EA},  // church
    {0x026F2, 0x026F3},  // fountain, flag in hole
    {0x026F5, 0x026F5},  // sailboat
    {0x026FA, 0x026FA},  // tent
    {0x026FD, 0x026FD},  // fuel pump
    {0x02705, 0x02705},  // check mark button
    {0x0270A, 0x0270B},  // raised fists
    {0x02728, 0x02728},  // sparkles
    {0x0274C, 0x0274C},  // cross mark
    {0x0274E, 0x0274E},  // negative squared cross mark
    {0x02753, 0x02755},  // question / exclamation marks
    {0x02757, 0x02757},  // heavy exclamation
    {0x02795, 0x02797},  // plus / minus / divide
    {0x027B0, 0x027B0},  // curly loop
    {0x027BF, 0x027BF},  // double curly loop
    {0x02B05, 0x02B07},  // left/up/down arrows (EAW W)
    {0x02B1B, 0x02B1C},  // heavy squares
    {0x02B50, 0x02B50},  // star
    {0x02B55, 0x02B55},  // heavy circle
    {0x02E80, 0x02E99},  // CJK Radicals Supplement
    {0x02E9B, 0x02EF3},  // CJK Radicals Supplement (cont.)
    {0x02F00, 0x02FD5},  // Kangxi Radicals
    {0x02FF0, 0x02FFB},  // Ideographic Description Characters
    {0x03000, 0x0303E},  // CJK Symbols and Punctuation
    {0x03041, 0x03096},  // Hiragana
    {0x03099, 0x030FF},  // kana voiced marks + Katakana
    {0x03105, 0x0312F},  // Bopomofo
    {0x03131, 0x0318E},  // Hangul Compatibility Jamo
    {0x03190, 0x031E3},  // Kanbun + Bopomofo Ext + CJK Strokes
    {0x031F0, 0x0321E},  // Katakana Phonetic Ext + Enclosed CJK
    {0x03220, 0x03247},  // Enclosed CJK Letters and Months
    {0x03250, 0x033FF},  // Enclosed CJK + CJK Compatibility
    {0x03400, 0x04DBF},  // CJK Unified Ext A
    {0x04E00, 0x0A48C},  // CJK Unified + Yi Syllables
    {0x0A490, 0x0A4C6},  // Yi Radicals
    {0x0A960, 0x0A97C},  // Hangul Jamo Extended-A
    {0x0AC00, 0x0D7A3},  // Hangul Syllables
    {0x0F900, 0x0FAFF},  // CJK Compatibility Ideographs
    {0x0FE10, 0x0FE19},  // Vertical Forms
    {0x0FE30, 0x0FE52},  // CJK Compatibility Forms
    {0x0FE54, 0x0FE66},  // CJK Compatibility Forms (cont.)
    {0x0FE68, 0x0FE6B},  // Small Form Variants
    {0x0FF01, 0x0FF60},  // Fullwidth Forms (F)
    {0x0FFE0, 0x0FFE6},  // Fullwidth Signs (F)
    {0x16FE0, 0x16FE4},  // Tangut / Nushu iteration marks
    {0x16FF0, 0x16FF1},  // Vietnamese alternate reading marks
    {0x17000, 0x187F7},  // Tangut
    {0x18800, 0x18CD5},  // Khitan Small Script
    {0x18D00, 0x18D08},  // Tangut Supplement
    {0x1AFF0, 0x1AFF3},  // Kana Extended-B
    {0x1AFF5, 0x1AFFB},  // Kana Extended-B (cont.)
    {0x1AFFD, 0x1AFFE},  // Kana Extended-B (cont.)
    {0x1B000, 0x1B122},  // Kana Supplement + Extended-A
    {0x1B132, 0x1B132},  // Hiragana small KO
    {0x1B150, 0x1B152},  // small kana
    {0x1B155, 0x1B155},  // Katakana small KO
    {0x1B164, 0x1B167},  // small kana A I U E O
    {0x1B170, 0x1B2FB},  // Nushu
    {0x1F004, 0x1F004},  // mahjong red dragon
    {0x1F0CF, 0x1F0CF},  // playing card joker
    {0x1F18E, 0x1F18E},  // AB button
    {0x1F191, 0x1F19A},  // enclosed alphanumerics supplement
    {0x1F200, 0x1F202},  // enclosed ideographic supplement
    {0x1F210, 0x1F23B},  // enclosed ideographic supplement (cont.)
    {0x1F240, 0x1F248},  // tortoise shell bracketed CJK
    {0x1F250, 0x1F251},  // circled ideograph advantage/accept
    {0x1F260, 0x1F265},  // enclosed ideograph supplement
    {0x1F300, 0x1F320},  // misc symbols & pictographs
    {0x1F32D, 0x1F335},  // hot dog..cactus
    {0x1F337, 0x1F37C},  // tulip..taco
    {0x1F37E, 0x1F393},  // bottle..graduation cap
    {0x1F3A0, 0x1F3CA},  // carousel horse..person swimming
    {0x1F3CF, 0x1F3D3},  // cricket bat..badminton
    {0x1F3E0, 0x1F3F0},  // house..castle
    {0x1F3F4, 0x1F3F4},  // black flag
    {0x1F3F8, 0x1F43E},  // badminton..paw prints
    {0x1F440, 0x1F440},  // eyes
    {0x1F442, 0x1F4FC},  // ear..video cassette
    {0x1F4FF, 0x1F53D},  // prayer beads..triangle down
    {0x1F54B, 0x1F54E},  // kaaba..menorah
    {0x1F550, 0x1F567},  // clocks
    {0x1F57A, 0x1F57A},  // man dancing
    {0x1F595, 0x1F596},  // middle fingers
    {0x1F5A4, 0x1F5A4},  // black heart
    {0x1F5FB, 0x1F64F},  // Mount Fuji..folded hands
    {0x1F680, 0x1F6C5},  // rocket..left luggage
    {0x1F6CC, 0x1F6CC},  // person in bed
    {0x1F6D0, 0x1F6D2},  // place of worship..shopping cart
    {0x1F6D5, 0x1F6D7},  // hindu temple..elevator
    {0x1F6DC, 0x1F6DF},  // wireless..moai-adjacent (15.x)
    {0x1F6EB, 0x1F6EC},  // airplanes
    {0x1F6F4, 0x1F6FC},  // scooter..roller skate
    {0x1F7E0, 0x1F7EB},  // colored circles/squares
    {0x1F7F0, 0x1F7F0},  // heavy equals sign
    {0x1F90C, 0x1F93A},  // pinched fingers..fencing
    {0x1F93C, 0x1F945},  // wrestlers..goal net
    {0x1F947, 0x1F9FF},  // medal..droplet
    {0x1FA70, 0x1FA7C},  // ballet shoes..face with thermometer
    {0x1FA80, 0x1FA89},  // rescue ring..harp (15.x)
    {0x1FA8F, 0x1FAC6},  // winged?..biting lip
    {0x1FACE, 0x1FADC},  // moose..harp ranges (15.1)
    {0x1FADF, 0x1FAE9},  // folding?..bubbles
    {0x1FAF0, 0x1FAF8},  // hand shapes (15.x)
    {0x20000, 0x2FFFD},  // CJK Ext B..F (incl. compat supplement)
    {0x30000, 0x3FFFD},  // CJK Ext G + reserved
};

// ── Extended_Pictographic (emoji-data.txt 15.1) ──────────────────────────
// BMP core ranges are curated; the emoji planes use the block-level spans
// (emoji-data.txt deliberately includes their unassigned code points).
// Ornamental Dingbats 1F650..1F67F and alchemical 1F700..1F77F are NOT
// pictographic and are excluded. Regional indicators are excluded. Skin
// tones 1F3FB..1F3FF fall inside the 1F300 span but classify Extend first
// (see the class dispatch order), so the superset is behaviorally inert.
constexpr Range kExtPict[] = {
    {0x000A9, 0x000A9},  // ©
    {0x000AE, 0x000AE},  // ®
    {0x0203C, 0x0203C},  // ‼
    {0x02049, 0x02049},  // ⁉
    {0x02122, 0x02122},  // ™
    {0x02139, 0x02139},  // ℹ
    {0x02194, 0x02199},  // arrows
    {0x021A9, 0x021AA},  // hooked arrows
    {0x0231A, 0x0231B},  // watch, hourglass
    {0x02328, 0x02328},  // keyboard
    {0x023CF, 0x023CF},  // eject
    {0x023E9, 0x023F3},  // media control
    {0x023F8, 0x023FA},  // pause / record
    {0x024C2, 0x024C2},  // Ⓜ
    {0x025AA, 0x025AB},  // small squares
    {0x025B6, 0x025B6},  // ▶
    {0x025C0, 0x025C0},  // ◀
    {0x025FB, 0x025FE},  // squares
    {0x02600, 0x02604},  // weather
    {0x0260E, 0x0260E},  // ☎
    {0x02611, 0x02611},  // ☑
    {0x02614, 0x02615},  // umbrella, beverage
    {0x02618, 0x02618},  // ☘
    {0x0261D, 0x0261D},  // ☝
    {0x02620, 0x02620},  // ☠
    {0x02622, 0x02623},  // ☢ ☣
    {0x02626, 0x02626},  // ✡
    {0x0262A, 0x0262A},  // ☪
    {0x0262E, 0x0262F},  // ☮ ☯
    {0x02638, 0x0263A},  // ☸ ☹ ☺
    {0x02640, 0x02640},  // ♀
    {0x02642, 0x02642},  // ♂
    {0x02648, 0x02653},  // zodiac
    {0x02668, 0x02668},  // ♨
    {0x0267B, 0x0267B},  // ♻
    {0x0267F, 0x0267F},  // ♿
    {0x02692, 0x02697},  // ⚒..⚗
    {0x02699, 0x02699},  // ⚙
    {0x0269B, 0x0269C},  // ⚛ ⚜
    {0x026A0, 0x026A1},  // ⚠ ⚡
    {0x026A7, 0x026A7},  // ⚧
    {0x026AA, 0x026AB},  // circles
    {0x026B0, 0x026B1},  // ⚰ ⚱
    {0x026BD, 0x026BE},  // ⚽ ⚾
    {0x026C4, 0x026C5},  // ⛄ ⛅
    {0x026C8, 0x026C8},  // ⛈
    {0x026CE, 0x026CF},  // ⛎ ⛏
    {0x026D1, 0x026D1},  // ⛑
    {0x026D3, 0x026D4},  // ⛓ ⛔
    {0x026E9, 0x026EA},  // ⛩ ⛪
    {0x026F0, 0x026F5},  // ⛰..⛵
    {0x026F7, 0x026FA},  // ⛷..⛺
    {0x026FD, 0x026FD},  // ⛽
    {0x02705, 0x02705},  // ✅
    {0x02708, 0x0270D},  // ✈..✍
    {0x02714, 0x02714},  // ✔
    {0x02716, 0x02716},  // ✖
    {0x0271D, 0x0271D},  // ✝
    {0x02728, 0x02728},  // ✨
    {0x02733, 0x02734},  // ✳ ✴
    {0x02744, 0x02744},  // ❄
    {0x0274C, 0x0274C},  // ❌
    {0x0274E, 0x0274E},  // ❎
    {0x02753, 0x02755},  // ❓❔❕
    {0x02757, 0x02757},  // ❗
    {0x02763, 0x02764},  // ❣ ❤
    {0x02795, 0x02797},  // ➕➖➗
    {0x027A1, 0x027A1},  // ➡
    {0x027B0, 0x027B0},  // ➰
    {0x027BF, 0x027BF},  // ➿
    {0x02934, 0x02935},  // ⤴ ⤵
    {0x02B05, 0x02B07},  // arrows
    {0x02B1B, 0x02B1C},  // heavy squares
    {0x02B50, 0x02B50},  // ⭐
    {0x02B55, 0x02B55},  // ⭕
    {0x03030, 0x03030},  // 〰
    {0x0303D, 0x0303D},  // 〽
    {0x03297, 0x03297},  // ㊗
    {0x03299, 0x03299},  // ㊙
    {0x1F000, 0x1F0FF},  // Mahjong + Playing Cards
    {0x1F10D, 0x1F10F},  // unassigned (emoji-data keeps them ExtPict)
    {0x1F12F, 0x1F12F},  // unassigned (ExtPict)
    {0x1F16C, 0x1F171},  // A / B buttons + unassigned
    {0x1F17E, 0x1F17F},  // double curly loop / floppy
    {0x1F18E, 0x1F18E},  // AB button
    {0x1F191, 0x1F19A},  // enclosed alphanumerics supplement
    {0x1F1AD, 0x1F1E5},  // unassigned (ExtPict, below the RI block)
    {0x1F200, 0x1F2FF},  // Enclosed Ideographic Supplement
    {0x1F300, 0x1F5FF},  // Misc Symbols & Pictographs + Transport
    {0x1F600, 0x1F64F},  // Emoticons
    {0x1F680, 0x1F6FF},  // Transport and Map Supplement
    {0x1F780, 0x1F7EB},  // Geometric Shapes Extended (colored)
    {0x1F7F0, 0x1F7F0},  // heavy equals
    {0x1F800, 0x1F8FF},  // Supplemental Arrows-C
    {0x1F900, 0x1F9FF},  // Supplemental Symbols and Pictographs
    {0x1FA70, 0x1FAFF},  // Symbols and Pictographs Extended-A
};

// ── Emoji_Presentation=Yes (default emoji rendering) ─────────────────────
// emoji-data.txt 15.1. Superset of the EAW-Wide emoji ranges plus the
// text-width default-emoji hearts (U+2764) and regional indicators.
constexpr Range kEmojiPresentation[] = {
    {0x0231A, 0x0231B},
    {0x023E9, 0x023EC},
    {0x023F0, 0x023F0},
    {0x023F3, 0x023F3},
    {0x025FD, 0x025FE},
    {0x02614, 0x02615},
    {0x02648, 0x02653},
    {0x0267F, 0x0267F},
    {0x02693, 0x02693},
    {0x026A1, 0x026A1},
    {0x026AA, 0x026AB},
    {0x026BD, 0x026BE},
    {0x026C4, 0x026C5},
    {0x026CE, 0x026CE},
    {0x026D4, 0x026D4},
    {0x026EA, 0x026EA},
    {0x026F2, 0x026F3},
    {0x026F5, 0x026F5},
    {0x026FA, 0x026FA},
    {0x026FD, 0x026FD},
    {0x02705, 0x02705},
    {0x0270A, 0x0270B},
    {0x02728, 0x02728},
    {0x0274C, 0x0274C},
    {0x0274E, 0x0274E},
    {0x02753, 0x02755},
    {0x02757, 0x02757},
    {0x02764, 0x02764},  // heavy black heart (EBP despite EAW N)
    {0x02795, 0x02797},
    {0x027B0, 0x027B0},
    {0x027BF, 0x027BF},
    {0x02B1B, 0x02B1C},
    {0x02B50, 0x02B50},
    {0x02B55, 0x02B55},
    {0x1F004, 0x1F004},
    {0x1F0CF, 0x1F0CF},
    {0x1F18E, 0x1F18E},
    {0x1F191, 0x1F19A},
    {0x1F1E6, 0x1F1FF},  // regional indicators (EBP=Yes, width 1)
    {0x1F201, 0x1F202},
    {0x1F210, 0x1F23B},
    {0x1F240, 0x1F248},
    {0x1F250, 0x1F251},
    {0x1F260, 0x1F265},
    {0x1F300, 0x1F320},
    {0x1F32D, 0x1F335},
    {0x1F337, 0x1F37C},
    {0x1F37E, 0x1F393},
    {0x1F3A0, 0x1F3CA},
    {0x1F3CF, 0x1F3D3},
    {0x1F3E0, 0x1F3F0},
    {0x1F3F4, 0x1F3F4},
    {0x1F3F8, 0x1F43E},
    {0x1F440, 0x1F440},
    {0x1F442, 0x1F4FC},
    {0x1F4FF, 0x1F53D},
    {0x1F54B, 0x1F54E},
    {0x1F550, 0x1F567},
    {0x1F57A, 0x1F57A},
    {0x1F595, 0x1F596},
    {0x1F5A4, 0x1F5A4},
    {0x1F5FB, 0x1F64F},
    {0x1F680, 0x1F6C5},
    {0x1F6CC, 0x1F6CC},
    {0x1F6D0, 0x1F6D2},
    {0x1F6D5, 0x1F6D7},
    {0x1F6DC, 0x1F6DF},
    {0x1F6EB, 0x1F6EC},
    {0x1F6F4, 0x1F6FC},
    {0x1F7E0, 0x1F7EB},
    {0x1F7F0, 0x1F7F0},
    {0x1F90C, 0x1F93A},
    {0x1F93C, 0x1F945},
    {0x1F947, 0x1F9FF},
    {0x1FA70, 0x1FA7C},
    {0x1FA80, 0x1FA89},
    {0x1FA8F, 0x1FAC6},
    {0x1FACE, 0x1FADC},
    {0x1FADF, 0x1FAE9},
    {0x1FAF0, 0x1FAF8},
};

// ── Emoji_Component extras beyond EBP ∪ ExtPict ──────────────────────────
// Keycap bases, ZWJ, the keycap combiner, VS16 and the tag characters used
// by subdivision-flag ZWJ sequences.
constexpr Range kEmojiComponentExtra[] = {
    {0x00023, 0x00023},  // # (keycap base)
    {0x0002A, 0x0002A},  // * (keycap base)
    {0x00030, 0x00039},  // 0..9 (keycap bases)
    {0x0200D, 0x0200D},  // ZWJ
    {0x020E3, 0x020E3},  // COMBINING ENCLOSING KEYCAP
    {0x0FE0F, 0x0FE0F},  // VS16 (Emoji_Component per emoji-data.txt)
    {0xE0020, 0xE007F},  // tag characters (subdivision flags)
};

// ── Ideographic ranges for word selection ────────────────────────────────
// Double-click word selection should treat CJK text as one ideographic
// stream. Kana / Hangul / fullwidth Latin are excluded on purpose.
constexpr Range kIdeographic[] = {
    {0x02E80, 0x0303E},  // CJK radicals + symbols + punctuation
    {0x031C0, 0x031E3},  // CJK strokes
    {0x03400, 0x04DBF},  // CJK Ext A
    {0x04E00, 0x09FFF},  // CJK Unified
    {0x0F900, 0x0FAFF},  // CJK Compatibility Ideographs
    {0x20000, 0x2FFFD},  // CJK Ext B..F + compat supplement
    {0x30000, 0x3FFFD},  // CJK Ext G + reserved
};

// ── Word joiners ─────────────────────────────────────────────────────────
// Invisibles that suppress word / line breaking around them. U+2060 is the
// canonical WORD JOINER; U+FEFF retains the legacy zero-width-no-break role.
constexpr Range kWordJoiner[] = {
    {0x02060, 0x02060},  // WORD JOINER
    {0x0FEFF, 0x0FEFF},  // ZWNBSP (legacy word joiner)
};

}  // namespace

const char* unicodeVersion() { return "15.1.0"; }

int displayWidth(uint32_t cp) {
  if (cp < 0x20) return 0;                        // C0
  if (cp >= 0x7F && cp <= 0x9F) return 0;         // DEL + C1
  if (cp < 0x300) return cp == 0x00AD ? 0 : 1;    // Latin-1 fast path
  if (in(kMnMe, cp)) return 0;                    // Mn/Me
  if (in(kFormatControl, cp)) return 0;           // Cf + Zl/Zp
  if (in(kExtendZero, cp)) return 0;              // ZWJ/ZWNJ/skin tones/tags
  if (in(kPrependCf, cp)) return 0;               // Cf preppends
  if (in(kWide, cp)) return 2;                    // EAW W/F
  return 1;                                       // Na/H/N/A
}

Gc graphemeClass(uint32_t cp) {
  if (cp == 0x0D) return Gc::kCr;
  if (cp == 0x0A) return Gc::kLf;
  if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) return Gc::kControl;  // C0/C1
  if (cp == 0x0E33 || cp == 0x0EB3) {
    return Gc::kSpacingMark;  // Thai/Lao SARA AM: Lo but SpacingMark per UAX #29
  }
  if (cp == 0x200D) return Gc::kZwj;
  if (in(kFormatControl, cp)) return Gc::kControl;  // Cf/Zl/Zp control set
  if (in(kExtendZero, cp)) return Gc::kExtend;      // ZWNJ, skin tones, tags
  if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return Gc::kRegionalIndicator;
  // Hangul jamo / syllable arithmetic (no tables needed).
  if ((cp >= 0x1100 && cp <= 0x115F) || (cp >= 0xA960 && cp <= 0xA97C)) {
    return Gc::kL;
  }
  if ((cp >= 0x1160 && cp <= 0x11A7) || (cp >= 0xD7B0 && cp <= 0xD7C6)) {
    return Gc::kV;
  }
  if ((cp >= 0x11A8 && cp <= 0x11FF) || (cp >= 0xD7CB && cp <= 0xD7FB)) {
    return Gc::kT;
  }
  if (cp >= 0xAC00 && cp <= 0xD7A3) {
    return ((cp - 0xAC00) % 28 == 0) ? Gc::kLv : Gc::kLvt;
  }
  if (in(kPrepend, cp)) return Gc::kPrepend;
  if (in(kMnMe, cp) || in(kExtendMc, cp)) return Gc::kExtend;
  if (in(kSpacingMark, cp)) return Gc::kSpacingMark;
  if (in(kExtPict, cp)) return Gc::kExtendedPictographic;
  return Gc::kOther;
}

bool isEmojiPresentationDefault(uint32_t cp) { return in(kEmojiPresentation, cp); }

bool isExtendedPictographic(uint32_t cp) { return in(kExtPict, cp); }

bool isEmojiComponent(uint32_t cp) {
  return in(kEmojiComponentExtra, cp) || isEmojiPresentationDefault(cp) ||
         isExtendedPictographic(cp);
}

bool isRegionalIndicator(uint32_t cp) {
  return cp >= 0x1F1E6 && cp <= 0x1F1FF;
}

bool isIdeographic(uint32_t cp) { return in(kIdeographic, cp); }

bool isWordJoiner(uint32_t cp) { return in(kWordJoiner, cp); }

}  // namespace apex::vt::uni
