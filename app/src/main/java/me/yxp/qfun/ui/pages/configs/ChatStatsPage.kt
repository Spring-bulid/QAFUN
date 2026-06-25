package me.yxp.qfun.ui.pages.configs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.yxp.qfun.conf.ChatStatsData
import me.yxp.qfun.hook.troop.ChatStats
import me.yxp.qfun.ui.components.listitems.ActionItem
import me.yxp.qfun.ui.components.listitems.PreferenceSection
import me.yxp.qfun.ui.components.scaffold.ConfigPageScaffold
import me.yxp.qfun.ui.core.theme.AccentCyan
import me.yxp.qfun.ui.core.theme.AccentPurple
import me.yxp.qfun.ui.core.theme.QFunTheme

@Composable
fun ChatStatsPage(
    statsData: ChatStatsData,
    onSave: (ChatStatsData) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = QFunTheme.colors
    var viewingPeer by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember(viewingPeer) {
        mutableStateOf<Bitmap?>(null)
    }
    var showClearDialog by remember { mutableStateOf<String?>(null) }

    // 渲染仪表盘
    LaunchedEffect(viewingPeer) {
        val peer = viewingPeer
        if (peer != null) {
            val group = statsData.groups[peer]
            if (group != null && group.total > 0) {
                previewBitmap = runCatching { ChatStats.renderDashboard(peer, group, group.name) }.getOrNull()
            }
        }
    }

    if (viewingPeer != null) {
        val peer = viewingPeer!!
        val group = statsData.groups[peer]
        ConfigPageScaffold(
            title = "群聊统计仪表盘",
            configData = statsData,
            onSave = onSave,
            onDismiss = { viewingPeer = null }
        ) { _ ->
            previewBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "仪表盘",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                )
            } ?: run {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("渲染中...", color = colors.textSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            ActionItem(
                title = "清空本群统计",
                description = "删除该群所有统计数据",
                onClick = { showClearDialog = peer }
            )
        }
        showClearDialog?.let { peerToClear ->
            AlertDialog(
                onDismissRequest = { showClearDialog = null },
                title = { Text("清空统计") },
                text = { Text("确定要清空该群的所有统计数据吗？此操作不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        ChatStats.clearGroup(peerToClear)
                        showClearDialog = null
                        viewingPeer = null
                    }) { Text("确定清空") }
                },
                dismissButton = { TextButton(onClick = { showClearDialog = null }) { Text("取消") } }
            )
        }
        return
    }

    ConfigPageScaffold(
        title = "群聊统计仪表盘",
        configData = statsData,
        onSave = onSave,
        onDismiss = onDismiss
    ) { _ ->
        val groups = statsData.groups.filter { it.value.total > 0 }.toList().sortedByDescending { it.second.total }
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无统计数据", fontSize = 16.sp, color = colors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text("启用功能后在群聊中收发消息即可开始统计", fontSize = 12.sp, color = colors.textSecondary)
                }
            }
        } else {
            PreferenceSection(title = "已统计群聊 (${groups.size})") {
                groups.forEach { (peerUin, group) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.cardBackground)
                            .clickable { viewingPeer = peerUin }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.horizontalGradient(listOf(AccentPurple, AccentCyan))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = group.total.toString().take(3),
                                fontSize = 11.sp,
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.name.ifEmpty { "群 $peerUin" },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "${group.total} 条消息 · ${group.members.size} 人活跃",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        Text("›", fontSize = 20.sp, color = colors.textSecondary)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
