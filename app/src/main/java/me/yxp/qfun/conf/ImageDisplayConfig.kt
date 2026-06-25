package me.yxp.qfun.conf

import kotlinx.serialization.Serializable

@Serializable
data class ImageDisplayConfig(
    val text: String = "图片外显",
    val fontSize: Int = 64,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val bgColor1: Int = 0xFF667eea.toInt(),
    val bgColor2: Int = 0xFF764ba2.toInt(),
    val padding: Int = 50,
    val cornerRadius: Int = 32,
    val useGradient: Boolean = true,
    val useShadow: Boolean = true,
    val trigger: String = "#外显",
    val autoMode: Boolean = false
)
