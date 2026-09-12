package com.apex.agent.ui.screen.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import kotlinx.coroutines.launch

/**
 * 新手引导（Onboarding）—— 首次启动的四页横滑流程。
 *
 * 页面结构：
 *  1. 欢迎 —— Viro 品牌吉祥物（与启动器图标同源的像素侵略者）+ 应用定位一句话
 *  2. 能力 —— 对话智能体 / Ubuntu 终端 / 技能与市场 三大核心能力卡片
 *  3. 权限 —— Android 13+ 通知运行时权限（前台服务可见性前提），可选权限引导入口
 *  4. 就绪 —— 模型配置提示 + 上手路线 + 「开始使用」收束
 *
 * 交互约定：
 *  - 顶栏「跳过」直达最后一页（不静默跳过 —— 收束页承载关键首步提示）
 *  - 页码圆点指示器可点击跳页
 *  - 结束回调 [onFinished] 由 MainActivity 持久化 onboardingCompleted
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pageCount = 4
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pageCount - 1

    // ── Android 13+ 通知权限（第 3 页主行动）。null = 未询问；低于 13 视为已授权 ──
    var notifGranted by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= 33) null else true)
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

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
                ) { Text("跳过") }
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
                    2 -> PermissionsPage(
                        notifGranted = notifGranted,
                        onRequestNotification = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                    else -> ReadyPage()
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
                        if (isLastPage) "开始使用" else "下一步",
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
                    contentDescription = "Apex Agent 品牌吉祥物",
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
            text = "口袋里的 AI 终端智能体",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "对话驱动 · 真实终端 · 百项工具\n让 Agent 在你的手机上真正干活",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
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
            "对话智能体",
            "多模型接入、计划确认、工具编排 —— 复杂任务一步步执行并汇报"
        ),
        Capability(
            Icons.Default.Terminal,
            "Ubuntu 终端",
            "手机上跑真实的 Linux 环境：apt 装包、bash 脚本、完整文件系统"
        ),
        Capability(
            Icons.Default.Storefront,
            "技能与市场",
            "100+ 内置工具按需取用，技能市场持续扩展 Agent 的能力边界"
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "它能做什么",
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

// ═══════════════════════ 页 3：权限 ═══════════════════════

@Composable
private fun PermissionsPage(
    notifGranted: Boolean?,
    onRequestNotification: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "先授一枚权限",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "仅此一项必选，其余都可以之后再开",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        // 通知权限卡片（主行动）
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (notifGranted == true) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = if (notifGranted == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "通知权限",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Android 13+ 需要显式授权 —— 前台服务的常驻通知、" +
                        "任务完成与需要确认的提醒都依赖它；不授权则通知静默不可见",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                if (notifGranted == true) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            "✓ 已授权",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    Button(onClick = onRequestNotification) {
                        Text(if (notifGranted == false) "再试一次" else "授权通知")
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 可选权限提示（引导去权限管理页）
        Text(
            "无障碍 · 悬浮窗 · 存储等进阶权限，\n可稍后在「抽屉 → 权限」按需开启",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ═══════════════════════ 页 4：就绪 ═══════════════════════

@Composable
private fun ReadyPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_invader_logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "最后一步",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "进入后先在「设置 → 模型配置」填入你的\nAPI 密钥（OpenAI / Anthropic / 兼容端点），\nAgent 才能开始思考",
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
                    "上手路线",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "1 · 抽屉菜单打开「设置」配置模型\n" +
                        "2 · 回到 Agent 对话页发第一条消息\n" +
                        "3 · 试试「终端」页一键安装 Ubuntu\n" +
                        "4 · 让 Agent 帮你装包、跑脚本、改文件",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
