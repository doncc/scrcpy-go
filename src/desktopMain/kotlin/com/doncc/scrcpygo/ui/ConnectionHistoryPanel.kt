package com.doncc.scrcpygo.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doncc.scrcpygo.history.TcpConnectionRecord
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val sameYearTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val fullTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 历史 TCP 连接列表面板。
 * 面板本身不持有业务状态，双击/选中/删除/清空都通过回调交给主界面处理。
 */
@Composable
fun ConnectionHistoryPanel(
    records: List<TcpConnectionRecord>,
    selectedAddress: String?,
    onSelect: (TcpConnectionRecord) -> Unit,
    onDoubleClick: (TcpConnectionRecord) -> Unit,
    onDelete: (TcpConnectionRecord) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "历史 TCP 连接（双击填入）",
                style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.SemiBold),
                color = LightTextMain,
                modifier = Modifier.weight(1f)
            )
            if (records.isNotEmpty()) {
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("清空", fontSize = 11.sp, color = LightPrimary)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .border(1.dp, LightBorder, AppShapes.small)
                .background(Color.White, AppShapes.small)
                .padding(2.dp)
        ) {
            if (records.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无历史记录，TCP 连接成功后自动记录",
                        style = MaterialTheme.typography.body2,
                        color = LightTextSub
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(records, key = { it.address }) { record ->
                        ConnectionHistoryRow(
                            record = record,
                            selected = record.address == selectedAddress,
                            onSelect = { onSelect(record) },
                            onDoubleClick = { onDoubleClick(record) },
                            onDelete = { onDelete(record) }
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ConnectionHistoryRow(
    record: TcpConnectionRecord,
    selected: Boolean,
    onSelect: () -> Unit,
    onDoubleClick: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val rowBackground = when {
        selected -> Color(0xFFE3F2FD)
        isHovered -> Color(0xFFF8FAFC)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(rowBackground)
            .hoverable(interactionSource)
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 双击区域只覆盖文本，删除按钮单独放置，避免误触
        Column(
            modifier = Modifier
                .weight(1f)
                .pointerInput(record.address) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                val mouseEvent = event.awtEventOrNull as? MouseEvent
                                if (mouseEvent != null) {
                                    if (mouseEvent.clickCount >= 2) {
                                        onDoubleClick()
                                    } else if (mouseEvent.clickCount == 1) {
                                        onSelect()
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(vertical = 2.dp, horizontal = 2.dp)
        ) {
            Text(
                text = record.address,
                style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
                color = LightTextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${record.model ?: "未知型号"} · ${formatHistoryTime(record.lastConnectedAt)}",
                style = MaterialTheme.typography.body2.copy(fontSize = 10.sp),
                color = LightTextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除历史记录",
                tint = LightTextSub,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun formatHistoryTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "时间未知"
    return try {
        val dateTime = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        val formatter = if (dateTime.year == ZonedDateTime.now().year) {
            sameYearTimeFormatter
        } else {
            fullTimeFormatter
        }
        dateTime.format(formatter)
    } catch (e: Exception) {
        "时间未知"
    }
}
