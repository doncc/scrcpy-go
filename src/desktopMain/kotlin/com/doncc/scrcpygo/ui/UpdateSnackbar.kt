package com.doncc.scrcpygo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 底部更新提示条需要的展示数据 */
data class UpdatePrompt(
    val newVersion: String,
    val currentVersion: String,
    val downloadUrl: String?,
    val pageUrl: String
)

private val SnackbarBackground = Color(0xFF2C3E50)
private const val ENTER_DURATION_MS = 250
private const val EXIT_DURATION_MS = 200

/**
 * 底部更新提示条的宿主。
 * prompt 变为 null 时保留最后一次内容，保证退出动画期间不会先变成空白。
 */
@Composable
fun UpdateSnackbarHost(
    prompt: UpdatePrompt?,
    onUpgrade: (UpdatePrompt) -> Unit,
    onSkip: (UpdatePrompt) -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier
) {
    var lastPrompt by remember { mutableStateOf<UpdatePrompt?>(null) }
    LaunchedEffect(prompt) {
        if (prompt != null) lastPrompt = prompt
    }

    AnimatedVisibility(
        visible = prompt != null,
        enter = slideInVertically(
            initialOffsetY = { height -> height },
            animationSpec = tween(ENTER_DURATION_MS)
        ) + fadeIn(animationSpec = tween(ENTER_DURATION_MS)),
        exit = slideOutVertically(
            targetOffsetY = { height -> height },
            animationSpec = tween(EXIT_DURATION_MS)
        ) + fadeOut(animationSpec = tween(EXIT_DURATION_MS)),
        modifier = modifier
    ) {
        val shown = lastPrompt
        if (shown != null) {
            UpdateSnackbarContent(
                prompt = shown,
                onUpgrade = { onUpgrade(shown) },
                onSkip = { onSkip(shown) },
                onIgnore = onIgnore
            )
        }
    }
}

/** 提示条本体：深色底、两行版本信息、右侧三个操作，一直显示直到用户选择 */
@Composable
private fun UpdateSnackbarContent(
    prompt: UpdatePrompt,
    onUpgrade: () -> Unit,
    onSkip: () -> Unit,
    onIgnore: () -> Unit
) {
    Surface(
        color = SnackbarBackground,
        shape = AppShapes.medium,
        elevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "发现新版本 v${prompt.newVersion}",
                    color = Color.White,
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "当前版本 v${prompt.currentVersion}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.body2,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onUpgrade,
                colors = ButtonDefaults.buttonColors(backgroundColor = LightPrimary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("升级", color = Color.White, fontSize = 12.sp)
            }
            TextButton(
                onClick = onSkip,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("跳过", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
            TextButton(
                onClick = onIgnore,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("本次忽略", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }
    }
}
