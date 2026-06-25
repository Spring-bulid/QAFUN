package me.yxp.qfun.ui.pages.configs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.yxp.qfun.conf.ImageDisplayConfig
import me.yxp.qfun.hook.chat.ImageDisplay
import me.yxp.qfun.ui.components.listitems.InputItem
import me.yxp.qfun.ui.components.listitems.PreferenceSection
import me.yxp.qfun.ui.components.listitems.SwitchItem
import me.yxp.qfun.ui.components.scaffold.ConfigPageScaffold
import me.yxp.qfun.ui.core.theme.AccentCyan
import me.yxp.qfun.ui.core.theme.AccentPurple
import me.yxp.qfun.ui.core.theme.QFunTheme

@Composable
fun ImageDisplayPage(
    currentConfig: ImageDisplayConfig,
    onSave: (ImageDisplayConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var tempConfig by remember(currentConfig) { mutableStateOf(currentConfig) }
    val colors = QFunTheme.colors
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(tempConfig.text, tempConfig.fontSize, tempConfig.textColor,
        tempConfig.bgColor1, tempConfig.bgColor2, tempConfig.useGradient,
        tempConfig.useShadow, tempConfig.padding, tempConfig.cornerRadius) {
        previewBitmap = runCatching {
            ImageDisplay.renderTextToBitmap(tempConfig.text, tempConfig)
        }.getOrNull()
    }

    ConfigPageScaffold(
        title = "图片外显配置",
        configData = tempConfig,
        onSave = onSave,
        onDismiss = onDismiss
    ) { _ ->
        // 预览区
        PreferenceSection(title = "实时预览") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(AccentPurple.copy(0.14f), colors.background)
                        )
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "预览",
                        modifier = Modifier
                            .width(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } ?: Text("渲染中...", color = colors.textSecondary)
            }
        }

        PreferenceSection(title = "文字内容") {
            InputItem(
                title = "外显文字",
                value = tempConfig.text,
                onValueChange = { tempConfig = tempConfig.copy(text = it) },
                placeholder = "输入要外显的文字"
            )
        }

        PreferenceSection(title = "样式") {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("字体大小: ${tempConfig.fontSize}px", fontSize = 14.sp, color = colors.textPrimary)
                Slider(
                    value = tempConfig.fontSize.toFloat(),
                    onValueChange = { tempConfig = tempConfig.copy(fontSize = it.toInt()) },
                    valueRange = 24f..128f
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("内边距: ${tempConfig.padding}px", fontSize = 14.sp, color = colors.textPrimary)
                Slider(
                    value = tempConfig.padding.toFloat(),
                    onValueChange = { tempConfig = tempConfig.copy(padding = it.toInt()) },
                    valueRange = 20f..100f
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("圆角: ${tempConfig.cornerRadius}px", fontSize = 14.sp, color = colors.textPrimary)
                Slider(
                    value = tempConfig.cornerRadius.toFloat(),
                    onValueChange = { tempConfig = tempConfig.copy(cornerRadius = it.toInt()) },
                    valueRange = 0f..64f
                )
            }
            SwitchItem(
                title = "渐变背景",
                description = "使用双色渐变，关闭则为纯色",
                checked = tempConfig.useGradient,
                onCheckedChange = { tempConfig = tempConfig.copy(useGradient = it) }
            )
            SwitchItem(
                title = "文字阴影",
                description = "为文字添加投影效果",
                checked = tempConfig.useShadow,
                onCheckedChange = { tempConfig = tempConfig.copy(useShadow = it) }
            )
        }

        PreferenceSection(title = "触发方式") {
            InputItem(
                title = "触发词",
                value = tempConfig.trigger,
                onValueChange = { tempConfig = tempConfig.copy(trigger = it) },
                placeholder = "如 #外显"
            )
            Text(
                text = "在聊天框输入「触发词 + 文字」即可转为图片发送",
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            SwitchItem(
                title = "自动模式",
                description = "所有文字消息都转为图片发送",
                checked = tempConfig.autoMode,
                onCheckedChange = { tempConfig = tempConfig.copy(autoMode = it) }
            )
        }

        // 渐变色预设
        PreferenceSection(title = "渐变预设") {
            val presets = listOf(
                Triple("紫梦", 0xFF667eea.toInt(), 0xFF764ba2.toInt()),
                Triple("海洋", 0xFF2193b0.toInt(), 0xFF6dd5ed.toInt()),
                Triple("日落", 0xFFff6e7f.toInt(), 0xFFbfe9ff.toInt()),
                Triple("森林", 0xFF11998e.toInt(), 0xFF38ef7d.toInt()),
                Triple("暗夜", 0xFF232526.toInt(), 0xFF414345.toInt()),
                Triple("烈焰", 0xFFf12711.toInt(), 0xFFf5af19.toInt())
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (name, c1, c2) ->
                    val selected = tempConfig.bgColor1 == c1 && tempConfig.bgColor2 == c2
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.horizontalGradient(listOf(Color(c1), Color(c2))))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                tempConfig = tempConfig.copy(bgColor1 = c1, bgColor2 = c2)
                            }
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name,
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
