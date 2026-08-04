package com.apex.agent.ui.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

/**
 * 附件按钮（+ → × 旋转动画）
 *
 * 点击展开：+ 顺时针旋转 45° 变为 ×，弹出菜单
 * 关闭时：× 逆时针旋转回 0° 恢复为 +
 */
@Composable
fun AttachButton(
    onFileSelected: (Uri) -> Unit,
    onImageSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    // 旋转动画：展开时 45°（+ → ×），收起时 0°
    val rotation by animateFloatAsState(
        targetValue = if (isMenuExpanded) 45f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "attach_rotation"
    )

    // 文件选择器（所有类型）
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onFileSelected(it) }
    }

    // 图片选择器（仅图片）
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImageSelected(it) }
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { isMenuExpanded = !isMenuExpanded },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (isMenuExpanded) "关闭附件菜单" else "添加附件",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation)
            )
        }

        // 弹出菜单
        DropdownMenu(
            expanded = isMenuExpanded,
            onDismissRequest = { isMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("上传文件") },
                leadingIcon = {
                    Icon(Icons.Default.AttachFile, contentDescription = null)
                },
                onClick = {
                    isMenuExpanded = false
                    filePickerLauncher.launch("*/*")
                }
            )
            DropdownMenuItem(
                text = { Text("上传图片") },
                leadingIcon = {
                    Icon(Icons.Default.Image, contentDescription = null)
                },
                onClick = {
                    isMenuExpanded = false
                    imagePickerLauncher.launch("image/*")
                }
            )
        }
    }
}
