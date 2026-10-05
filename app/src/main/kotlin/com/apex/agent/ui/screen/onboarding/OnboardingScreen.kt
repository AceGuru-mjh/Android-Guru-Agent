package com.apex.agent.ui.screen.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import kotlinx.coroutines.launch

/** 引导流程版本与工作区范围常量（MainActivity 门控 / 持久化两侧共用）。 */
object OnboardingFlow {
    /**
     * 当前引导版本。升级递增 —— 已完成版本低于它时首启重新展示，
     * 老用户借引导页获知新权限步骤与工作区选择（v1 的 onboardingCompleted
     * 重看语义的版本化延续，避免升级用户错过新能力布道）。
     */
    const val CURRENT_VERSION = 2
}

/**
 * 新手引导（Onboarding）—— 首次启动的五页横滑流程。
 *
 * 页面结构（v2）：
 *  1. 欢迎 —— Viro 品牌吉祥物（与启动器图标同源的像素侵略者）+ 应用定位一句话
 *  2. 能力 —— 对话智能体 / Ubuntu 终端 / 技能与市场 三大核心能力卡片
 *  3. 权限 —— 八步授权引导（存储/所有文件/系统设置/安装未知应用/电池优化/
 *     悬浮窗/通知/无障碍进阶），全部可跳过（OnboardingPermissionsPage）
 *  4. 工作区 —— 操控所有（默认）或 SAF 选定文件夹（OnboardingWorkspacePage）
 *  5. 就绪 —— 模型配置提示 + 上手路线 + 工作区摘要 + 「开始使用」收束
 *
 * 交互约定：
 *  - 顶栏「跳过」直达最后一页（不静默跳过 —— 收束页承载关键首步提示）；
 *  - 权限与工作区步骤逐项零门控可跳过，不做「必须授权才能继续」的强制；
 *  - 工作区选择即时经 [onWorkspaceSelected] 持久化（引导中途退出也不丢）；
 *  - 页码圆点指示器可点击跳页；
 *  - 结束回调 [onFinished] 由 MainActivity 持久化 onboardingCompleted +
 *    onboardingVersion（见 [OnboardingFlow.CURRENT_VERSION]）。
 */
@Composable
fun OnboardingScreen(
    workspaceScope: String,
    workspaceFolderName: String,
    onWorkspaceSelected: (scope: String, folderUri: String, folderName: String) -> Unit,
    onFinished: () -> Unit
) {
    val pageCount = 5
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pageCount - 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── 顶栏：跳过（直达收束页）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pageCount - 1) }
                    }
                ) { Text(stringResource(R.string.onboarding_skip)) }
            }

            // ── 主体横滑 ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> CapabilitiesPage()
                    2 -> OnboardingPermissionsPage()
                    3 -> OnboardingWorkspacePage(
                        selectedScope = workspaceScope,
                        selectedFolderName = workspaceFolderName,
                        onWorkspaceSelected = onWorkspaceSelected
                    )
                    else -> ReadyPage(
                        workspaceScope = workspaceScope,
                        workspaceFolderName = workspaceFolderName
                    )
                }
            }

            // ── 底部：页码指示器 + 主行动按钮 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    repeat(pageCount) { index ->
                        val selected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(
                                    width = if (selected) 24.dp else 8.dp,
                                    height = 8.dp
                                )
                                .clip(
                                    if (selected) RoundedCornerShape(4.dp) else CircleShape
                                )
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                        )
                    }
                }

                Button(
                    onClick = {
                        if (isLastPage) onFinished()
                        else scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (isLastPage) stringResource(R.string.onboarding_start)
                        else stringResource(R.string.onboarding_next),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
        }
    }
}

// ═══════════════════════ 页 1：欢迎 ═══════════════════════

@Composable
private fun WelcomePage() {
    // #225：内容 Column 原为 Arrangement.Center 且无 verticalScroll —— 横屏高度
    // 不足时上下两端同时被裁且无法滚动。改为外层 Box 承托居中（内容不足视口
    // 时保持原居中观感），Column 加 verticalScroll（内容超出视口时可滚动）。
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 品牌吉祥物 —— 与启动器图标同源的像素侵略者（Viro），白底圆角卡呼应自适应图标白盘
            Surface(
                shape = RoundedCornerShape(36.dp),
                color = Color.White,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.size(168.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.ic_invader_logo),
                        contentDescription = stringResource(R.string.onboarding_mascot),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(116.dp)
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            Text(
                text = "Apex Agent",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.onboarding_welcome_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ═══════════════════════ 页 2：核心能力 ═══════════════════════

private data class Capability(
    val icon: ImageVector,
    val title: String,
    val description: String
)

@Composable
private fun CapabilitiesPage() {
    val capabilities = listOf(
        Capability(
            Icons.Default.SmartToy,
            stringResource(R.string.onboarding_cap_agent_title),
            stringResource(R.string.onboarding_cap_agent_desc)
        ),
        Capability(
            Icons.Default.Terminal,
            stringResource(R.string.onboarding_cap_terminal_title),
            stringResource(R.string.onboarding_cap_terminal_desc)
        ),
        Capability(
            Icons.Default.Storefront,
            stringResource(R.string.onboarding_cap_market_title),
            stringResource(R.string.onboarding_cap_market_desc)
        )
    )

    // #225：同 WelcomePage —— Box 承托居中 + Column 可滚动，横屏不裁切
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.onboarding_what_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(32.dp))

            capabilities.forEach { cap ->
                CapabilityCard(cap)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CapabilityCard(cap: Capability) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        cap.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Column {
                Text(
                    cap.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    cap.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════ 页 5：就绪 ═══════════════════════

@Composable
private fun ReadyPage(
    workspaceScope: String,
    workspaceFolderName: String
) {
    // #225：同 WelcomePage —— Box 承托居中 + Column 可滚动，横屏不裁切
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_invader_logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.onboarding_final_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onboarding_ready_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.onboarding_quickstart_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.onboarding_quickstart_steps),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 工作区选择摘要（第 4 页的选择即时持久化，这里只是回显）
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    ) {
                        Text(
                            text = if (workspaceScope == WorkspaceScopes.FOLDER &&
                                workspaceFolderName.isNotBlank()
                            ) {
                                stringResource(
                                    R.string.onboarding_ws_current_folder, workspaceFolderName
                                )
                            } else {
                                stringResource(R.string.onboarding_ws_current_all)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
