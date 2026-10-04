package com.apex.agent.core.tools.catalog

/**
 * # Capability Introspection — Privilege Ladder（权限阶梯知识库）
 *
 * 根因：系统提示词过去只告知「当前权限等级」，agent 既不知道阶梯全貌
 * （NORMAL_SHELL < SHIZUKU < ROOT 每级解锁什么），也不知道升级路径，
 * 遇到 permission denied 只会盲目重试或直接放弃。
 *
 * 本对象是权限知识的**单一真值源**，两个消费方永远输出同一份内容：
 * - [com.apex.agent.core.engine.EnginePrompts]（系统提示词段）；
 * - [CapabilityReportTool]（capability_report 工具输出）；
 * - [com.apex.agent.core.engine.orchestrator.OrchestratorPrompts]（BUILD
 *   编排线的一行简介）。
 *
 * 纯静态知识（与运行时检测解耦——运行时等级由 PrivilegeInfoProvider
 * 注入），零状态零副作用，全部函数可直接单测。
 */
object PrivilegeLadder {

    const val LEVEL_ROOT = "ROOT"
    const val LEVEL_SHIZUKU = "SHIZUKU"
    const val LEVEL_NORMAL_SHELL = "NORMAL_SHELL"

    /** 一级权限的结构化知识（prompt 与工具输出共用）。 */
    data class LevelInfo(
        /** 规范化等级名（见 [normalize]）。 */
        val level: String,
        /** 单行摘要（工具输出首行用）。 */
        val summary: String,
        /** 本级可做（每条已含动词/名词，可直接拼进 CAN 清单）。 */
        val can: List<String>,
        /** 本级不可做（每条附需要哪一级）。 */
        val cannot: List<String>,
        /** 升级指引（面向用户的话术 + 解锁内容）。 */
        val upgradeHint: String
    )

    /** 未知/空值一律折叠为 NORMAL_SHELL（与引擎 prompt 的 else 分支口径一致）。 */
    fun normalize(level: String): String = when (level.trim().uppercase()) {
        LEVEL_ROOT -> LEVEL_ROOT
        LEVEL_SHIZUKU -> LEVEL_SHIZUKU
        else -> LEVEL_NORMAL_SHELL
    }

    /** 取某级（任意输入串）的结构化知识——内部先规范化。 */
    fun infoFor(level: String): LevelInfo {
        val normalized = normalize(level)
        return when (normalized) {
            LEVEL_ROOT -> LevelInfo(
                level = LEVEL_ROOT,
                summary = "ROOT — full superuser access via su (top of the ladder)",
                can = listOf(
                    "execute any command with su",
                    "read/write /system, /data and other apps' private data",
                    "mount filesystems, edit SELinux policy, iptables",
                    "everything SHIZUKU and NORMAL_SHELL can do"
                ),
                cannot = emptyList(),
                upgradeHint = "You are at the top — no further privilege to unlock."
            )
            LEVEL_SHIZUKU -> LevelInfo(
                level = LEVEL_SHIZUKU,
                summary = "SHIZUKU (ADB-level) — shell user uid=2000",
                can = listOf(
                    "pm install/uninstall, am start/stop, settings put/get",
                    "dumpsys, getprop, screencap",
                    "input tap/swipe/text/keyevent",
                    "read/write /sdcard/"
                ),
                cannot = listOf(
                    "modify /system (needs ROOT)",
                    "access other apps' /data/data (needs ROOT)",
                    "mount, iptables, modify SELinux, ptrace (needs ROOT)"
                ),
                upgradeHint = "ROOT (KernelSU/Magisk) unlocks system-level operations — " +
                    "ask the user to unlock root when a task truly needs it."
            )
            else -> LevelInfo(
                level = LEVEL_NORMAL_SHELL,
                summary = "NORMAL_SHELL — no Root, no Shizuku (bottom of the ladder)",
                can = listOf(
                    "basic file operations in /sdcard and your own app sandbox",
                    "the PRoot Ubuntu terminal (full Linux userland, unprivileged)"
                ),
                cannot = listOf(
                    "pm app management / am activity control / settings put (needs SHIZUKU)",
                    "input injection, dumpsys (needs SHIZUKU)",
                    "/system and other apps' private data (needs ROOT)"
                ),
                upgradeHint = "Shizuku (recommended, no root needed) — install from " +
                    "https://shizuku.rikka.app/ and start it via ADB or wireless debugging; " +
                    "it unlocks pm/am/settings/dumpsys/input. Ask the user to set it up."
            )
        }
    }

    /**
     * 系统提示词整段（含 heading）。heading 保留**原始**等级串（历史行为：
     * 既有测试与调用方传入什么就展示什么），正文按 [normalize] 规范化。
     */
    fun promptSection(rawLevel: String): String {
        val info = infoFor(rawLevel)
        return buildString {
            appendLine("## Device Privilege Level: ${rawLevel.trim()}")
            appendLine(
                "Privilege ladder on this device: NORMAL_SHELL < SHIZUKU < ROOT " +
                    "(yours: ${info.level})."
            )
            appendLine(info.summary + ".")
            appendLine("You CAN: " + info.can.joinToString("; ") + ".")
            if (info.cannot.isNotEmpty()) {
                appendLine("You CANNOT: " + info.cannot.joinToString("; ") + ".")
            }
            appendLine("Upgrade path: ${info.upgradeHint}")
            appendLine(
                "When a command fails with permission denied, check these lists FIRST: " +
                    "switch to a route your level allows, or ask the user to raise the " +
                    "level. Never blind-retry the same command."
            )
        }
    }

    /** 编排器/BUILD 线的一行简介（紧凑，适合低 token 预算路径）。 */
    fun briefLine(level: String): String {
        val info = infoFor(level)
        val cannotPart = if (info.cannot.isEmpty()) "no permission walls"
        else "CANNOT " + info.cannot.joinToString("; ")
        return "Privilege: ${info.level} — CAN ${info.can.joinToString("; ")}; $cannotPart."
    }
}
