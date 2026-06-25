package me.yxp.qfun.ui.core.theme

import androidx.compose.ui.graphics.Color

// ============ QAFUN 全新色彩体系 ============
// 主色调：电光紫 + 霓虹青，营造科技未来感

// ---------- 亮色主题 ----------
val LightBackground = Color(0xFFF2F3F7)
val LightCardBackground = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF1A1B2E)
val LightTextSecondary = Color(0xFF7A7C8A)
val LightRipple = Color(0x14000000)

// ---------- 暗色主题 ----------
val DarkBackground = Color(0xFF0A0B14)
val DarkCardBackground = Color(0xFF161827)
val DarkTextPrimary = Color(0xFFEAEBF5)
val DarkTextSecondary = Color(0xFF8B8DA3)
val DarkRipple = Color(0x14FFFFFF)

// ---------- 强调色 (QAFUN 品牌色) ----------
// 主品牌色：电光紫
val AccentPurple = Color(0xFF7C5CFF)
val AccentPurpleDark = Color(0xFF8E72FF)
// 次品牌色：霓虹青
val AccentCyan = Color(0xFF00D9C0)
val AccentCyanDark = Color(0xFF1AEBD4)
// 兼容旧引用
val AccentGreen = AccentCyan
val AccentGreenDark = AccentCyanDark
val AccentBlue = AccentPurple
val AccentRed = Color(0xFFFF5470)
val AccentOrange = Color(0xFFFFA552)
val AccentPink = Color(0xFFFF6BCB)

// ---------- 开关色彩 ----------
val SwitchThumbOff = Color(0xFFFFFFFF)
val SwitchThumbOn = Color(0xFFFFFFFF)
val SwitchTrackOff = Color(0xFFE3E4ED)
val SwitchTrackOffDark = Color(0xFF2A2D40)
val SwitchTrackOn = AccentPurple
val SwitchTrackOnDark = AccentPurpleDark

// ---------- 渐变色组 (用于品牌元素) ----------
val BrandGradientStart = Color(0xFF7C5CFF)
val BrandGradientEnd = Color(0xFF00D9C0)
val CardGradientStartDark = Color(0xFF1E2033)
val CardGradientEndDark = Color(0xFF161827)
