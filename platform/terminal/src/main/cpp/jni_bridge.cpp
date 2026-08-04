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
