package com.apex.agent.platform.terminal.state

import com.apex.agent.platform.terminal.events.Confidence
import org.junit.Assert.*
import org.junit.Test

/**
 * T81 (D-3) — 真实 shell PS1 识别回归。
 *
 * 根因背景：原 InputWaitingDetector 只认裸 `^\s*\$\s*$`/`^\s*#\s*$` ——
 * 真实 bash 默认 PS1（user@host:~$）、venv/conda 前缀、zsh prompt 全部不命中 →
 * 前台 job 完成后 JobManager 的合成退出永不触发 → job 永久 RUNNING、wait() 挂死。
 * FakeNativePty 输出裸 "$ " 掩盖了此缺陷（测试绿 / 生产必挂）。
 */
class T81RealPromptDetectionTest {

    private val detector = InputWaitingDetector()

    private fun conf(line: String): Confidence = detector.detectFromText(line, null)

    @Test fun `bash default PS1 user@host path dollar is HIGH`() {
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("user@host:~$"))
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("u@h:/data/local/tmp$"))
    }

    @Test fun `bash root PS1 host path hash is HIGH`() {
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("root@localhost:/#"))
    }

    @Test fun `venv prefixed PS1 is HIGH`() {
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("(venv) $"))
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("(base) user@host:~$"))
    }

    @Test fun `zsh default prompt percent is HIGH`() {
        // zsh 默认 prompt 是裸 %；带前缀形态（`machine %`）与进度条输出
        //（`50%`）无法可靠区分，保守不认（避免假阳性）。
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("%"))
        assertEquals(Confidence.UNKNOWN, conf("machine %"))
        assertEquals(Confidence.UNKNOWN, conf("50%"))
    }

    @Test fun `exit-code-prefixed PS1 is HIGH`() {
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("1 $"))
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("127 $"))
    }

    @Test fun `bare prompts still HIGH (regression)`() {
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("$"))
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("#"))
        assertEquals(Confidence.HIGH_CONFIDENCE, conf(">>> "))
    }

    @Test fun `ordinary output lines are not HIGH`() {
        assertEquals(Confidence.UNKNOWN, conf("hello world"))
        assertEquals(Confidence.UNKNOWN, conf("total 12"))
        assertEquals(Confidence.UNKNOWN, conf("build succeeded in 3s"))
    }

    @Test fun `output ending with dollar inside text is not HIGH (false-positive guard)`() {
        assertEquals(Confidence.UNKNOWN, conf("costs 5$"))
        assertEquals(Confidence.UNKNOWN, conf("price: 12$"))
    }

    @Test fun `confirmation and password prompts remain HIGH (regression)`() {
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("Continue? [y/N]"))
        assertEquals(Confidence.HIGH_CONFIDENCE, conf("Password:"))
    }

    @Test fun `interactive program with prompt-ish ending is HIGH`() {
        assertEquals(Confidence.HIGH_CONFIDENCE, detector.detectFromText(">>> ", "python3"))
        assertEquals(Confidence.POSSIBLE, detector.detectFromText("some text", "python3"))
    }

    @Test fun `non interactive command output stays UNKNOWN`() {
        assertEquals(Confidence.UNKNOWN, detector.detectFromText("compiling...", "make"))
    }
}
