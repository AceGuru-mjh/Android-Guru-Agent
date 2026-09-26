// apex-vt-grapheme — UAX #29 extended grapheme cluster boundaries (impl).
//
// Rule set (UAX #29 15.1) as implemented in feed(), evaluated in order:
//   GB1  sot ÷                          — first feed() always a boundary
//   GB2  ÷ eot                          — caller-side (end of sequence)
//   GB3  CR × LF
//   GB4  (Control|CR|LF) ÷
//   GB5  ÷ (Control|CR|LF)
//   GB6  L × (L|V|LV|LVT)
//   GB7  (LV|V) × (V|T)
//   GB8  (LVT|T) × T
//   GB9  × (Extend|ZWJ)
//   GB9a × SpacingMark
//   GB9b Prepend ×
//   GB11 ExtPict Extend* ZWJ × ExtPict  (needs the pictChain_ history)
//   GB12/13 (RI RI)* RI × RI            (odd preceding run joins, needs
//                                        the riRun_ parity)
//   GB999 break
// GB9c (Indic conjuncts, 15.1) and the "GB10" emoji-modifier rule are
// omitted deliberately — see the header comment for the rationale.
#include "vt_grapheme.h"

#include <cstddef>

namespace apex::vt {

namespace {

using uni::Gc;

// Core UAX #29 pair decision given the GB11 / GB12-13 context flags.
// pictChainArm: prev is ZWJ terminating a valid ExtPict Extend* chain.
// riOddRun:     an odd number of RIs immediately precedes (pair open).
inline bool breakPair(Gc prev, Gc next, bool pictChainArm, bool riOddRun) {
  if (prev == Gc::kCr && next == Gc::kLf) return false;               // GB3
  if (prev == Gc::kControl || prev == Gc::kCr || prev == Gc::kLf) {
    return true;                                                       // GB4
  }
  if (next == Gc::kControl || next == Gc::kCr || next == Gc::kLf) {
    return true;                                                       // GB5
  }
  if (prev == Gc::kL &&
      (next == Gc::kL || next == Gc::kV || next == Gc::kLv ||
       next == Gc::kLvt)) {
    return false;                                                      // GB6
  }
  if ((prev == Gc::kLv || prev == Gc::kV) &&
      (next == Gc::kV || next == Gc::kT)) {
    return false;                                                      // GB7
  }
  if ((prev == Gc::kLvt || prev == Gc::kT) && next == Gc::kT) {
    return false;                                                      // GB8
  }
  if (next == Gc::kExtend || next == Gc::kZwj) return false;           // GB9
  if (next == Gc::kSpacingMark) return false;                          // GB9a
  if (prev == Gc::kPrepend) return false;                              // GB9b
  if (prev == Gc::kZwj && pictChainArm) return false;                  // GB11
  if (prev == Gc::kRegionalIndicator && next == Gc::kRegionalIndicator &&
      riOddRun) {
    return false;                                              // GB12/GB13
  }
  return true;                                                         // GB999
}

// State update shared by feed() (and mirrored by isBreak's assumptions):
// maintains the GB11 chain and the GB12/13 RI run count AFTER cp joined.
inline void advanceState(uint32_t cp, Gc cl, uint8_t& pictChain,
                          uint32_t& riRun) {
  if (cl == Gc::kExtend) {
    // Extend* continues a chain that started with an ExtPict; an Extend
    // after the ZWJ disarms the chain (Extend* only precedes the ZWJ).
    pictChain = (pictChain == 1) ? 1 : 0;
  } else if (cl == Gc::kZwj) {
    pictChain = (pictChain == 1) ? 2 : 0;
  } else if (uni::isExtendedPictographic(cp)) {
    pictChain = 1;  // fresh chain start (skin tones are Extend, not here)
  } else {
    pictChain = 0;
  }
  riRun = (cl == Gc::kRegionalIndicator) ? (riRun + 1) : 0;
}

}  // namespace

bool GraphemeBreaker::feed(uint32_t cp) {
  const Gc next = uni::graphemeClass(cp);

  bool brk;
  if (first_) {
    brk = true;                                                        // GB1
  } else {
    // GB11: only arms when the NEXT char is also Extended_Pictographic —
    // ZWJ before plain text must break (GB999).
    const bool pictChainArm =
        prevClass_ == Gc::kZwj && pictChain_ == 2 &&
        next == Gc::kExtendedPictographic;
    const bool riOddRun = (riRun_ % 2) == 1;
    brk = breakPair(prevClass_, next, pictChainArm, riOddRun);
  }

  advanceState(cp, next, pictChain_, riRun_);
  prevClass_ = next;
  first_ = false;
  return brk;
}

void GraphemeBreaker::reset() {
  pictChain_ = 0;
  riRun_ = 0;
  prevClass_ = Gc::kOther;
  first_ = true;
}

// static
bool GraphemeBreaker::isBreak(uint32_t prev, uint32_t next) {
  const Gc p = uni::graphemeClass(prev);
  const Gc n = uni::graphemeClass(next);
  // History-free approximations (documented in the header):
  //  * a RI pair is assumed to be a fresh flag → no break;
  //  * ZWJ before an ExtPict is assumed to end a valid GB11 chain → no
  //    break. feed() is authoritative when history matters.
  const bool pictChainArm = (p == Gc::kZwj) && uni::isExtendedPictographic(next);
  return breakPair(p, n, pictChainArm, /*riOddRun=*/true);
}

// static
void GraphemeBreaker::markStarts(const uint32_t* cps, size_t n,
                                 uint8_t* starts) {
  if (n == 0 || cps == nullptr || starts == nullptr) return;
  GraphemeBreaker b;
  starts[0] = 1;                                                       // GB1
  b.feed(cps[0]);  // seed the state with the first code point
  for (size_t i = 1; i < n; ++i) {
    starts[i] = b.feed(cps[i]) ? 1 : 0;
  }
}

// static
void GraphemeBreaker::markCellStarts(const Cell* cells, int n,
                                     uint8_t* starts) {
  if (n <= 0 || cells == nullptr || starts == nullptr) return;
  GraphemeBreaker b;
  bool first = true;
  for (int i = 0; i < n; ++i) {
    if (cells[i].flags & kCellWideTrail) {
      starts[i] = 0;  // second column of the wide cell at i-1
      continue;
    }
    if (first) {
      starts[i] = 1;                                                  // GB1
      b.feed(cells[i].cp);
      first = false;
    } else {
      starts[i] = b.feed(cells[i].cp) ? 1 : 0;
    }
  }
}

}  // namespace apex::vt
