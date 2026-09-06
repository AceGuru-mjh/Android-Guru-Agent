package com.apex.agent.platform.terminal.buffer

/**
 * T81 (D-6)：UTF-8 窗口边界解码工具。
 *
 * 问题：recentOutput / observe(RAW) 从 RingBuffer 按字节窗口取字节后直接
 * `toString(UTF_8)` —— 窗口起点若落在多字节 UTF-8 序列的中间（前导字节被
 * 上一次窗口取走/被环形驱逐），解码器把残缺序列替换为 U+FFFD（乱码），
 * 且**每个消费窗口都重复产生**（Agent 看到成片乱码）。
 *
 * 修复：解码前跳过头部的不完整序列前缀（连续的 10xxxxxx 后继字节），
 * 从首个合法序列起点开始解码。尾部不完整序列由 String 解码器自然替换
 * （窗口滚动语义：下一次窗口会带上完整序列）。
 */
object Utf8Boundary {

    /**
     * 解码一个字节窗口，跳过头部残缺的 UTF-8 序列。
     * 二进制安全：非 UTF-8 字节原样保留（解码器替换为 U+FFFD —— 与旧行为
     * 一致，但不再因窗口切割产生额外乱码）。
     */
    fun decodeWindow(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var start = 0
        // 10xxxxxx = 0x80..0xBF：多字节序列的后继字节 —— 头部出现即为残缺前缀。
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) {
            start++
        }
        if (start == 0) return String(bytes, Charsets.UTF_8)
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /** 便捷：[decodeWindow] 的 ByteArray slice 版本。 */
    fun decodeWindow(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0 || offset < 0 || offset + length > bytes.size) return ""
        var start = offset
        val end = offset + length
        while (start < end && (bytes[start].toInt() and 0xC0) == 0x80) {
            start++
        }
        return String(bytes, start, end - start, Charsets.UTF_8)
    }
}
