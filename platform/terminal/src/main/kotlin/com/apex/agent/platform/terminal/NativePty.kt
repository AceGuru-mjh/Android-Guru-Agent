package com.apex.agent.platform.terminal

/**
 * JNI接口声明
 * 与C++ PTY引擎通信的唯一通道
 */
class NativePty {

    companion object {
        private var loaded = false

        fun ensureLoaded() {
            if (!loaded) {
                System.loadLibrary("apex_terminal")
                loaded = true
            }
        }
    }

    init {
        ensureLoaded()
    }

    external fun nativeCreateSession(
        shell: String, workDir: String,
        envKeys: Array<String>?, envVals: Array<String>?,
        rows: Int, cols: Int
    ): Int

    external fun nativeWrite(sessionId: Int, data: String): Boolean
    external fun nativeWriteRaw(sessionId: Int, data: String): Boolean
    external fun nativeRead(sessionId: Int, maxBytes: Int, stripAnsi: Boolean): String
    external fun nativeHasData(sessionId: Int): Boolean
    external fun nativeWaitForData(sessionId: Int, timeoutMs: Int): Boolean
    external fun nativeSendSignal(sessionId: Int, signal: Int): Boolean
    external fun nativeResize(sessionId: Int, rows: Int, cols: Int)
    external fun nativeIsAlive(sessionId: Int): Boolean
    external fun nativeGetPid(sessionId: Int): Int
    external fun nativeGetExitCode(sessionId: Int): Int
    external fun nativeCloseSession(sessionId: Int)
    external fun nativeCloseAll()
    external fun nativeActiveCount(): Int
    external fun nativeListSessionIds(): IntArray
}
