package com.apex.agent.platform.terminal

/**
 * JVM 可注入的 JNI 桥接接口（P70）。
 *
 * 真实实现是 [NativePty]（external funs → libapex_terminal.so）。
 * 抽出接口的目的：JVM 单元测试可以注入替身，直接验证 [com.apex.agent.platform.terminal.pty.JniNativePty]
 * 的状态映射与字节语义 —— 这些行为历史上只能靠 FakeNativePty 旁路，真实 bridge 层
 * 完全没有测试覆盖（P70 修复的 4 个 bug 全部漏测的直接原因）。
 *
 * nativeReadBytes 的状态码约定（与 jni_bridge.cpp 的 PTY_READ_* 一一对应）：
 *   0 = DATA   1 = NO_DATA（idle）   2 = EOF   3 = ERROR   4 = SESSION_NOT_FOUND
 */
interface NativePtyJniBridge {
    fun nativeCreateSession(
        shell: String, workDir: String,
        envKeys: Array<String>?, envVals: Array<String>?,
        rows: Int, cols: Int
    ): Int

    /**
     * P71 (N1)：通用 argv 创建 —— child 执行 execv(argv[0], argv)。
     * 本地 shell（["/system/bin/sh","-i"]）与 proot Linux 会话共用同一条 forkpty 路径。
     * argv 为空或含空首元素时返回 -1。
     */
    fun nativeCreateSessionArgv(
        argv: Array<String>, workDir: String,
        envKeys: Array<String>?, envVals: Array<String>?,
        rows: Int, cols: Int
    ): Int

    /**
     * 二进制安全读取（P70-1/P70-2）。
     * @param statusOut 长度 ≥3 的输出数组：[0]=状态，[1]=errno（仅 ERROR），[2]=字节数。
     * @return 本次读到的原始字节（可能为空数组；null 仅在 JNI 异常时）。
     */
    fun nativeReadBytes(sessionId: Int, maxBytes: Int, statusOut: IntArray): ByteArray

    /** 二进制安全写入（P70-2/P70-3）：字节直传、零追加、零换行。 */
    fun nativeWriteBytes(sessionId: Int, data: ByteArray, offset: Int, len: Int): Boolean

    fun nativeHasData(sessionId: Int): Boolean
    fun nativeWaitForData(sessionId: Int, timeoutMs: Int): Boolean
    fun nativeSendSignal(sessionId: Int, signal: Int): Boolean
    fun nativeResize(sessionId: Int, rows: Int, cols: Int)
    fun nativeIsAlive(sessionId: Int): Boolean
    fun nativeGetPid(sessionId: Int): Int
    fun nativeGetExitCode(sessionId: Int): Int
    fun nativeCloseSession(sessionId: Int)
    fun nativeCloseAll()
    fun nativeActiveCount(): Int
    fun nativeListSessionIds(): IntArray
}

/**
 * JNI接口声明
 * 与C++ PTY引擎通信的唯一通道
 */
class NativePty : NativePtyJniBridge {

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

    override external fun nativeCreateSession(
        shell: String, workDir: String,
        envKeys: Array<String>?, envVals: Array<String>?,
        rows: Int, cols: Int
    ): Int

    /** P71 (N1): 通用 argv 创建入口（见 [NativePtyJniBridge.nativeCreateSessionArgv]）。 */
    override external fun nativeCreateSessionArgv(
        argv: Array<String>, workDir: String,
        envKeys: Array<String>?, envVals: Array<String>?,
        rows: Int, cols: Int
    ): Int

    /**
     * P70-1/P70-2: 生产读取通道（byte[] + 状态，二进制安全）。
     * 见 [NativePtyJniBridge.nativeReadBytes]。
     */
    override external fun nativeReadBytes(sessionId: Int, maxBytes: Int, statusOut: IntArray): ByteArray

    /**
     * P70-2/P70-3: 生产写入通道（byte[] 直传，不追加换行）。
     * LINE 模式的换行由 TerminalInput.sendLine 恰好追加一次。
     */
    override external fun nativeWriteBytes(sessionId: Int, data: ByteArray, offset: Int, len: Int): Boolean

    // ─── Legacy String 通道（P70 起生产路径不再使用，仅为 ABI 兼容保留） ───
    // nativeWrite 走 writeLine —— 会追加 '\n'（P70-3 双换行根因之一）；
    // nativeRead 走 NewStringUTF —— NUL 截断（P70-2）且无法区分 idle/EOF/error（P70-1）。

    @Deprecated(
        "P70: 额外追加 '\\n'（与 TerminalInput.sendLine 叠加成双换行）。改用 nativeWriteBytes。",
        ReplaceWith("nativeWriteBytes(sessionId, data.toByteArray(Charsets.UTF_8), 0, data.length)")
    )
    external fun nativeWrite(sessionId: Int, data: String): Boolean

    @Deprecated(
        "P70: jstring 路径对 NUL/二进制不安全。改用 nativeWriteBytes。",
        ReplaceWith("nativeWriteBytes(sessionId, data.toByteArray(Charsets.UTF_8), 0, data.length)")
    )
    external fun nativeWriteRaw(sessionId: Int, data: String): Boolean

    @Deprecated(
        "P70: NewStringUTF 在 NUL 处截断、非法 UTF-8 损坏，且无法区分 idle/EOF/error。改用 nativeReadBytes。",
        ReplaceWith("nativeReadBytes(sessionId, maxBytes, statusOut)")
    )
    external fun nativeRead(sessionId: Int, maxBytes: Int, stripAnsi: Boolean): String

    override external fun nativeHasData(sessionId: Int): Boolean
    override external fun nativeWaitForData(sessionId: Int, timeoutMs: Int): Boolean
    override external fun nativeSendSignal(sessionId: Int, signal: Int): Boolean
    override external fun nativeResize(sessionId: Int, rows: Int, cols: Int)
    override external fun nativeIsAlive(sessionId: Int): Boolean
    override external fun nativeGetPid(sessionId: Int): Int
    override external fun nativeGetExitCode(sessionId: Int): Int
    override external fun nativeCloseSession(sessionId: Int)
    override external fun nativeCloseAll()
    override external fun nativeActiveCount(): Int
    override external fun nativeListSessionIds(): IntArray
}
