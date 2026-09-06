// host-side stub of android/log.h（本地验证专用，不进 APK）
#pragma once
#include <cstdio>
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    (void)prio; (void)tag; (void)fmt;
    return 0;  // 静默 —— host 验证关注断言而非日志
}
