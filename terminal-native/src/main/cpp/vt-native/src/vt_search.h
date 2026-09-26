// apex-vt-search — Ctrl+F style text search over logical lines.
//
// Usage: reset(query) once, then addLine() once per logical line in order
// (screen + scrollback, oldest first). Each line's text and map come from
// lines::extractText — the text is pre-joined across wrap boundaries, so a
// pattern matching across a fold is just an ordinary match inside the line.
//
// Contract:
//  * SearchQuery.pattern holds the search text (raw case, UTF-8). NOTE: this
//    field is this module's own addition over the original task sketch — a
//    query needs its text and reset(const SearchQuery&) is the only input
//    channel; the option fields are unchanged.
//  * caseInsensitive: pattern and every line are folded with foldUtf8()
//    BEFORE matching. The fold is length-preserving, so byte offsets in the
//    folded line map 1:1 onto the caller's extractText() map.
//  * Matching is KMP over the (folded) bytes of each line, line by line —
//    hits never span two logical lines; overlapping hits ARE reported
//    (e.g. "aa" hits twice in "aaa").
//  * SearchHit:
//      start = map[startByte]            (inclusive — lead column for wide)
//      end   = map[alignToClusterStart(endByte)]
//    endByte is aligned to the next cluster start when it lands mid-cluster
//    (a hit that consumes part of a multi-byte cluster owns the whole cell);
//    map[len] is the one-past-end entry, so a hit running to the line end
//    ends at (lastRow, colPastLastCell).
//  * wholeWord: the byte before the hit and the byte after must not be an
//    ASCII word char ([A-Za-z0-9_] — see isWordChar). Line start/end counts
//    as a word boundary (logical lines are the search unit).
//  * maxHits bounds hits().size(): once reached, matching stops but
//    addLine() keeps counting lineIds (lineId = addLine call ordinal,
//    0-based) so the engine stays reusable against the same line stream.
//    maxHits <= 0 → no hits.
//  * addLine() with a null map counts the line but skips matching (no
//    coordinates could be produced). map must otherwise follow the
//    extractText contract: map.size() == utf8.size() + 1.
//  * Performance: fold and match reuse member buffers — no per-character
//    or per-hit allocation on the steady path.
#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace apex::vt {

struct SearchQuery {
  std::string pattern;         // search text (UTF-8, raw case) — see header NOTE
  bool caseInsensitive = true;
  bool wholeWord = false;      // ASCII word chars [A-Za-z0-9_] boundary check
  int maxHits = 2000;          // bounded hit count
};

struct SearchHit {
  int32_t lineId = 0;          // addLine ordinal (0-based)
  int32_t startRow = 0;        // row-in-line + cell column (start inclusive)
  int32_t startCol = 0;
  int32_t endRow = 0;          // one-past-end
  int32_t endCol = 0;
};

class SearchEngine {
 public:
  // Install a query and clear all state. Reusable across queries.
  void reset(const SearchQuery& q);

  // Feed one logical line (in order). map per lines::extractText contract.
  void addLine(std::string_view utf8, const uint32_t* map);

  const std::vector<SearchHit>& hits() const { return hits_; }

  // Length-preserving simple UTF-8 case fold (lowercase direction):
  //   ASCII A-Z; Latin-1 À-Ö/Ø-Þ (ß kept — it would fold to "ss", changing
  //   length and shifting every later offset); Latin Extended-A pairs
  //   (İ/ı/ķ/ŉ/ſ excluded — İ and ſ would change length); Ÿ→ÿ; Greek
  //   (incl. ά..ώ accents and ς→σ); Greek Extended polytonic (U+1F08..
  //   U+1FFB families, e.g. U+1F08→U+1F00, U+1FEC→U+1FE5); Cyrillic
  //   Ѐ-Я; Armenian Ա-Ֆ; Latin Extended Additional pairs (Vietnamese,
  //   ẞ excluded — length change).
  // Length-changing folds are intentionally NOT applied so folded offsets
  // stay 1:1 with the input bytes. Invalid/truncated UTF-8 is copied
  // verbatim (robustness — never throws, never reflows offsets).
  static void foldUtf8(std::string_view in, std::string* out);

  // ASCII word character for wholeWord boundaries.
  static bool isWordChar(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
           (c >= '0' && c <= '9') || c == '_';
  }

 private:
  void buildFailTable();
  void matchLine(const uint32_t* map, int len);
  void recordHit(int startByte, int endByte, const uint32_t* map, int len);

  SearchQuery query_;          // normalized copy (maxHits floored at 0)
  std::string pattern_;        // folded pattern bytes
  std::vector<int> fail_;      // KMP failure table for pattern_
  std::string lineBuf_;        // folded current line (reused)
  std::vector<SearchHit> hits_;
  int32_t nextLineId_ = 0;
  bool active_ = false;        // non-empty pattern and maxHits > 0
  bool exhausted_ = false;     // maxHits reached
};

}  // namespace apex::vt
