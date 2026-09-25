// apex-vt-jni — Android JNI bridge for the VT engine (libvt_native.so).
//
// Kotlin side: com.apex.agent.vtnative.NativeVtCore (terminal-native module
// of Android-Guru-Agent). Method names are static-bound — keep in sync.
//
// Design notes:
//  * feed() uses GetPrimitiveArrayCritical — no other JNI calls happen while
//    the engine runs, so the critical section is short and legal.
//  * Snapshots are transferred as flat int arrays (one JNI call per region)
//    so the JVM can build its object model without per-cell native calls.
//  * Strings travel as UTF-16 via NewString (NOT NewStringUTF — modified
//    UTF-8 breaks supplementary code points such as emoji).
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "apex/vt/vt_engine.h"

using apex::vt::Engine;

namespace {

inline Engine* asEngine(jlong h) { return reinterpret_cast<Engine*>(h); }

// UTF-8 → UTF-16 helper (correct for supplementary code points).
void utf8ToUtf16(const std::string& in, std::vector<jchar>& out) {
  out.clear();
  out.reserve(in.size());
  size_t i = 0;
  while (i < in.size()) {
    uint8_t b = uint8_t(in[i]);
    uint32_t cp;
    size_t need;
    if (b < 0x80) {
      out.push_back(jchar(b));
      ++i;
      continue;
    } else if ((b & 0xE0) == 0xC0) {
      need = 2;
      cp = b & 0x1F;
    } else if ((b & 0xF0) == 0xE0) {
      need = 3;
      cp = b & 0x0F;
    } else if ((b & 0xF8) == 0xF0) {
      need = 4;
      cp = b & 0x07;
    } else {
      out.push_back(jchar(0xFFFD));
      ++i;
      continue;
    }
    if (i + need > in.size()) {
      out.push_back(jchar(0xFFFD));
      ++i;
      continue;
    }
    bool ok = true;
    for (size_t k = 1; k < need; ++k) {
      uint8_t cc = uint8_t(in[i + k]);
      if ((cc & 0xC0) != 0x80) {
        ok = false;
        break;
      }
      cp = (cp << 6) | (cc & 0x3F);
    }
    if (!ok) {
      out.push_back(jchar(0xFFFD));
      ++i;
      continue;
    }
    if (cp < 0x10000) {
      out.push_back(jchar(cp));
    } else {
      cp -= 0x10000;
      out.push_back(jchar(0xD800 | (cp >> 10)));
      out.push_back(jchar(0xDC00 | (cp & 0x3FF)));
    }
    i += need;
  }
}

jstring newStringUtf8(JNIEnv* env, const std::string& s) {
  std::vector<jchar> u16;
  utf8ToUtf16(s, u16);
  if (u16.empty()) return env->NewString(u16.data(), 0);
  return env->NewString(u16.data(), jsize(u16.size()));
}

jintArray toJIntArray(JNIEnv* env, const std::vector<jint>& v) {
  jintArray arr = env->NewIntArray(jsize(v.size()));
  if (arr && !v.empty()) env->SetIntArrayRegion(arr, 0, jsize(v.size()), v.data());
  return arr;
}

jobjectArray toStringArray(JNIEnv* env, const std::vector<std::string>& v) {
  jclass cls = env->FindClass("java/lang/String");
  if (!cls) return nullptr;
  jobjectArray arr = env->NewObjectArray(jsize(v.size()), cls, nullptr);
  for (size_t i = 0; arr && i < v.size(); ++i) {
    jstring s = newStringUtf8(env, v[i]);
    env->SetObjectArrayElement(arr, jsize(i), s);
    env->DeleteLocalRef(s);
  }
  return arr;
}

// Flat layout: [rowCount, perRow: cellCount, cells...: cp, fg, bg, flags, nComb, comb...]
void encodeFlatRows(JNIEnv* env, const std::vector<apex::vt::FlatRow>& rows,
                    const std::vector<uint32_t>& combPool, std::vector<jint>& out) {
  out.clear();
  out.push_back(jint(rows.size()));
  for (const auto& row : rows) {
    out.push_back(jint(row.cells.size()));
    for (const auto& c : row.cells) {
      out.push_back(jint(c.cp));
      out.push_back(jint(c.fg));
      out.push_back(jint(c.bg));
      out.push_back(jint(c.flags));
      out.push_back(jint(c.combCount));
      for (uint32_t k = 0; k < c.combCount && c.combOff + k < combPool.size(); ++k) {
        out.push_back(jint(combPool[c.combOff + k]));
      }
    }
  }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeCreate(JNIEnv* env, jclass, jint rows, jint cols,
                                                        jint maxScrollback) {
  if (rows < 1 || cols < 1 || maxScrollback < 0) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                  "rows/cols must be >= 1, maxScrollback >= 0");
    return 0;
  }
  return reinterpret_cast<jlong>(new Engine(int(rows), int(cols), int(maxScrollback)));
}

JNIEXPORT void JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeDestroy(JNIEnv*, jclass, jlong handle) {
  delete asEngine(handle);
}

JNIEXPORT void JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeFeed(JNIEnv* env, jclass, jlong handle,
                                                      jbyteArray bytes, jint off, jint len) {
  Engine* e = asEngine(handle);
  if (!e || !bytes || len <= 0) return;
  jsize arrayLen = env->GetArrayLength(bytes);
  if (off < 0 || len > arrayLen - off) {
    env->ThrowNew(env->FindClass("java/lang/IndexOutOfBoundsException"), "bad feed range");
    return;
  }
  // Critical section — the engine makes no JNI calls while running.
  void* critical = env->GetPrimitiveArrayCritical(bytes, nullptr);
  if (critical) {
    e->feed(reinterpret_cast<const uint8_t*>(critical) + off, size_t(len));
    env->ReleasePrimitiveArrayCritical(bytes, critical, JNI_ABORT);
    return;
  }
  jbyte* elems = env->GetByteArrayElements(bytes, nullptr);
  if (elems) {
    e->feed(reinterpret_cast<const uint8_t*>(elems) + off, size_t(len));
    env->ReleaseByteArrayElements(bytes, elems, JNI_ABORT);
  }
}

JNIEXPORT void JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeFlush(JNIEnv*, jclass, jlong handle) {
  if (Engine* e = asEngine(handle)) e->flush();
}

JNIEXPORT void JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeResize(JNIEnv*, jclass, jlong handle, jint rows,
                                                        jint cols) {
  if (Engine* e = asEngine(handle)) e->resize(int(rows), int(cols));
}

JNIEXPORT void JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeReset(JNIEnv*, jclass, jlong handle) {
  if (Engine* e = asEngine(handle)) e->reset();
}

// [rows, cols, cursorRow, cursorCol, cursorVisible, cursorShape, altScreen,
//  applicationCursor, bracketedPaste, reverseVideo, scrollbackTotal,
//  scrollbackBase, bellSeq(2 slots — hi/lo as longs)] → use jlongArray.
JNIEXPORT jlongArray JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeHeader(JNIEnv* env, jclass, jlong handle) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  const jlong v[] = {
      jlong(e->rows()),
      jlong(e->cols()),
      jlong(e->cursorRow()),
      jlong(e->cursorCol()),
      jlong(e->cursorVisible() ? 1 : 0),
      jlong(static_cast<int>(e->cursorShape())),
      jlong(e->alternateScreen() ? 1 : 0),
      jlong(e->applicationCursor() ? 1 : 0),
      jlong(e->bracketedPaste() ? 1 : 0),
      jlong(e->reverseVideo() ? 1 : 0),
      jlong(e->scrollbackTotal()),
      jlong(e->scrollbackBase()),
  };
  jlongArray arr = env->NewLongArray(12);
  if (arr) env->SetLongArrayRegion(arr, 0, 12, v);
  return arr;
}

JNIEXPORT jstring JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeTitle(JNIEnv* env, jclass, jlong handle) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  const char* t = e->title();
  return t ? newStringUtf8(env, std::string(t)) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeRenderedText(JNIEnv* env, jclass, jlong handle) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  return newStringUtf8(env, e->renderedText());
}

JNIEXPORT jobjectArray JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeScrollbackText(JNIEnv* env, jclass, jlong handle,
                                                                jint maxLines) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  return toStringArray(env, e->scrollbackText(int(maxLines)));
}

JNIEXPORT jintArray JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeVisibleCells(JNIEnv* env, jclass, jlong handle) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  apex::vt::FlatSnapshot snap = e->renderSnapshot(0);
  std::vector<jint> flat;
  encodeFlatRows(env, snap.visible, snap.combPool, flat);
  return toJIntArray(env, flat);
}

// Full snapshot in one call: visible + scrollback + header in flat ints.
// Layout: [16 header ints, visible rows, scrollback rows] where rows are
// [rowCount, perRow: cellCount, cells...: cp, fg, bg, flags, nComb, comb...].
// Header: rows, cols, cursorRow, cursorCol, cursorVisible, cursorShape,
// altScreen, applicationCursor, bracketedPaste, reverseVideo,
// scrollbackTotal, reserved, scrollbackBase(hi/lo), bellSeq(hi/lo).
// The Kotlin wrapper decodes; renderSnapshot drains the bell (Kotlin parity).
JNIEXPORT jintArray JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeSnapshotCells(JNIEnv* env, jclass, jlong handle,
                                                               jint maxScrollbackLines) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  apex::vt::FlatSnapshot snap = e->renderSnapshot(int(maxScrollbackLines));
  std::vector<jint> visible;
  std::vector<jint> scrollback;
  encodeFlatRows(env, snap.visible, snap.combPool, visible);
  encodeFlatRows(env, snap.scrollback, snap.combPool, scrollback);

  std::vector<jint> flat;
  flat.reserve(12 + visible.size() + scrollback.size());
  flat.push_back(jint(snap.rows));
  flat.push_back(jint(snap.cols));
  flat.push_back(jint(snap.cursorRow));
  flat.push_back(jint(snap.cursorCol));
  flat.push_back(jint(snap.cursorVisible ? 1 : 0));
  flat.push_back(jint(static_cast<int>(snap.cursorShape)));
  flat.push_back(jint(snap.alternateScreen ? 1 : 0));
  flat.push_back(jint(snap.applicationCursor ? 1 : 0));
  flat.push_back(jint(snap.bracketedPaste ? 1 : 0));
  flat.push_back(jint(snap.reverseVideo ? 1 : 0));
  flat.push_back(jint(snap.scrollbackTotal));
  flat.push_back(jint(0));  // reserved (alignment / future use)
  flat.push_back(jint(snap.scrollbackBase >> 32));
  flat.push_back(jint(uint32_t(snap.scrollbackBase)));
  flat.push_back(jint(snap.bellSeq >> 32));
  flat.push_back(jint(uint32_t(snap.bellSeq)));
  flat.insert(flat.end(), visible.begin(), visible.end());
  flat.insert(flat.end(), scrollback.begin(), scrollback.end());
  return toJIntArray(env, flat);
}

JNIEXPORT jintArray JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeDrainMutations(JNIEnv* env, jclass, jlong handle) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  std::vector<apex::vt::Mutation> muts = e->drainMutations();
  std::vector<jint> flat;
  flat.reserve(muts.size() * 3);
  for (const auto& m : muts) {
    flat.push_back(jint(static_cast<int>(m.type)));
    flat.push_back(jint(m.firstRow));
    flat.push_back(jint(m.lastRow));
  }
  return toJIntArray(env, flat);
}

JNIEXPORT jlong JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeDrainBell(JNIEnv*, jclass, jlong handle) {
  Engine* e = asEngine(handle);
  return e ? jlong(e->drainBell()) : 0;
}

JNIEXPORT jobjectArray JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativeDrainClipboardRequests(JNIEnv* env, jclass,
                                                                        jlong handle) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  return toStringArray(env, e->drainClipboardRequests());
}

JNIEXPORT jbyteArray JNICALL
Java_com_apex_agent_vtnative_NativeVtCore_nativePollResponses(JNIEnv* env, jclass, jlong handle) {
  Engine* e = asEngine(handle);
  if (!e) return nullptr;
  std::vector<uint8_t> r = e->pollResponses();
  if (r.empty()) return nullptr;
  jbyteArray arr = env->NewByteArray(jsize(r.size()));
  if (arr) {
    std::vector<jbyte> tmp(r.begin(), r.end());
    env->SetByteArrayRegion(arr, 0, jsize(tmp.size()), tmp.data());
  }
  return arr;
}

}  // extern "C"
