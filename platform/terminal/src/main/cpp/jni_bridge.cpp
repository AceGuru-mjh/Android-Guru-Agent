#include <jni.h>
#include <string>
#include <vector>
#include "pty_engine.h"
#include "ansi_filter.h"

using namespace apex;

// 辅助：jstring → std::string
static std::string jniGetString(JNIEnv* env, jstring str) {
    if (!str) return "";
    const char* chars = env->GetStringUTFChars(str, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(str, chars);
    return result;
}

extern "C" {

// ═══════════════════════════════════════════════════════════════════════════
// P70 读取状态数值约定 —— 与 Kotlin 侧 com.apex.agent.platform.terminal.pty.PtyJniReadStatus
// 一一对应，勿改数值。详见 pty_engine.h 的 PtyReadStatus（nativeReadBytes 直接透传
// PtyEngine::readEx 的 status 字段，此处仅需 SESSION_NOT_FOUND 用于初始值）：
//   0 = DATA（读到数据）          1 = NO_DATA（EAGAIN —— idle，一切正常）
//   2 = EOF（输出流结束）          3 = ERROR（真实错误，errno 有效）
//   4 = SESSION_NOT_FOUND
// ═══════════════════════════════════════════════════════════════════════════
static constexpr jint PTY_READ_SESSION_NOT_FOUND = 4;

JNIEXPORT jint JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeCreateSession(
    JNIEnv* env, jobject,
    jstring shell, jstring workDir,
    jobjectArray envKeys, jobjectArray envVals,
    jint rows, jint cols) {

    std::string shellStr = jniGetString(env, shell);
    std::string workDirStr = jniGetString(env, workDir);

    // 解析环境变量
    std::vector<std::pair<std::string, std::string>> envVars;
    if (envKeys && envVals) {
        int count = env->GetArrayLength(envKeys);
        for (int i = 0; i < count; i++) {
            auto key = static_cast<jstring>(env->GetObjectArrayElement(envKeys, i));
            auto val = static_cast<jstring>(env->GetObjectArrayElement(envVals, i));
            envVars.emplace_back(jniGetString(env, key), jniGetString(env, val));
        }
    }

    return PtyEngine::instance().createSession(shellStr, workDirStr, envVars, rows, cols);
}

// ─────────────────────────────────────────────────────────────────────────
// P70-2/P70-3: 二进制安全写通道。
//
// 旧 nativeWrite(String) 走 PtyEngine::writeLine —— 会额外追加 '\n'，
// 与 Kotlin 层 TerminalInput.sendLine 的换行叠加成 "hello\n\n"（P70-3），
// 且 jstring→C-string 转换在 NUL 处截断（P70-2）。
// 生产路径一律走本方法：字节直传、零追加、零转换。
// ─────────────────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeWriteBytes(
    JNIEnv* env, jobject, jint sessionId, jbyteArray data, jint offset, jint len) {

    if (len < 0 || offset < 0) return JNI_FALSE;
    if (len == 0) return JNI_TRUE;   // 0 字节写：无操作，视为成功
    if (data == nullptr) return JNI_FALSE;

    jsize arrayLen = env->GetArrayLength(data);
    if (len > arrayLen - offset) return JNI_FALSE;  // 越界

    // GetByteArrayElements 在 ART 上通常返回直接指针（零拷贝）；
    // JNI_ABORT = 只读借用，释放时不回写。
    jboolean isCopy = JNI_FALSE;
    jbyte* elems = env->GetByteArrayElements(data, &isCopy);
    if (elems == nullptr) return JNI_FALSE;

    bool ok = PtyEngine::instance().write(
        sessionId,
        reinterpret_cast<const char*>(elems + offset),
        static_cast<size_t>(len));

    env->ReleaseByteArrayElements(data, elems, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────────────────
// P70-1/P70-2: 二进制安全读通道（带状态）。
//
// 旧 nativeRead(String) 用 NewStringUTF(raw.c_str()) —— NUL 截断 + 非法
// UTF-8 损坏，且无法区分 idle/EOF/error，迫使上层用 hasData() 二次猜测，
// 空闲窗口被误判为流结束导致 PtyOutputPump 自杀（P70-1）。
//
// 本方法单次调用原子返回「字节 + 状态」：
//   返回 jbyteArray（可能为空数组，非 null）
//   statusOut[0] = 状态（见 PTY_READ_*）
//   statusOut[1] = errno（仅 ERROR 时有效，否则 0）
//   statusOut[2] = 本次返回的字节数
// ─────────────────────────────────────────────────────────────────────────
JNIEXPORT jbyteArray JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeReadBytes(
    JNIEnv* env, jobject, jint sessionId, jint maxBytes, jintArray statusOut) {

    jint status[3] = {PTY_READ_SESSION_NOT_FOUND, 0, 0};

    int cap = (maxBytes > 0) ? maxBytes : 4096;
    ReadOutcome outcome = PtyEngine::instance().readEx(sessionId, cap);
    status[0] = outcome.status;
    status[1] = outcome.err;
    status[2] = static_cast<jint>(outcome.data.size());

    jsize len = static_cast<jsize>(outcome.data.size());
    jbyteArray result = env->NewByteArray(len);
    if (result == nullptr) {
        // OOM —— pending exception 已挂起，直接返回 null
        return nullptr;
    }
    if (len > 0) {
        env->SetByteArrayRegion(result, 0, len,
                                reinterpret_cast<const jbyte*>(outcome.data.data()));
    }
    if (statusOut != nullptr && env->GetArrayLength(statusOut) >= 3) {
        env->SetIntArrayRegion(statusOut, 0, 3, status);
    }
    return result;
}

// ═══════════════════════════════════════════════════════════════════════════
// Legacy String 通道 —— 仅为 ABI 兼容保留（P70 起生产路径不再使用，
// Kotlin 侧已标注 @Deprecated）。语义缺陷见上方 P70 注释。
// ═══════════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeWrite(
    JNIEnv* env, jobject, jint sessionId, jstring data) {
    std::string dataStr = jniGetString(env, data);
    return PtyEngine::instance().writeLine(sessionId, dataStr);
}

JNIEXPORT jboolean JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeWriteRaw(
    JNIEnv* env, jobject, jint sessionId, jstring data) {
    std::string dataStr = jniGetString(env, data);
    return PtyEngine::instance().write(sessionId, dataStr.c_str(), dataStr.size());
}

JNIEXPORT jstring JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeRead(
    JNIEnv* env, jobject, jint sessionId, jint maxBytes, jboolean stripAnsi) {
    std::string raw = PtyEngine::instance().read(sessionId, maxBytes);
    if (stripAnsi) {
        raw = AnsiFilter::strip(raw);
    }
    return env->NewStringUTF(raw.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeHasData(
    JNIEnv*, jobject, jint sessionId) {
    return PtyEngine::instance().hasData(sessionId);
}

JNIEXPORT jboolean JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeWaitForData(
    JNIEnv*, jobject, jint sessionId, jint timeoutMs) {
    return PtyEngine::instance().waitForData(sessionId, timeoutMs);
}

JNIEXPORT jboolean JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeSendSignal(
    JNIEnv*, jobject, jint sessionId, jint signal) {
    return PtyEngine::instance().sendSignal(sessionId, signal);
}

JNIEXPORT void JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeResize(
    JNIEnv*, jobject, jint sessionId, jint rows, jint cols) {
    PtyEngine::instance().resize(sessionId, rows, cols);
}

JNIEXPORT jboolean JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeIsAlive(
    JNIEnv*, jobject, jint sessionId) {
    return PtyEngine::instance().isAlive(sessionId);
}

JNIEXPORT jint JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeGetPid(
    JNIEnv*, jobject, jint sessionId) {
    return PtyEngine::instance().getPid(sessionId);
}

JNIEXPORT jint JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeGetExitCode(
    JNIEnv*, jobject, jint sessionId) {
    return PtyEngine::instance().getExitCode(sessionId);
}

JNIEXPORT void JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeCloseSession(
    JNIEnv*, jobject, jint sessionId) {
    PtyEngine::instance().closeSession(sessionId);
}

JNIEXPORT void JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeCloseAll(
    JNIEnv*, jobject) {
    PtyEngine::instance().closeAll();
}

JNIEXPORT jint JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeActiveCount(
    JNIEnv*, jobject) {
    return PtyEngine::instance().activeCount();
}

JNIEXPORT jintArray JNICALL
Java_com_apex_agent_platform_terminal_NativePty_nativeListSessionIds(
    JNIEnv* env, jobject) {
    auto ids = PtyEngine::instance().listSessionIds();
    jintArray result = env->NewIntArray(static_cast<int>(ids.size()));
    if (!ids.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<int>(ids.size()),
                               reinterpret_cast<const jint*>(ids.data()));
    }
    return result;
}

} // extern "C"
