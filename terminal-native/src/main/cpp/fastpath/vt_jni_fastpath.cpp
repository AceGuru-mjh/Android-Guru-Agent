// apex-vt-fastpath JNI binding — host-maintained addition to libvt_native.so.
//
// One new symbol (T95): NativeVtCore.nativeRunSnapshotCells — the run-collapsed
// render snapshot (see vt_runs.h for the flat layout). The vendored JNI bridge
// (src/main/cpp/vt-native/src/jni/vt_jni.cpp) stays byte-identical to upstream;
// this file is compiled into the SAME shared object via target_sources() from
// the module-level CMakeLists.txt (see VENDOR.md "host-maintained" rules).
//
// Symbol binding is static (Kotlin external fun ↔ JNI name) — keep in sync
// with com.apex.agent.vtnative.NativeVtCore.nativeRunSnapshotCells.
#include <jni.h>

#include <vector>

#include "vt_runs.h"

using apex::vt::Engine;
using apex::vt::fastpath::buildRunSnapshotFlat;

extern "C" {

// Flat int array: [32 header ints, visible run rows, scrollback run rows] —
// header identical to nativeSnapshotCells; rows carry run tuples
// [colStart, colSpan, fg, bg, flags, link, textLen, text UTF-16 units].
// Returns null only for a dead handle (0) — the Kotlin wrapper then falls
// back to the v0.2 cell path.
JNIEXPORT jintArray JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeRunSnapshotCells(
    JNIEnv* env, jclass, jlong handle, jint maxScrollbackLines) {
  Engine* engine = reinterpret_cast<Engine*>(handle);
  if (engine == nullptr) return nullptr;

  std::vector<int32_t> flat;
  buildRunSnapshotFlat(*engine, int(maxScrollbackLines), flat);

  // jint is int32_t on every ABI this module ships (arm64-v8a / armeabi-v7a /
  // x86_64); the reinterpret is layout-exact and copies once via JNI.
  jintArray arr = env->NewIntArray(jsize(flat.size()));
  if (arr == nullptr) return nullptr;  // OOM — pending exception already set
  if (!flat.empty()) {
    env->SetIntArrayRegion(arr, 0, jsize(flat.size()),
                           reinterpret_cast<const jint*>(flat.data()));
  }
  return arr;
}

}  // extern "C"
