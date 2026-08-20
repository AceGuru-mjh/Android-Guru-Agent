package com.apex.agent.ui.screen.agent

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.core.llm.ModelProfile
import kotlin.math.roundToInt

/** 模型列表单项行高；列表展开高度固定为其 3 倍（规格要求）。 */
private val MODEL_ITEM_HEIGHT = 48.dp
private val MODEL_LIST_HEIGHT = MODEL_ITEM_HEIGHT * 3

/**
 * 对话框"小大脑"智能菜单入口。
 *
 * 结构（自上而下）：
 * 1. 当前模型选择器——名称区点击（多模型时）或箭头点击（无条件）展开/收起
 *    可滚动模型列表（高度 = 单项 3 倍），箭头 180° 旋转动画；
 * 2. 配置模型——跳转设置页模型配置区；
 * 3. 模型参数调节——Temperature / Top-P / Max Tokens 三个自定义滑块，
 *    松手即持久化并即时生效。
 */
@Composable
fun BrainMenuButton(
    profiles: List<ModelProfile>,
    currentProfileId: String,
    providerNameOf: (String) -> String,
    onSelectProfile: (String) -> Unit,
    onParamsChanged: (temperature: Float, topP: Float, maxTokens: Int) -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var modelListOpen by remember { mutableStateOf(false) }

    val current = profiles.firstOrNull { it.id == currentProfileId } ?: profiles.firstOrNull()
    val arrowRotation by animateFloatAsState(
        targetValue = if (modelListOpen) 180f else 0f,
        animationSpec = tween(250),
        label = "brain_arrow_rotation"
    )

    Box(modifier = modifier) {
        IconButton(onClick = { menuOpen = !menuOpen }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = "小大脑",
                tint = if (menuOpen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                modelListOpen = false
            },
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.width(300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                // ── 第一项：当前模型选择器 ─────────────────────
                Text(
                    "当前模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(MODEL_ITEM_HEIGHT)
                    ) {
                        // 名称区：多模型时点击展开列表，单模型时提示
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (profiles.size <= 1) {
                                        Toast.makeText(context, "仅有一个模型", Toast.LENGTH_SHORT).show()
                                    } else {
                                        modelListOpen = !modelListOpen
                                    }
                                }
                                .padding(horizontal = 12.dp)
                        ) {
                            Column {
                                Text(
                                    current?.name ?: "未配置",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                current?.let {
                                    Text(
                                        "${providerNameOf(it.providerId)} · ${it.modelId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        // 箭头：无条件切换列表展开/收起 + 180° 旋转
                        IconButton(onClick = { modelListOpen = !modelListOpen }) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = if (modelListOpen) "收起模型列表" else "展开模型列表",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.rotate(arrowRotation)
                            )
                        }
                    }
                }

                // ── 可滚动模型列表（高度 = 单项 3 倍，菜单本体保持展开）──
                AnimatedVisibility(
                    visible = modelListOpen,
                    enter = expandVertically(tween(250)),
                    exit = shrinkVertically(tween(200))
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(MODEL_LIST_HEIGHT)
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            profiles.forEach { profile ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(MODEL_ITEM_HEIGHT)
                                        .clickable {
                                            onSelectProfile(profile.id)
                                            modelListOpen = false
                                        }
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            profile.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${providerNameOf(profile.providerId)} · ${profile.modelId}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (profile.id == current?.id) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "当前模型",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // ── 第二项：配置模型（跳设置页模型配置区）─────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            menuOpen = false
                            modelListOpen = false
                            onConfigure()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("配置模型", style = MaterialTheme.typography.bodyMedium)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // ── 第三项：模型参数调节（滑块，松手即生效）────────
                current?.let { p ->
                    BrainParamSliders(
                        profile = p,
                        onParamsChanged = onParamsChanged,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

/**
 * 三个核心采样参数的自定义滑块组：Temperature / Top-P / Max Tokens。
 * 拖拽中本地实时回显，松手（onValueChangeFinished）后回调持久化并即时生效。
 */
@Composable
private fun BrainParamSliders(
    profile: ModelProfile,
    onParamsChanged: (temperature: Float, topP: Float, maxTokens: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var temperature by remember { mutableFloatStateOf(profile.temperature) }
    var topP by remember { mutableFloatStateOf(profile.topP) }
    var maxTokens by remember { mutableFloatStateOf(profile.maxOutputTokens.toFloat()) }

    // 切换模型时同步滑块位置
    LaunchedEffect(profile.id, profile.temperature, profile.topP, profile.maxOutputTokens) {
        temperature = profile.temperature
        topP = profile.topP
        maxTokens = profile.maxOutputTokens.toFloat()
    }

    fun commit() = onParamsChanged(
        (temperature * 100).roundToInt() / 100f,
        (topP * 100).roundToInt() / 100f,
        maxTokens.roundToInt()
    )

    Column(modifier = modifier) {
        Text(
            "模型参数",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BrainSliderRow(
            label = "Temperature",
            value = temperature,
            valueRange = 0f..2f,
            display = "%.2f".format(temperature),
            onDrag = { temperature = it },
            onCommit = { commit() }
        )
        BrainSliderRow(
            label = "Top-P",
            value = topP,
            valueRange = 0f..1f,
            display = "%.2f".format(topP),
            onDrag = { topP = it },
            onCommit = { commit() }
        )
        BrainSliderRow(
            label = "Max Tokens",
            value = maxTokens,
            valueRange = 256f..32768f,
            display = maxTokens.roundToInt().toString(),
            onDrag = { maxTokens = it },
            onCommit = { commit() }
        )
    }
}

@Composable
private fun BrainSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onDrag: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(76.dp)
        )
        Slider(
            value = value,
            onValueChange = onDrag,
            onValueChangeFinished = onCommit,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            display,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(44.dp)
        )
    }
}
