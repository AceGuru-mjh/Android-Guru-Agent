// apex-vt-search — implementation (see vt_search.h for the contract).
#include "vt_search.h"

namespace apex::vt {

namespace {

// ─── length-preserving case-fold tables ──────────────────────────────────
// Every entry maps a code point to a lowercase code point in the SAME UTF-8
// byte-length class (2-byte → 2-byte, 3-byte → 3-byte), which is what keeps
// folded offsets 1:1 with the input bytes.

struct FoldRange {
  uint32_t lo, hi;
  int32_t delta;  // folded = cp + delta (negative for Greek Extended)
};

// Sorted — binary-searched. Same-length ranges only.
constexpr FoldRange kFoldAdd[] = {
    {0x0041, 0x005A, 0x20},      // A-Z → a-z
    {0x00C0, 0x00D6, 0x20},      // À-Ö → à-ö (0xD7 × has no case pair)
    {0x00D8, 0x00DE, 0x20},      // Ø-Þ → ø-þ (0xDF ß kept: ß→"ss" changes length)
    {0x0386, 0x0386, 0x26},      // Ά → ά
    {0x0388, 0x038A, 0x25},      // Έ-Ί → έ-ί
    {0x038C, 0x038C, 0x40},      // Ό → ό
    {0x038E, 0x038F, 0x3F},      // Ύ-Ώ → ύ-ώ
    {0x0391, 0x03A1, 0x20},      // Α-Ρ → α-ρ (0x3A2 unassigned)
    {0x03A3, 0x03AB, 0x20},      // Σ-Ϋ → σ-ϊ
    {0x03C2, 0x03C2, 0x01},      // ς → σ (final sigma folds to sigma)
    {0x0400, 0x040F, 0x50},      // Ѐ-Џ → ѐ-џ
    {0x0410, 0x042F, 0x20},      // А-Я → а-я
    {0x0531, 0x0556, 0x30},      // Ա-Ֆ Armenian → ա-ֆ
    // Greek Extended (polytonic Greek) — CaseFolding "common" entries, all
    // 3-byte -> 3-byte. The -8 families map accented capitals onto the small
    // accented block 0x30 lower; the vowel-with-varia families need uneven
    // deltas (U+1FBA -> U+1F70 etc.). Rho-with-dasia U+1FEC -> U+1FE5 and
    // the U+1F59/1F5B/1F5D/1F5F singles live in kFoldSingle below.
    {0x1F08, 0x1F0F, -0x08},  // U+1F08..1F0F -> U+1F00..1F07 (alpha family)
    {0x1F18, 0x1F1D, -0x08},  // U+1F18..1F1D -> U+1F10..1F15 (epsilon)
    {0x1F28, 0x1F2F, -0x08},  // U+1F28..1F2F -> U+1F20..1F27 (eta)
    {0x1F38, 0x1F3F, -0x08},  // U+1F38..1F3F -> U+1F30..1F37 (iota)
    {0x1F48, 0x1F4D, -0x08},  // U+1F48..1F4D -> U+1F40..1F45 (omicron)
    {0x1F68, 0x1F6F, -0x08},  // U+1F68..1F6F -> U+1F60..1F67 (omega)
    {0x1F88, 0x1F8F, -0x08},  // U+1F88..1F8F -> U+1F80..1F87 (alpha+iota sub)
    {0x1F98, 0x1F9F, -0x08},  // U+1F98..1F9F -> U+1F90..1F97 (eta+iota sub)
    {0x1FA8, 0x1FAF, -0x08},  // U+1FA8..1FAF -> U+1FA0..1FA7 (omega+iota sub)
    {0x1FB8, 0x1FB9, -0x08},  // U+1FB8..1FB9 -> U+1FB0..1FB1
    {0x1FBA, 0x1FBB, -0x4A},  // U+1FBA..1FBB -> U+1F70..1F71
    {0x1FC8, 0x1FCB, -0x56},  // U+1FC8..1FCB -> U+1F72..1F75
    {0x1FD8, 0x1FD9, -0x08},  // U+1FD8..1FD9 -> U+1FD0..1FD1
    {0x1FDA, 0x1FDB, -0x64},  // U+1FDA..1FDB -> U+1F76..1F77
    {0x1FE8, 0x1FE9, -0x08},  // U+1FE8..1FE9 -> U+1FE0..1FE1
    {0x1FEA, 0x1FEB, -0x72},  // U+1FEA..1FEB -> U+1F78..1F79
    {0x1FF8, 0x1FF9, -0x80},  // U+1FF8..1FF9 -> U+1F78..1F79
    {0x1FFA, 0x1FFB, -0x80},  // U+1FFA..1FFB -> U+1F7A..1F7B
};

// Paired ranges (Latin Extended-A structure): offsets from [lo] alternate
// upper (even) / lower (odd) — even entries fold with +1.
// Excluded neighbors: 0x130 İ (would need a combining dot — length change),
// 0x131 ı and 0x138 ķ / 0x149 ŉ (lowercase only), 0x17F ſ (would shorten).
// Latin Extended Additional: Vietnamese (0x1EA0-0x1EFF) is uniformly paired;
// 0x1E9E ẞ is excluded (its lowercase ß is 2-byte vs 3-byte — length change).
constexpr uint32_t kFoldPairs[][2] = {
    {0x0100, 0x012F},  // Ā-į (pairs)
    {0x0132, 0x0137},  // Ĳ-ķ (pairs)
    {0x0139, 0x0148},  // Ĺ-ņ (pairs)
    {0x014A, 0x0177},  // Ŋ-ŵ (pairs)
    {0x0179, 0x017E},  // Ź-ž (pairs)
    {0x1E00, 0x1E9D},  // Ḁ-ẝ (pairs; ẞ at 0x1E9E excluded — see above)
    {0x1EA0, 0x1EFF},  // ạ-ỿ Vietnamese (uniform pairs)
};

// One-off folds that do not fit a +delta shape. Source and target stay in
// the same UTF-8 byte-length class (2→2, 3→3).
constexpr uint32_t kFoldSingle[][2] = {
    {0x0178, 0x00FF},  // U+0178 -> U+00FF
    {0x1F59, 0x1F51},  // Greek Ext singles (no contiguous capital run)
    {0x1F5B, 0x1F53},
    {0x1F5D, 0x1F55},
    {0x1F5F, 0x1F57},
    {0x1FEC, 0x1FE5},  // U+1FEC -> U+1FE5 (rho with dasia)
};

uint32_t foldCp(uint32_t cp) {
  // +delta ranges (binary search).
  {
    size_t lo = 0, hi = sizeof(kFoldAdd) / sizeof(kFoldAdd[0]);
    while (lo < hi) {
      size_t mid = lo + (hi - lo) / 2;
      if (cp < kFoldAdd[mid].lo) {
        hi = mid;
      } else if (cp > kFoldAdd[mid].hi) {
        lo = mid + 1;
      } else {
        // Every table entry keeps the result a valid code point, so the
        // int64 hop is exact (no wraparound, no truncation).
        return uint32_t(int64_t(cp) + kFoldAdd[mid].delta);
      }
    }
  }
  // Paired ranges (few — linear is fine and branch-friendly).
  for (const auto& pair : kFoldPairs) {
    if (cp >= pair[0] && cp <= pair[1] && ((cp - pair[0]) & 1u) == 0) return cp + 1;
  }
  for (const auto& single : kFoldSingle) {
    if (cp == single[0]) return single[1];
  }
  return cp;  // not foldable (or length-changing fold — see header)
}

// UTF-8 sequence length from the lead byte; -1 when invalid (stray
// continuation, overlong C0/C1, F8+ lead).
int utf8SeqLen(unsigned char b0) {
  if (b0 >= 0xC2 && b0 <= 0xDF) return 2;
  if (b0 >= 0xE0 && b0 <= 0xEF) return 3;
  if (b0 >= 0xF0 && b0 <= 0xF4) return 4;
  return -1;
}

bool isCont(unsigned char b) { return (b & 0xC0) == 0x80; }

// Decode a known-length valid-lead sequence starting at in[i].
uint32_t decodeCp(std::string_view in, size_t i, int len) {
  const uint32_t b0 = static_cast<unsigned char>(in[i]);
  uint32_t cp = b0 & uint32_t(0xFF >> (len + 1));
  for (int k = 1; k < len; ++k) {
    cp = (cp << 6) | (static_cast<unsigned char>(in[i + size_t(k)]) & 0x3Fu);
  }
  return cp;
}

// UTF-8 encode [cp] into [out] (2-byte space only is ever needed here, but
// keep the full encoder for safety).
void appendUtf8(std::string* out, uint32_t cp) {
  if (cp < 0x80) {
    out->push_back(char(cp));
  } else if (cp < 0x800) {
    out->push_back(char(0xC0 | (cp >> 6)));
    out->push_back(char(0x80 | (cp & 0x3F)));
  } else if (cp < 0x10000) {
    out->push_back(char(0xE0 | (cp >> 12)));
    out->push_back(char(0x80 | ((cp >> 6) & 0x3F)));
    out->push_back(char(0x80 | (cp & 0x3F)));
  } else {
    out->push_back(char(0xF0 | (cp >> 18)));
    out->push_back(char(0x80 | ((cp >> 12) & 0x3F)));
    out->push_back(char(0x80 | ((cp >> 6) & 0x3F)));
    out->push_back(char(0x80 | (cp & 0x3F)));
  }
}

// If [byte] falls in the middle of a UTF-8 cluster, advance it to the next
// cluster start. extractText repeats a cluster's (row, col) for every byte
// of its encoding, so map[byte] == map[byte - 1] identifies a continuation
// byte. [mapSize] is the map length (== text length + 1); map[mapSize - 1]
// is the one-past-end entry and never aligns further.
static uint32_t alignForward(const uint32_t* map, size_t mapSize, size_t byte) {
  if (mapSize == 0) return uint32_t(byte);
  if (byte >= mapSize) byte = mapSize - 1;
  while (byte < mapSize - 1 && byte > 0 && map[byte] == map[byte - 1]) {
    ++byte;
  }
  return uint32_t(byte);
}

}  // namespace

// ─── SearchEngine ─────────────────────────────────────────────────────────

void SearchEngine::reset(const SearchQuery& q) {
  query_ = q;
  if (query_.maxHits < 0) query_.maxHits = 0;
  hits_.clear();
  nextLineId_ = 0;
  exhausted_ = false;

  pattern_.clear();
  if (query_.caseInsensitive) {
    foldUtf8(query_.pattern, &pattern_);
  } else {
    pattern_.assign(query_.pattern);
  }
  active_ = !pattern_.empty() && query_.maxHits > 0;
  if (!active_) return;
  buildFailTable();
}

void SearchEngine::addLine(std::string_view utf8, const uint32_t* map) {
  const int32_t lineId = nextLineId_++;  // every call consumes an id
  (void)lineId;
  if (!active_ || exhausted_ || map == nullptr) return;

  if (query_.caseInsensitive) {
    foldUtf8(utf8, &lineBuf_);
  } else {
    lineBuf_.assign(utf8);
  }
  matchLine(map, int(utf8.size()));
}

void SearchEngine::buildFailTable() {
  const int m = int(pattern_.size());
  fail_.assign(size_t(m), 0);
  int k = 0;  // longest proper prefix that is also a suffix of pattern[0..i]
  for (int i = 1; i < m; ++i) {
    while (k > 0 && pattern_[size_t(i)] != pattern_[size_t(k)]) {
      k = fail_[size_t(k - 1)];
    }
    if (pattern_[size_t(i)] == pattern_[size_t(k)]) ++k;
    fail_[size_t(i)] = k;
  }
}

void SearchEngine::matchLine(const uint32_t* map, int len) {
  const int m = int(pattern_.size());
  if (m == 0 || m > len) return;
  int k = 0;  // matched prefix length of the pattern
  for (int i = 0; i < len; ++i) {
    while (k > 0 && pattern_[size_t(k)] != lineBuf_[size_t(i)]) {
      k = fail_[size_t(k - 1)];
    }
    if (pattern_[size_t(k)] == lineBuf_[size_t(i)]) ++k;
    if (k == m) {
      recordHit(i - m + 1, i + 1, map, len);
      if (exhausted_) return;
      k = fail_[size_t(k - 1)];  // continue — overlapping hits allowed
    }
  }
}

void SearchEngine::recordHit(int startByte, int endByte, const uint32_t* map, int len) {
  if (query_.wholeWord) {
    // Line boundaries (start/end of the logical line) are word boundaries.
    if (startByte > 0 && isWordChar(lineBuf_[size_t(startByte - 1)])) return;
    if (endByte < len && isWordChar(lineBuf_[size_t(endByte)])) return;
  }
  const uint32_t s = map[size_t(startByte)];
  // Align the end to a cluster start: a hit that consumed part of a
  // multi-byte cluster owns the whole cell.
  const uint32_t alignedEnd = alignForward(map, size_t(len) + 1, size_t(endByte));
  const uint32_t e = map[alignedEnd];
  hits_.push_back(SearchHit{nextLineId_ - 1, int32_t(s >> 16), int32_t(s & 0xFFFFu),
                            int32_t(e >> 16), int32_t(e & 0xFFFFu)});
  if (int(hits_.size()) >= query_.maxHits) exhausted_ = true;
}

// ─── foldUtf8 ─────────────────────────────────────────────────────────────

void SearchEngine::foldUtf8(std::string_view in, std::string* out) {
  if (out == nullptr) return;
  out->clear();
  out->reserve(in.size());

  size_t i = 0;
  while (i < in.size()) {
    const unsigned char b0 = static_cast<unsigned char>(in[i]);
    if (b0 < 0x80) {
      // ASCII fast path.
      const unsigned char lower =
          (b0 >= 'A' && b0 <= 'Z') ? static_cast<unsigned char>(b0 + 0x20) : b0;
      out->push_back(char(lower));
      ++i;
      continue;
    }
    const int len = utf8SeqLen(b0);
    bool valid = len > 0 && (i + size_t(len)) <= in.size();
    if (valid) {
      for (int k = 1; k < len; ++k) {
        if (!isCont(static_cast<unsigned char>(in[i + size_t(k)]))) {
          valid = false;
          break;
        }
      }
    }
    if (!valid) {
      // Invalid / truncated sequence: copy the byte verbatim so the output
      // length always tracks the input (never reflow offsets).
      out->push_back(char(b0));
      ++i;
      continue;
    }
    const uint32_t cp = decodeCp(in, i, len);
    const uint32_t folded = foldCp(cp);
    if (folded == cp) {
      out->append(in.data() + i, size_t(len));  // unchanged — copy raw bytes
    } else {
      // Fold tables only map within one UTF-8 length class (2→2, 3→3), so
      // the re-encoded length always equals [len] — offsets stay 1:1.
      appendUtf8(out, folded);
    }
    i += size_t(len);
  }
}

}  // namespace apex::vt
