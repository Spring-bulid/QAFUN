package me.yxp.qfun.ui.pages.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.yxp.qfun.R
import me.yxp.qfun.ui.components.atoms.QFunCard
import me.yxp.qfun.ui.core.theme.AccentCyan
import me.yxp.qfun.ui.core.theme.AccentPurple
import me.yxp.qfun.ui.core.theme.AccentRed
import me.yxp.qfun.ui.core.theme.BrandGradientEnd
import me.yxp.qfun.ui.core.theme.BrandGradientStart
import me.yxp.qfun.ui.core.theme.QFunTheme

@Composable
fun MainScreen(
    versionName: String,
    versionCode: Int,
    isActivated: Boolean,
    frameworkInfo: String,
    isIconVisible: Boolean,
    onToggleIcon: () -> Unit,
    onTelegramClick: () -> Unit,
    onGithubClick: () -> Unit,
    onQQGroupClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = QFunTheme.colors
    val iconButtonAlpha by animateFloatAsState(
        targetValue = if (isIconVisible) 1f else 0.6f,
        animationSpec = tween(durationMillis = 200),
        label = "iconAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        BrandGradientStart.copy(0.12f),
                        colors.background
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // === 品牌标题区 ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(BrandGradientStart, BrandGradientEnd)
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text(
                        "QAFUN",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "v$versionName · $versionCode",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary
                )
            }
            QFunCard(
                modifier = Modifier.alpha(iconButtonAlpha),
                animateContentSize = false,
                onClick = onToggleIcon
            ) {
                Text(
                    if (isIconVisible) "隐藏图标" else "显示图标",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(16.dp, 10.dp)
                )
            }
        }

        // === 激活状态卡片 (渐变) ===
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.horizontalGradient(
                        if (isActivated) {
                            listOf(BrandGradientStart, BrandGradientEnd)
                        } else {
                            listOf(
                                AccentRed.copy(0.85f),
                                AccentRed.copy(0.6f)
                            )
                        }
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painterResource(if (isActivated) R.drawable.ic_logo_check else R.drawable.ic_logo_unchecked),
                    null,
                    Modifier.size(52.dp),
                    androidx.compose.ui.graphics.Color.White
                )
                Spacer(modifier = Modifier.width(20.dp))
                Column {
                    Text(
                        if (isActivated) "已激活" else "未激活",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        frameworkInfo,
                        fontSize = 13.sp,
                        color = androidx.compose.ui.graphics.Color.White.copy(0.9f),
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // === 链接卡片 ===
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LinkCard(
                R.drawable.ic_logo_telegram,
                "Telegram Channel",
                "获取最新更新和公告",
                onTelegramClick
            )
            LinkCard(
                R.drawable.ic_logo_github,
                "Github Repository",
                "查看源码和提交问题",
                onGithubClick
            )
            LinkCard(R.drawable.ic_logo_qq, "QQ交流群", "加入社区讨论", onQQGroupClick)
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun LinkCard(iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = QFunTheme.colors

    QFunCard(modifier = Modifier.fillMaxWidth(), animateContentSize = false, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                BrandGradientStart.copy(0.15f),
                                BrandGradientEnd.copy(0.15f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(iconRes),
                    title,
                    Modifier.size(24.dp),
                    colors.accentPurple
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, fontSize = 13.sp, color = colors.textSecondary)
            }
        }
    }
}
