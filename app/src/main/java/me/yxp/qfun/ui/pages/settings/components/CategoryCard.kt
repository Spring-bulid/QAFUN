package me.yxp.qfun.ui.pages.settings.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.yxp.qfun.ui.components.atoms.QFunCard
import me.yxp.qfun.ui.core.theme.BrandGradientEnd
import me.yxp.qfun.ui.core.theme.BrandGradientStart
import me.yxp.qfun.ui.core.theme.QFunTheme

@Composable
fun CategoryCard(
    name: String,
    totalCount: Int,
    enabledCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = QFunTheme.colors

    QFunCard(modifier = modifier.fillMaxWidth(), animateContentSize = false, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp, 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 品牌渐变指示条
            Box(
                Modifier
                    .size(width = 5.dp, height = 38.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(BrandGradientStart, BrandGradientEnd)
                        )
                    )
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$totalCount 功能",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                    if (enabledCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(BrandGradientStart.copy(0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "已开 $enabledCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.accentPurple
                            )
                        }
                    }
                }
            }
            Text("›", fontSize = 26.sp, color = colors.accentPurple.copy(0.5f))
        }
    }
}
