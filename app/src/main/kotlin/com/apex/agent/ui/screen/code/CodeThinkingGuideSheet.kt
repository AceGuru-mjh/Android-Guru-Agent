package com.apex.agent.ui.screen.code

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.code.thinking.CodeThinkingProfile

/**
 * # Code Thinking Guide Sheet — Coding 屏思考档位指南（coding 专属七档）
 *
 * ## 这个组件解决什么问题
 *
 * [CodeThinkingSelector] 的下拉菜单里每档只有一行徽标 + 一句话画像——用户
 * 无从得知「ULTRACODE 和 MAXIMUM 差在哪」「APEXCODE 什么时候才值得开」
 * 「迭代倍率 / 压缩阈值 / 工具输出预算这些数字对自己的任务意味着什么」。
 * 本弹层把 **7 深度档 + AUTO 元档**的完整画像摊开讲清楚：
 *
 * - **阶梯总表**：8 行紧凑表格（档位 / budget / effort / 迭代倍率 / 工具
 *   输出预算），数据**直读 [CodeThinkingProfile.forLevel] 静态表**——UI 与引擎
 *   画像永远同源，杜绝文案漂移（core 改画像数值，这里自动跟读）；
 * - **每档一张卡片**：档位名徽标 + [CodeThinkingLevel.description]（枚举一句话）
 *   + [CodeThinkingProfile.uiDescriptionZh]（画像执行策略摘要）+ 「编码视角」
 *   段（本文件手写的 8 段适用谱系说明：从「改文案级小改」到「跨模块架构级
 *   大改」什么时候用这档 + 成本提示）；
 * - **当前档高亮**：primary 描边（BorderStroke，AgentRolesSection 同款），
 *   点击任意卡片直接 [onSelect] 切档——指南不只是看，还能顺手换挡。
 *
 * ## 数据流与设计取舍
 *
 * - 纯渲染 + 回调组件，不碰引擎不碰存储；currentLevel 由 CodeViewModel
 *   的 uiState 驱动，onSelect 直通 setThinkingLevel（与选择器同一通道，
 *   切档后本弹层内部状态零残留——高亮由参数重渲染）；
 * - 「编码视角」8 段文案手写在本文件（对齐 [CodeThinkingProfile.uiDescriptionZh]
 *   的 core 侧中文风格），不进 strings_code.xml：它们是随档位语义强绑定的
 *   说明文（改档位画像必须同步改这里），而非可独立演化的 UI chrome 文案；
 *   UI chrome（标题 / 表头 / 底部提示）走 code_thinking_guide_* 资源键
 *   （en/zh 对称）；
 * - 表格用 Row + weight 紧凑行（ModeGuideSheet 的思考档位简表同款手法）
 *   而非横向滚动：五列短数值（最长 APEXCODE / 65536 / ×3.0）在手机宽度内
 *   放得下，滚动只会藏数据；
 * - 总表 + 8 张卡片 + 底部提示全部收进**一个** LazyColumn（高度上限
 *   heightIn）：整张弹层可滚但不会被内容顶穿（CodeLongTaskSheet 列表
 *   同款约束手法）；标题 / 副标题固定在列表外，滚动时始终可见。
 *
 * 挂载点：由 CodeScreen 在思考选择器下拉底部「查看档位指南」入口打开
 * （接线归主控——本组件只交付 public API：currentLevel / onDismiss /
 * onSelect 三参数）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeThinkingGuideSheet(
    currentLevel: CodeThinkingLevel,
    onDismiss: () -> Unit,
    onSelect: (CodeThinkingLevel) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // ── 标题 + 副标题（固定区，不随列表滚动）──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.code_thinking_guide_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.code_thinking_guide_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
            )

            // ── 可滚内容区：总表 + 每档卡片 + 底部提示 ──
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                item { TierLadderTable() }

                item {
                    Text(
                        text = stringResource(R.string.code_thinking_guide_tap_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                items(CodeThinkingLevel.entries, key = { it.name }) { level ->
                    TierCard(
                        level = level,
                        isCurrent = level == currentLevel,
                        onSelect = onSelect
                    )
                }

                item {
                    GuideFooter()
                }
            }
        }
    }
}

/**
 * 阶梯总表：8 行紧凑表格（表头 + 7 深度档 + AUTO）。
 *
 * 数据全部取自 [CodeThinkingProfile.forLevel]——budget / effort / 迭代倍率 /
 * 输出预算与引擎真实画像同源，core 侧调整数值本表自动跟读（不硬编码）。
 * NONE 档 budget=0（显式不思考）、AUTO 档 budget/effort 为 null（跟随
 * 逐轮选档结果），显示为「—」。
 */
@Composable
private fun TierLadderTable() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = stringResource(R.string.code_thinking_guide_table_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        // 表头
        LadderRow(
            level = stringResource(R.string.code_thinking_guide_col_level),
            budget = stringResource(R.string.code_thinking_guide_col_budget),
            effort = stringResource(R.string.code_thinking_guide_col_effort),
            iters = stringResource(R.string.code_thinking_guide_col_iters),
            output = stringResource(R.string.code_thinking_guide_col_output),
            header = true
        )
        // 数据行：枚举声明序 = 阶梯深度序（NONE→…→APEXCODE，AUTO 恒最后）
        CodeThinkingLevel.entries.forEach { level ->
            val profile = CodeThinkingProfile.forLevel(level)
            LadderRow(
                level = level.name,
                budget = profile.thinkingBudget?.toString() ?: "—",
                effort = profile.reasoningEffortName
                    ?: stringResource(R.string.code_thinking_guide_auto_row),
                iters = "×${profile.maxIterationsScale}",
                output = profile.toolOutputBudget.toString()
            )
        }
    }
}

/** 总表单行（header = true 时表头样式：primary 色 + SemiBold）。 */
@Composable
private fun LadderRow(
    level: String,
    budget: String,
    effort: String,
    iters: String,
    output: String,
    header: Boolean = false
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val color = if (header) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row {
        Text(
            text = level,
            style = style,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            modifier = Modifier.weight(1.5f)
        )
        Text(text = budget, style = style, color = color, modifier = Modifier.weight(0.9f))
        Text(text = effort, style = style, color = color, modifier = Modifier.weight(1.0f))
        Text(text = iters, style = style, color = color, modifier = Modifier.weight(0.7f))
        Text(text = output, style = style, color = color, modifier = Modifier.weight(0.9f))
    }
}

/**
 * 单档卡片：徽标名 + 枚举 description + 画像 uiDescriptionZh + 编码视角段。
 *
 * 当前档 primary 描边（AgentRolesSection 的 isActive 边框同款）；点击整卡
 * onSelect 切档（指南即换挡入口，与选择器共用同一回调通道）。
 */
@Composable
private fun TierCard(
    level: CodeThinkingLevel,
    isCurrent: Boolean,
    onSelect: (CodeThinkingLevel) -> Unit
) {
    val profile = remember(level) { CodeThinkingProfile.forLevel(level) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = if (isCurrent) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(level) }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            // ── 头行：档位徽标 + 当前档标记 + 关键数字 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierBadge(level)
                Spacer(Modifier.width(8.dp))
                if (isCurrent) {
                    Text(
                        text = stringResource(R.string.code_thinking_guide_current_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "×${profile.maxIterationsScale} · ${profile.toolOutputBudget}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 枚举一句话（CodeThinkingLevel.description）──
            Text(
                text = level.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )

            // ── 画像执行策略摘要（ThinkingProfile.uiDescriptionZh）──
            Text(
                text = profile.uiDescriptionZh,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── 编码视角：什么时候用这档（适用谱系 + 成本提示）──
            Text(
                text = stringResource(R.string.code_thinking_guide_coding_view),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = codingViewText(level),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 档位名徽标：深水三档用强调色（档位越深越醒目），其余中性色。 */
@Composable
private fun TierBadge(level: CodeThinkingLevel) {
    val color = when (level) {
        CodeThinkingLevel.APEXCODE -> MaterialTheme.colorScheme.error
        CodeThinkingLevel.ULTRACODE -> MaterialTheme.colorScheme.tertiary
        CodeThinkingLevel.MAXIMUM, CodeThinkingLevel.DEEP -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = level.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/**
 * 底部提示区：档位与执行策略的联动说明 + APEXCODE 成本防线。
 *
 * 联动说明一句话讲清「选档不只是改提示词」：迭代上限 / 压缩阈值 / 工具
 * 输出预算都随档位联动（数值见上方总表）；APEXCODE 防线是产品级承诺
 * （AdaptiveThinkingSelector 全路径不可达，仅用户显式指定），tertiary 色
 * 提示而非 error——它是「贵」不是「错」。
 */
@Composable
private fun GuideFooter() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 24.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                text = stringResource(R.string.code_thinking_guide_footer_linkage),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.code_thinking_guide_footer_apex),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

/**
 * 「编码视角」8 段适用谱系说明（每档 2-4 行：什么时候用 + 成本提示）。
 *
 * 手写中文（与 [CodeThinkingProfile.uiDescriptionZh] 的 core 侧中文风格对齐），
 * 不进资源文件——理由见类 KDoc「设计取舍」：这几段文字与档位语义强绑定，
 * 改画像必同步改这里，放同一个文件里让两处离得近。
 *
 * 谱系定位（档位随改动规模递进）：
 * NONE 改文案级 → LIGHT 单点小改 → STANDARD 常规功能改动 →
 * DEEP 多文件特性 → MAXIMUM 危险区重构 → ULTRACODE 跨模块深改 →
 * APEXCODE 架构级大改；AUTO 不定位（逐轮自适应）。
 */
private fun codingViewText(level: CodeThinkingLevel): String = when (level) {
    CodeThinkingLevel.NONE ->
        "改文案、改常量、注释级微调——目标位置明确、不涉及逻辑分支。跑得最快" +
            "（迭代×0.8、更早压缩），但模型完全不做前置推理，复杂一点的任务容易瞎改；" +
            "拿不准就退回 LIGHT。"

    CodeThinkingLevel.LIGHT ->
        "单点小改：改一个函数 / 一段配置 / 一处样式，改动半径一目了然。" +
            "一读一改的纪律（改前扫一眼、改完扫一眼 diff）足够兜住风险，" +
            "成本只有 256 token 的思考预算——日常小修的默认选择。"

    CodeThinkingLevel.STANDARD ->
        "常规功能改动：一个完整但有边界的小需求（加个校验 / 补个分支 / 接个回调）。" +
            "读→改→验→回读的标准循环 + 最小 diff 纪律，成本适中。" +
            "跨文件改动超过两处时建议升 DEEP。"

    CodeThinkingLevel.DEEP ->
        "多文件特性 / 有连锁反应的改动：先建「改动集清单」再动手，每处改动关联验证方式，" +
            "探索类工作委派 code_task 子代理。工具失败后自动追加自检，" +
            "成本开始显著（迭代×1.2），但能防住「改了 A 忘了 B」。"

    CodeThinkingLevel.MAXIMUM ->
        "危险区重构：动核心数据结构 / 公共工具函数 / 状态机转移——不变量一破就是连环 bug。" +
            "识别不变量 + 连锁影响核对 + 终检三问清单全开，迭代×1.5。" +
            "改错了回滚成本高于思考成本的地方，才值得开这档。"

    CodeThinkingLevel.ULTRACODE ->
        "跨模块深改：一次改动波及 3 个以上模块 / 需要摸清整条调用链再动手。" +
            "依赖地图→候选改法→风险排序→最小修改→即时验证→回归扫描的编码闭环，" +
            "迭代×2.0、输出预算 12000——token 花费约为 STANDARD 的数倍，" +
            "但换来「改完即验证、不留回归债」。"

    CodeThinkingLevel.APEXCODE ->
        "架构级大改：换框架 / 迁移数据层 / 重定义模块边界这类「改错就翻车」的工程。" +
            "影响半径测绘 + 多方案对比矩阵 + 对抗性自审 + 全量验证矩阵 + 证据链汇报，" +
            "迭代×3.0、budget 65536、更晚压缩保留完整证据链——全梯度成本天花板。" +
            "只对真正的大活开：日常任务开它纯属烧 token。"

    CodeThinkingLevel.AUTO ->
        "不确定就选它：引擎按任务复杂度（长度 / 多步指示 / 代码含量 / 风险词 / 错误史）" +
            "逐轮动态选档——简单问题秒切 NONE，深水区自动升到 ULTRACODE。" +
            "永远不会自动选 APEXCODE（成本防线），想用巅峰档必须手动指定。"
}
