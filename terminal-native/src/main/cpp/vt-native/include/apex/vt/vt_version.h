// apex-vt-native — C++17 zero-allocation VT/ANSI terminal emulation core.
//
// Version macros — single source of truth for the library ABI/feature version.
#pragma once

#define APEX_VT_VERSION_MAJOR 0
#define APEX_VT_VERSION_MINOR 1
#define APEX_VT_VERSION_PATCH 0

#define APEX_VT_STRINGIFY_(x) #x
#define APEX_VT_STRINGIFY(x) APEX_VT_STRINGIFY_(x)
#define APEX_VT_VERSION_STRING \
  APEX_VT_STRINGIFY(APEX_VT_VERSION_MAJOR) "." APEX_VT_STRINGIFY(APEX_VT_VERSION_MINOR) "." \
  APEX_VT_STRINGIFY(APEX_VT_VERSION_PATCH)

namespace apex::vt {
inline constexpr int kVersionMajor = APEX_VT_VERSION_MAJOR;
inline constexpr int kVersionMinor = APEX_VT_VERSION_MINOR;
inline constexpr int kVersionPatch = APEX_VT_VERSION_PATCH;
}  // namespace apex::vt
