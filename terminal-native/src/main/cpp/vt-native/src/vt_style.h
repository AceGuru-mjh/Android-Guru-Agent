// apex-vt-style — style interning table.
//
// Every distinct Style in play gets a 16-bit id; cells reference ids. The table
// is bounded (kMaxStyles). If a pathological stream produces more distinct
// styles than the cap, new styles degrade to id 0 (default) — memory stays
// bounded and the stream keeps rendering (documented, practically unreachable).
#pragma once

#include "vt_types.h"

#include <cstddef>
#include <vector>

namespace apex::vt {

class StyleTable {
 public:
  static constexpr uint16_t kMaxStyles = 4096;

  StyleTable();

  // Returns the interned id for [s]. Id 0 is always the DEFAULT style.
  uint16_t intern(const Style& s);

  const Style& get(uint16_t id) const { return styles_[id]; }
  uint16_t defaultId() const { return 0; }
  size_t size() const { return styles_.size(); }

 private:
  static uint64_t hashStyle(const Style& s);

  std::vector<Style> styles_;    // id → style (id 0 = default)
  std::vector<uint32_t> slots_;  // open addressing: slot = styleId + 1, 0 = empty
  size_t mask_ = 0;
};

}  // namespace apex::vt
