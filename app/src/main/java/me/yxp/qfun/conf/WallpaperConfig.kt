package me.yxp.qfun.conf

import kotlinx.serialization.Serializable

@Serializable
data class WallpaperConfig(
    val imagePath: String = "",
    val blurRadius: Int = 0,        // 0-25, 高斯模糊半径
    val opacity: Float = 1.0f,      // 0.0-1.0, 壁纸不透明度
    val dimAmount: Float = 0.0f,    // 0.0-1.0, 暗化遮罩强度（提升文字可读性）
    val scaleType: Int = 0          // 0=裁剪填充, 1=适应, 2=拉伸
)
