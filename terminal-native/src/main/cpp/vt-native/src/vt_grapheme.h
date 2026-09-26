// apex-vt-grapheme — UAX #29 extended grapheme cluster boundaries.
//
// GraphemeBreaker implements the UAX #29 (15.1) boundary rules GB1..GB13
// over a stream of code points: cursor motion, selection and clipboard
// copy must step cluster-by-cluster, not code-point-by-code-point, on a
// mobile terminal (emoji ZWJ families, combining marks, Hangul jamo,
// flag pairs). Classes come from uni::graphemeClass().
//
// Omitted by design (documented deviations, both harmless for terminals):
//  * GB9c (Indic InCB linker/consonant conjuncts, new in 15.1) — conjuncts
//    still break into base + combining marks, which renders identically;
//  * the emoji-modifier rule number the task calls "GB10" — emoji modifiers
//    (skin tones) are Extend-class, so GB9 already keeps them glued to the
//    emoji base.
//
// Contract: no allocation, no exceptions; O(1) state per fed code point.
#pragma once

#include "vt_types.h"          // internal Cell (kCellWideLead/Trail)
#include "vt_unicode_data.h"   // Gc + classification

#include <cstddef>
#include <cstdint>

namespace apex::vt {

class GraphemeBreaker {
 public:
  // Feeds one code point. Returns true when a cluster boundary sits BEFORE
  // cp (cp starts a new cluster). Stateful: tracks the previous class, the
  // GB11 ExtPict→Extend*→ZWJ chain and the GB12/13 regional-indicator
  // run parity.
  bool feed(uint32_t cp);

  // Clears all state; the breaker is then ready for a new sequence.
  void reset();

  // Stateless pair test (GB3..GB999 without run history): RI followed by RI
  // reports "no break" (assumes the pair is a fresh flag) and ZWJ followed
  // by an ExtPict reports "no break" (assumes a valid GB11 chain). Use
  // feed() when the full parity/chain behavior matters.
  static bool isBreak(uint32_t prev, uint32_t next);

  // Marks cluster starts over a code-point array: starts[i] != 0 ⇔ cps[i]
  // begins a cluster (starts[0] is always 1 per GB1).
  static void markStarts(const uint32_t* cps, size_t n, uint8_t* starts);

  // Marks cluster starts over a row of cells: wide trails never start a
  // cluster (they are the second column of the preceding lead cell);
  // every other cell participates in a normal GB decision on its code
  // point. Combining marks parked in Screen's RowExtras side table are not
  // visible here — they belong to the previous cell's cluster by
  // construction.
  static void markCellStarts(const Cell* cells, int n, uint8_t* starts);

 private:
  // GB11 pictographic chain state:
  // 0 = none, 1 = ExtPict (chain start) [+ Extend*], 2 = ... + ZWJ armed.
  uint8_t pictChain_ = 0;
  // Count of consecutive regional indicators ending at the previous code
  // point (GB12/GB13 parity source).
  uint32_t riRun_ = 0;
  uni::Gc prevClass_ = uni::Gc::kOther;
  bool first_ = true;
};

}  // namespace apex::vt
