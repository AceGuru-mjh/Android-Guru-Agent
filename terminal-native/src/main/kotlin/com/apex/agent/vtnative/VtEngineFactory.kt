package com.apex.agent.vtnative

import android.util.Log
import com.apex.agent.terminalemulator.TerminalCore
import com.apex.agent.terminalemulator.TerminalEngine

/**
 * VtEngineFactory — runtime backend selection for the VT hot path.
 *
 * ```
 * RealVirtualTerminal → VtEngineFactory.create(rows, cols)
 *                          ├─ libvt_native.so loads → NativeVtCore (C++17 零分配引擎)
 *                          └─ 否则（单测/JVM CI/异常 ABI）→ TerminalCore（纯 Kotlin 回退）
 * ```
 *
 * 回退语义（保证零破坏）：
 *  - JVM 单元测试与 CI：无 .so → 全部走 TerminalCore，现有 129+ 用例语义不变；
 *  - 设备端：三 ABI .so 由 :terminal-native 的 CMake 随 APK 一起打包，正常路径为 native；
 *  - 首次加载失败后 [nativeAvailable] 置 false —— 后续会话直接走回退，
 *    不再为每个终端会话重复抛 UnsatisfiedLinkError/NoClassDefFoundError。
 *
 * 引擎对象生命周期与 RealVirtualTerminal 一致（会话级），由 RVT 持有引用。
 */
object VtEngineFactory {

    private const val TAG = "VtEngineFactory"

    /** null = 未探测；true = native 可用；false = 已确认不可用（粘性回退）。 */
    @Volatile
    private var nativeAvailable: Boolean? = null

    /** 上游引擎选择（测试可注入强制回退）。 */
    @Volatile
    var forceKotlinFallback: Boolean = false

    fun create(rows: Int, cols: Int, maxScrollback: Int = 1000): TerminalEngine {
        if (forceKotlinFallback) return TerminalCore(rows, cols, maxScrollback)

        if (nativeAvailable == false) return TerminalCore(rows, cols, maxScrollback)

        return try {
            val engine = NativeVtCore(rows, cols, maxScrollback)
            nativeAvailable = true
            engine
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / ExceptionInInitializerError / NoClassDefFoundError
            nativeAvailable = false
            // runCatching：宿主模块 JVM 单测未开 returnDefaultValues 时，
            // android.util.Log.w 会抛 "not mocked" —— 回退路径绝不能因此二次失败。
            runCatching {
                Log.w(TAG, "native VT engine unavailable — falling back to Kotlin TerminalCore", t)
            }
            TerminalCore(rows, cols, maxScrollback)
        }
    }

    /** 当前选择是否为 native 后端（诊断/测试用）。 */
    fun isNativeAvailable(): Boolean = nativeAvailable == true
}
