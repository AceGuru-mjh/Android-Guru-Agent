#include "vt_style.h"

namespace apex::vt {

namespace {
constexpr uint64_t kFnvOffset = 1469598103934665603ull;
constexpr uint64_t kFnvPrime = 1099511628211ull;

// FNV-1a over the POD bytes of the style (fg, bg, attr — 12 bytes incl. padding).
uint64_t fnvBytes(const uint8_t* p, size_t n, uint64_t h) {
  for (size_t i = 0; i < n; ++i) {
    h ^= p[i];
    h *= kFnvPrime;
  }
  return h;
}
}  // namespace

StyleTable::StyleTable() {
  styles_.reserve(64);
  styles_.push_back(kDefaultStyle);  // id 0 = DEFAULT — always present.
  size_t cap = 256;
  slots_.assign(cap, 0);
  mask_ = cap - 1;
  slots_[hashStyle(kDefaultStyle) & mask_] = 1;  // id + 1
}

uint64_t StyleTable::hashStyle(const Style& s) {
  static_assert(sizeof(Style) == 12, "unexpected Style padding");
  return fnvBytes(reinterpret_cast<const uint8_t*>(&s), sizeof(Style), kFnvOffset);
}

uint16_t StyleTable::intern(const Style& s) {
  const uint64_t h = hashStyle(s);
  size_t slot = h & mask_;
  // Linear probe — table load is kept < 0.7 via rehash.
  while (slots_[slot] != 0) {
    uint16_t id = static_cast<uint16_t>(slots_[slot] - 1);
    if (styles_[id] == s) return id;
    slot = (slot + 1) & mask_;
  }
  // Empty slot → new style.
  if (styles_.size() >= kMaxStyles) {
    // Degenerate but bounded: map overflow styles to DEFAULT (see header).
    // (The slot is left empty — the DEFAULT id remains reachable via its own slot.)
    return defaultId();
  }
  uint16_t id = static_cast<uint16_t>(styles_.size());
  styles_.push_back(s);
  slots_[slot] = id + 1;
  // Rehash at 70% load.
  if (styles_.size() * 10 >= slots_.size() * 7) {
    size_t cap = slots_.size() * 2;
    slots_.assign(cap, 0);
    mask_ = cap - 1;
    for (size_t i = 0; i < styles_.size(); ++i) {
      size_t s2 = hashStyle(styles_[i]) & mask_;
      while (slots_[s2] != 0) s2 = (s2 + 1) & mask_;
      slots_[s2] = static_cast<uint32_t>(i + 1);
    }
  }
  return id;
}

}  // namespace apex::vt
