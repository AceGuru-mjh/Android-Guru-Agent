// apex-vt-parser — incremental VT parser state machine.
//
// Exact semantic port of the Kotlin VtParser (Spec §2/§5 PR #53, incl. T82/T85
// hardening): 13 states, cross-read-boundary sequence buffering, bounded
// CSI/OSC/DCS buffers, colon sub-params, C1 (8-bit) control compatibility.
//
// Host contract (Host is a template parameter — static dispatch, no vtables):
//   void onPrintable(uint32_t cp);
//   void onC0(uint32_t byte);
//   void onCsi(const CsiSequence& seq);
//   void onOsc(int32_t code, const std::vector<char16_t>& data);
//   void onEsc(char final, const char* intermediates, size_t nIntermediates);
//   void onDcs(const std::vector<char16_t>& data);
//   void onUnknown();
//
// Performance contract: no allocation for ordinary sequences — all buffers are
// member state reused across feeds (amortized zero-alloc). Bounded worst case
// (MAX_CSI_BUFFER_LENGTH / MAX_STRING_SEQUENCE_LENGTH) matches Kotlin.
#pragma once

#include "vt_types.h"

#include <cstdint>
#include <string>
#include <vector>

namespace apex::vt {

// CSI sequence — parity with Kotlin VtParser.CSISequence.
struct CsiSequence {
  char privateMarker = 0;  // 0 = none ('?', '<', '=', '>')
  std::vector<int32_t> params;  // token first values; empty/overflow token → 0
  std::vector<int32_t> subPool;  // flat pool of sub-param values
  std::vector<uint32_t> subOff;  // per token: offset into subPool
  std::vector<uint32_t> subLen;  // per token: 0 = plain token (no ':')
  std::string intermediates;
  char finalByte = 0;

  int32_t param(int idx, int32_t def = 0) const {
    return idx >= 0 && size_t(idx) < params.size() ? params[size_t(idx)] : def;
  }
  // xterm semantics: explicit 0 counts as "default".
  int32_t paramOrDefault(int idx, int32_t def) const {
    return idx >= 0 && size_t(idx) < params.size() && params[size_t(idx)] != 0
               ? params[size_t(idx)]
               : def;
  }
  bool hasSubParams(int idx) const {
    return idx >= 0 && size_t(idx) < subLen.size() && subLen[size_t(idx)] > 0;
  }
  const int32_t* subParams(int idx, uint32_t& n) const {
    n = subLen[size_t(idx)];
    return &subPool[subOff[size_t(idx)]];
  }
};

template <typename Host>
class Parser {
 public:
  // Robustness bounds — parity with Kotlin constants.
  static constexpr size_t kMaxStringSequenceLength = 100000;
  static constexpr size_t kMaxCsiBufferLength = 4096;

  void feed(uint32_t cp, Host& host) {
    switch (state_) {
      case State::kGround: handleGround(cp, host); break;
      case State::kEscape: handleEscape(cp, host); break;
      case State::kEscIntermediate: handleEscIntermediate(cp, host); break;
      case State::kCsiEntry: handleCsiEntry(cp, host); break;
      case State::kCsiParam: handleCsiParam(cp, host); break;
      case State::kCsiIntermediate: handleCsiIntermediate(cp, host); break;
      case State::kCsiIgnore: handleCsiIgnore(cp); break;
      case State::kOscString: handleOscString(cp, host); break;
      case State::kOscEsc: handleOscEsc(cp, host); break;
      case State::kDcsEntry: handleDcsEntry(cp, host); break;
      case State::kDcsString: handleDcsString(cp, host); break;
      case State::kDcsEsc: handleDcsEsc(cp, host); break;
      case State::kStringIgnore: handleStringIgnore(cp); break;
    }
  }

  bool inGround() const { return state_ == State::kGround; }

  void reset() {
    state_ = State::kGround;
    csiParams_.clear();
    csiIntermediates_.clear();
    stringBuf_.clear();
    privateMarker_ = 0;
  }

 private:
  enum class State {
    kGround, kEscape, kEscIntermediate, kCsiEntry, kCsiParam, kCsiIntermediate,
    kCsiIgnore, kOscString, kOscEsc, kDcsEntry, kDcsString, kDcsEsc, kStringIgnore,
  };

  // ─── GROUND ────────────────────────────────────────────────────────────
  void handleGround(uint32_t cp, Host& host) {
    if (cp == 0x1B) {
      state_ = State::kEscape;
    } else if (cp < 0x20) {
      host.onC0(cp);
    } else if (cp == 0x7F) {
      host.onC0(0x7F);
    } else if (cp >= 0x80 && cp <= 0x9F) {
      handleC1(cp, host);
    } else {
      host.onPrintable(cp);
    }
  }

  // T85: single-byte C1 controls → 7-bit equivalents (xterm C1 compatibility).
  void handleC1(uint32_t cp, Host& host) {
    switch (cp) {
      case 0x9B:  // CSI (8-bit)
        csiParams_.clear();
        csiIntermediates_.clear();
        privateMarker_ = 0;
        state_ = State::kCsiEntry;
        break;
      case 0x90:  // DCS
        stringBuf_.clear();
        state_ = State::kDcsEntry;
        break;
      case 0x9D:  // OSC
        stringBuf_.clear();
        state_ = State::kOscString;
        break;
      case 0x98:
      case 0x9E:
      case 0x9F:  // SOS/PM/APC
        state_ = State::kStringIgnore;
        break;
      case 0x84: host.onEsc('D', nullptr, 0); break;  // IND
      case 0x85: host.onEsc('E', nullptr, 0); break;  // NEL
      case 0x8D: host.onEsc('M', nullptr, 0); break;  // RI
      case 0x88: host.onEsc('H', nullptr, 0); break;  // HTS
      default: host.onUnknown(); break;  // other C1 — safely ignored
    }
  }

  // ─── ESCAPE ────────────────────────────────────────────────────────────
  void handleEscape(uint32_t cp, Host& host) {
    if (cp == '[') {
      csiParams_.clear();
      csiIntermediates_.clear();
      privateMarker_ = 0;
      state_ = State::kCsiEntry;
    } else if (cp == ']') {
      stringBuf_.clear();
      state_ = State::kOscString;
    } else if (cp == 'P') {
      stringBuf_.clear();
      state_ = State::kDcsEntry;
    } else if (cp >= 0x20 && cp <= 0x2F) {
      csiIntermediates_.push_back(char(cp));
      state_ = State::kEscIntermediate;
    } else if (cp >= 0x30 && cp <= 0x7E) {
      host.onEsc(char(cp), nullptr, 0);
      state_ = State::kGround;
    } else if (cp == 0x1B) {
      host.onUnknown();
      state_ = State::kEscape;  // ESC ESC → restart
    } else {
      host.onUnknown();
      state_ = State::kGround;
    }
  }

  void handleEscIntermediate(uint32_t cp, Host& host) {
    if (cp >= 0x30 && cp <= 0x7E) {
      host.onEsc(char(cp), csiIntermediates_.c_str(), csiIntermediates_.size());
      csiIntermediates_.clear();
      state_ = State::kGround;
    } else if (cp >= 0x20 && cp <= 0x2F) {
      csiIntermediates_.push_back(char(cp));
    } else {
      state_ = State::kGround;
    }
  }

  // ─── CSI ───────────────────────────────────────────────────────────────
  void handleCsiEntry(uint32_t cp, Host& host) {
    if (cp == '?' || cp == '<' || cp == '=' || cp == '>') {
      privateMarker_ = char(cp);
      state_ = State::kCsiParam;
    } else if (cp >= 0x30 && cp <= 0x39) {  // digit
      appendCsiParam(char(cp));
      state_ = State::kCsiParam;
    } else if (cp == ';') {
      appendCsiParam(';');
      state_ = State::kCsiParam;
    } else if (cp == ':') {  // T85: colon sub-param separator
      appendCsiParam(':');
      state_ = State::kCsiParam;
    } else if (cp >= 0x20 && cp <= 0x2F) {
      appendCsiIntermediate(char(cp));
      state_ = State::kCsiIntermediate;
    } else if (cp >= 0x40 && cp <= 0x7E) {
      emitCsi(char(cp), host);
      state_ = State::kGround;
    } else {
      state_ = State::kCsiIgnore;
    }
  }

  void handleCsiParam(uint32_t cp, Host& host) {
    if (cp >= 0x30 && cp <= 0x39) {
      appendCsiParam(char(cp));
    } else if (cp == ';' || cp == ':') {
      appendCsiParam(char(cp));
    } else if (cp >= 0x20 && cp <= 0x2F) {
      appendCsiIntermediate(char(cp));
      state_ = State::kCsiIntermediate;
    } else if (cp >= 0x40 && cp <= 0x7E) {
      emitCsi(char(cp), host);
      state_ = State::kGround;
    } else {
      state_ = State::kCsiIgnore;
    }
  }

  void handleCsiIntermediate(uint32_t cp, Host& host) {
    if (cp >= 0x20 && cp <= 0x2F) {
      appendCsiIntermediate(char(cp));
    } else if (cp >= 0x40 && cp <= 0x7E) {
      emitCsi(char(cp), host);
      state_ = State::kGround;
    } else {
      state_ = State::kCsiIgnore;
    }
  }

  void handleCsiIgnore(uint32_t cp) {
    if (cp >= 0x40 && cp <= 0x7E) state_ = State::kGround;
  }

  // P1: overflow → discard and switch to CsiIgnore (parity with Kotlin).
  void appendCsiParam(char ch) {
    if (csiParams_.size() >= kMaxCsiBufferLength) {
      csiParams_.clear();
      csiIntermediates_.clear();
      state_ = State::kCsiIgnore;
    } else {
      csiParams_.push_back(ch);
    }
  }

  void appendCsiIntermediate(char ch) {
    if (csiIntermediates_.size() >= kMaxCsiBufferLength) {
      csiParams_.clear();
      csiIntermediates_.clear();
      state_ = State::kCsiIgnore;
    } else {
      csiIntermediates_.push_back(ch);
    }
  }

  // ─── OSC / DCS strings ─────────────────────────────────────────────────
  void handleOscString(uint32_t cp, Host& host) {
    if (cp == 0x07) {
      emitOsc(host);
      state_ = State::kGround;  // BEL terminates OSC
    } else if (cp == 0x1B) {
      state_ = State::kOscEsc;  // ESC begins ST (ESC \)
    } else {
      appendString(char16_t(cp));  // Kotlin parity: truncated to 16 bits
    }
  }

  void handleOscEsc(uint32_t cp, Host& host) {
    if (cp == '\\') {
      emitOsc(host);
      state_ = State::kGround;  // ST terminates OSC
    } else {
      emitOsc(host);
      state_ = State::kEscape;
      handleEscape(cp, host);  // ESC starts a new control
    }
  }

  void handleDcsEntry(uint32_t cp, Host& host) {
    (void)host;
    if (cp == 0x1B) {
      state_ = State::kStringIgnore;
    } else {
      stringBuf_.push_back(char16_t(cp));
      state_ = State::kDcsString;
    }
  }

  void handleDcsString(uint32_t cp, Host& host) {
    (void)host;
    if (cp == 0x1B) {
      state_ = State::kDcsEsc;  // ESC begins ST (ESC \)
    } else {
      appendString(char16_t(cp));
    }
  }

  void handleDcsEsc(uint32_t cp, Host& host) {
    if (cp == '\\') {
      host.onDcs(stringBuf_);
      stringBuf_.clear();
      state_ = State::kGround;
    } else {
      host.onDcs(stringBuf_);
      stringBuf_.clear();
      state_ = State::kEscape;
      handleEscape(cp, host);
    }
  }

  void appendString(char16_t ch) {
    if (stringBuf_.size() >= kMaxStringSequenceLength) {
      stringBuf_.clear();
      state_ = State::kStringIgnore;
    } else {
      stringBuf_.push_back(ch);
    }
  }

  void handleStringIgnore(uint32_t cp) {
    if (cp == '\\') state_ = State::kGround;  // ST ends the (discarded) string
  }

  // ─── emitters ──────────────────────────────────────────────────────────
  void emitCsi(char final, Host& host) {
    csi_.privateMarker = privateMarker_;
    csi_.intermediates = csiIntermediates_;
    csi_.finalByte = final;
    parseParams(csiParams_, csi_);
    host.onCsi(csi_);
  }

  void emitOsc(Host& host) {
    // code = token before ';' (invalid/missing → -1); data = remainder.
    int32_t code = -1;
    size_t semi = 0;
    bool hasSemi = false;
    for (; semi < stringBuf_.size(); ++semi) {
      if (stringBuf_[semi] == ';') {
        hasSemi = true;
        break;
      }
    }
    size_t codeEnd = hasSemi ? semi : stringBuf_.size();
    code = parseOscCode(stringBuf_.data(), codeEnd);
    oscData_.assign(stringBuf_.begin() + (hasSemi ? semi + 1 : stringBuf_.size()),
                    stringBuf_.end());
    host.onOsc(code, oscData_);
    stringBuf_.clear();
  }

  static int32_t parseOscCode(const char16_t* s, size_t n) {
    if (n == 0 || n > 10) return -1;  // Kotlin toIntOrNull: null on empty/overflow-ish
    int64_t v = 0;
    for (size_t i = 0; i < n; ++i) {
      char16_t c = s[i];
      if (c < '0' || c > '9') return -1;  // non-digit → null → -1
      v = v * 10 + (c - '0');
      if (v > 0x7FFFFFFF) return -1;  // overflow → null → -1
    }
    return int32_t(v);
  }

  // Kotlin parseParams parity: tokens split by ';'; a token containing ':' has
  // its full sub-sequence recorded (incl. first value); empty/overflow → 0.
  static void parseParams(const std::string& raw, CsiSequence& out) {
    out.params.clear();
    out.subPool.clear();
    out.subOff.clear();
    out.subLen.clear();
    if (raw.empty()) return;
    size_t start = 0;
    while (true) {
      size_t semi = raw.find(';', start);
      size_t end = (semi != std::string::npos) ? semi : raw.size();
      size_t colon = raw.find(':', start);
      bool hasColon = (colon != std::string::npos && colon < end);
      if (!hasColon) {
        // Plain token (possibly empty) — no colon within [start, end).
        out.params.push_back(parseToken(raw.data() + start, end - start));
        out.subOff.push_back(0);
        out.subLen.push_back(0);
      } else {
        // Colon token: split by ':' — the full sequence (incl. first value)
        // goes into the sub pool; params gets the first value.
        uint32_t off = uint32_t(out.subPool.size());
        uint32_t n = 0;
        size_t s2 = start;
        while (true) {
          size_t c2 = raw.find(':', s2);
          size_t e2 = (c2 == std::string::npos || c2 >= end) ? end : c2;
          out.subPool.push_back(parseToken(raw.data() + s2, e2 - s2));
          ++n;
          if (e2 >= end) break;
          s2 = e2 + 1;
        }
        out.params.push_back(n > 0 ? out.subPool[off] : 0);
        out.subOff.push_back(off);
        out.subLen.push_back(n);
      }
      if (semi == std::string::npos) break;
      start = semi + 1;
    }
  }

  // Kotlin toIntOrNull() ?: 0 — empty or overflow → 0.
  static int32_t parseToken(const char* s, size_t n) {
    if (n == 0 || n > 10) return 0;
    int64_t v = 0;
    for (size_t i = 0; i < n; ++i) {
      char c = s[i];
      if (c < '0' || c > '9') return 0;
      v = v * 10 + (c - '0');
      if (v > 0x7FFFFFFF) return 0;
    }
    return int32_t(v);
  }

  State state_ = State::kGround;
  std::string csiParams_;        // raw param chars, reused
  std::string csiIntermediates_; // raw intermediates, reused
  std::vector<char16_t> stringBuf_;  // OSC/DCS string buffer, reused
  char privateMarker_ = 0;
  CsiSequence csi_;  // reused emission scratch
  std::vector<char16_t> oscData_;  // reused OSC payload scratch
};

}  // namespace apex::vt
