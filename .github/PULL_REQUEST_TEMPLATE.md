<!--
PR 模板 —— 帮助 reviewer 快速理解变更。短小的修复可精简填写，但请勿删除结构。
For small fixes, keep it brief but do not remove the structure.
-->

## What & Why

<!-- 一句话说清这个 PR 做了什么、为什么需要它。 -->


## Changes

<!-- 关键改动列表（文件/模块级别的 bullet points）。 -->


## Testing

- [ ] `:core:agent-engine:test` 通过
- [ ] `:core:tool-registry:test` 通过
- [ ] `:platform:terminal:testDebugUnitTest` 通过（若涉及）
- [ ] `:app:compileDebugKotlin` 通过（若涉及 app/）
- [ ] 手动验证了核心用户路径（描述于下）

<!-- 本地验证命令：
export JAVA_HOME=<jdk17> && ./gradlew :core:agent-engine:test :core:tool-registry:test --no-daemon
-->

## Quality gates

- [ ] 新增/修改的 Kotlin 主源码文件 ≤ 1200 行（`scripts/check_file_size.sh`）
- [ ] 无 `javaClass.getMethod` 反射分发、无 `printStackTrace()`（`scripts/check_code_quality.sh`）
- [ ] 未通过删除/跳过测试步骤的方式让 CI 变绿

## Screenshots / Demo（UI 变更时）

<!-- 前后对比截图或录屏。 -->
