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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

/**
 * 附件按钮（+ → × 旋转动画）
 * 展开时 + 顺时针旋转 45° 变为 ×
 * 关闭时逆时针旋转回 0°
 */
@Composable
fun AttachButton(
    onFileSelected: (Uri) -> Unit,
    onImageSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    val rotation by animateFloatAsState(
        targetValue = if (isMenuExpanded) 45f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "attach_rotation"
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { onFileSelected(it) } }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { onImageSelected(it) } }

    Box(modifier = modifier) {
        IconButton(
            onClick = { isMenuExpanded = !isMenuExpanded },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (isMenuExpanded) "关闭" else "添加附件",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation)
            )
        }

        DropdownMenu(
            expanded = isMenuExpanded,
            onDismissRequest = { isMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("上传文件") },
                leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                onClick = {
                    isMenuExpanded = false
                    filePickerLauncher.launch("*/*")
                }
            )
            DropdownMenuItem(
                text = { Text("上传图片") },
                leadingIcon = { Icon(Icons.Default.Image, null) },
                onClick = {
                    isMenuExpanded = false
                    imagePickerLauncher.launch("image/*")
                }
            )
        }
    }
}
