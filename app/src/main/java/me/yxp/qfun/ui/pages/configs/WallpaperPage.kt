package me.yxp.qfun.ui.pages.configs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.yxp.qfun.conf.WallpaperConfig
import me.yxp.qfun.ui.components.listitems.PreferenceSection
import me.yxp.qfun.ui.components.scaffold.ConfigPageScaffold
import me.yxp.qfun.ui.core.theme.AccentPurple
import me.yxp.qfun.ui.core.theme.QFunTheme
import java.io.File

@Composable
fun WallpaperPage(
    currentConfig: WallpaperConfig,
    onSave: (WallpaperConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var tempConfig by remember(currentConfig) { mutableStateOf(currentConfig) }
    val colors = QFunTheme.colors
    val context = LocalContext.current

    // 图片选择器：选择后将图片复制到模块私有目录，存储绝对路径
    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val target = File(context.filesDir, "qfun_wallpaper.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                tempConfig = tempConfig.copy(imagePath = target.absolutePath)
            }
        }
    }

    // 预览 Bitmap（仅解码原图，效果通过 Compose 叠加层模拟）
    val previewBitmap = remember(tempConfig.imagePath) {
        loadPreview(tempConfig.imagePath)
    }

    ConfigPageScaffold(
        title = "桌面壁纸配置",
        configData = tempConfig,
        onSave = onSave,
        onDismiss = onDismiss
    ) {
        // ==================== 预览区 ====================
        PreferenceSection(title = "实时预览") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.background)
                    .border(1.dp, colors.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "壁纸预览",
                        contentScale = when (tempConfig.scaleType) {
                            1    -> ContentScale.Fit
                            2    -> ContentScale.FillBounds
                            else -> ContentScale.Crop
                        },
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    )
                    // 模糊预览（通过半透明黑层近似，真实模糊在应用时生效）
                    if (tempConfig.blurRadius > 0) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = (tempConfig.blurRadius / 25f) * 0.25f))
                        )
                    }
                    // 透明度遮罩
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - tempConfig.opacity)))
                    // 暗化遮罩
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = tempConfig.dimAmount)))
                } ?: Text("未选择壁纸，点击下方按钮选择图片", color = colors.textSecondary)
            }
        }

        // ==================== 图片选择 ====================
        PreferenceSection(title = "壁纸图片") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(
                    text = "选择图片",
                    primary = true,
                    modifier = Modifier.weight(1f)
                ) { pickLauncher.launch("image/*") }
                if (tempConfig.imagePath.isNotEmpty()) {
                    ActionButton(
                        text = "清除",
                        primary = false,
                        modifier = Modifier.weight(1f)
                    ) { tempConfig = tempConfig.copy(imagePath = "") }
                }
            }
            if (tempConfig.imagePath.isNotEmpty()) {
                Text(
                    text = tempConfig.imagePath,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // ==================== 效果调整 ====================
        PreferenceSection(title = "效果调整") {
            SliderItem(
                label = "模糊半径",
                valueText = "${tempConfig.blurRadius}",
                value = tempConfig.blurRadius.toFloat(),
                range = 0f..25f,
                onValueChange = { tempConfig = tempConfig.copy(blurRadius = it.toInt()) }
            )
            SliderItem(
                label = "不透明度",
                valueText = "${(tempConfig.opacity * 100).toInt()}%",
                value = tempConfig.opacity,
                range = 0f..1f,
                onValueChange = { tempConfig = tempConfig.copy(opacity = it) }
            )
            SliderItem(
                label = "暗化遮罩",
                valueText = "${(tempConfig.dimAmount * 100).toInt()}%",
                value = tempConfig.dimAmount,
                range = 0f..1f,
                onValueChange = { tempConfig = tempConfig.copy(dimAmount = it) }
            )
        }

        // ==================== 缩放方式 ====================
        PreferenceSection(title = "缩放方式") {
            val scaleTypes = listOf("裁剪填充", "适应", "拉伸")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scaleTypes.forEachIndexed { idx, name ->
                    val selected = tempConfig.scaleType == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) AccentPurple else colors.cardBackground)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { tempConfig = tempConfig.copy(scaleType = idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name,
                            fontSize = 12.sp,
                            color = if (selected) Color.White else colors.textPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // 使用提示
        Text(
            text = "提示：保存后返回 QQ 即可生效，无需重启。壁纸将应用于 QQ 所有界面。",
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = QFunTheme.colors
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) AccentPurple else colors.cardBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) Color.White else colors.textPrimary
        )
    }
}

@Composable
private fun SliderItem(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val colors = QFunTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, color = colors.textPrimary)
            Text(valueText, fontSize = 14.sp, color = colors.textSecondary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

private fun loadPreview(path: String): Bitmap? {
    if (path.isEmpty() || !File(path).exists()) return null
    return runCatching {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()
}
