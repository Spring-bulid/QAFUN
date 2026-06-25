package me.yxp.qfun.hook.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.Composable
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.conf.WallpaperConfig
import me.yxp.qfun.hook.base.BaseClickableHookItem
import me.yxp.qfun.ui.pages.configs.WallpaperPage
import me.yxp.qfun.utils.hook.hookAfter
import me.yxp.qfun.utils.log.LogUtils
import me.yxp.qfun.utils.reflect.TAG
import java.io.File
import java.lang.ref.SoftReference
import kotlin.concurrent.thread

/**
 * 自定义桌面壁纸：为 QQ 所有界面注入自定义壁纸背景。
 *
 * QQNT 采用单 Activity(QPublicFragmentActivity) + Fragment 架构，
 * Fragment 根 View 有自己的不透明背景，会盖住 contentView 的 background。
 *
 * 实现思路：
 * 1. Hook Activity.onPostResume —— Fragment view 此时已生成并完成事务提交
 * 2. 用 content.post {} 等待布局完成
 * 3. 在 android.R.id.content 的 index 0 插入 ImageView 作为壁纸层（最底层）
 * 4. 清除 Window/content/Fragment根 的不透明背景，让壁纸透出
 * 5. 已处理 Bitmap 用 SoftReference 缓存，后台线程解码避免卡顿
 */
@HookItemAnnotation(
    "自定义桌面壁纸",
    "为QQ所有界面设置自定义壁纸，支持模糊、透明度与暗化遮罩",
    HookCategory.OTHER
)
object CustomWallpaper : BaseClickableHookItem<WallpaperConfig>(WallpaperConfig.serializer()) {

    override val defaultConfig: WallpaperConfig = WallpaperConfig()

    private const val TAG_WALLPAPER = "qfun_wallpaper_layer"

    @Volatile private var cacheKey: String = ""
    @Volatile private var cachedBmpRef: SoftReference<Bitmap>? = null

    private val activityOnPostResume by lazy {
        Activity::class.java.getDeclaredMethod("onPostResume")
    }

    override fun onHook() {
        runCatching {
            activityOnPostResume.hookAfter(this) { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfter
                val className = activity.javaClass.name
                if (className.startsWith("me.yxp.qfun")) return@hookAfter
                if (!className.contains("tencent", ignoreCase = true) &&
                    !className.contains("qq", ignoreCase = true)
                ) return@hookAfter
                applyWallpaper(activity)
            }
        }.onFailure { LogUtils.e(TAG, RuntimeException("CustomWallpaper hook install failed", it)) }
    }

    private fun applyWallpaper(activity: Activity) {
        val cfg = config
        if (cfg.imagePath.isEmpty() || !File(cfg.imagePath).exists()) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // 等待布局完成，Fragment 根 View 此时已就位
        content.post {
            runCatching { installWallpaperLayer(activity, content, cfg) }
                .onFailure { LogUtils.e(TAG, it) }
        }
    }

    private fun installWallpaperLayer(
        activity: Activity,
        content: ViewGroup,
        cfg: WallpaperConfig
    ) {
        // 1. 先确保壁纸 ImageView 已插入到 content 的 index 0
        var wallpaperView: ImageView? = content.findViewWithTag(TAG_WALLPAPER)
        if (wallpaperView == null) {
            wallpaperView = ImageView(activity).apply {
                tag = TAG_WALLPAPER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = when (cfg.scaleType) {
                    1    -> ImageView.ScaleType.FIT_CENTER   // 适应
                    2    -> ImageView.ScaleType.FIT_XY       // 拉伸
                    else -> ImageView.ScaleType.CENTER_CROP  // 裁剪填充（默认）
                }
                setImageDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            content.addView(wallpaperView, 0)
        } else {
            wallpaperView.scaleType = when (cfg.scaleType) {
                1    -> ImageView.ScaleType.FIT_CENTER
                2    -> ImageView.ScaleType.FIT_XY
                else -> ImageView.ScaleType.CENTER_CROP
            }
        }

        // 2. 清除上层不透明背景，让壁纸透出
        activity.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        content.background = null
        // content.getChildAt(1) 是原 Fragment 根 View（index 0 已是壁纸层）
        if (content.childCount > 1) {
            val fragRoot = content.getChildAt(1)
            if (fragRoot.background is ColorDrawable) {
                fragRoot.background = null
            }
        }

        // 3. 加载并设置壁纸 Bitmap
        val key = "${cfg.imagePath}|${cfg.blurRadius}|${cfg.dimAmount}"
        if (key == cacheKey) {
            cachedBmpRef?.get()?.let { bmp ->
                wallpaperView.setImageBitmap(bmp)
                wallpaperView.alpha = cfg.opacity.coerceIn(0f, 1f)
                return
            }
        }
        // 占位透明，后台解码后回主线程设置
        wallpaperView.setImageDrawable(ColorDrawable(Color.TRANSPARENT))
        thread(name = "qfun-wallpaper-decode") {
            val bmp = runCatching { obtainBitmap(cfg) }.getOrNull() ?: return@thread
            activity.runOnUiThread {
                runCatching {
                    wallpaperView.setImageBitmap(bmp)
                    wallpaperView.alpha = cfg.opacity.coerceIn(0f, 1f)
                }.onFailure { LogUtils.e(TAG, it) }
            }
        }
    }

    private fun obtainBitmap(cfg: WallpaperConfig): Bitmap? {
        val key = "${cfg.imagePath}|${cfg.blurRadius}|${cfg.dimAmount}"
        if (key == cacheKey) {
            cachedBmpRef?.get()?.let { return it }
        }
        val raw = decode(cfg.imagePath) ?: return null
        val blurred = if (cfg.blurRadius > 0) boxBlur(raw, cfg.blurRadius) else raw
        val dimmed = if (cfg.dimAmount > 0f) applyDim(blurred, cfg.dimAmount) else blurred
        cacheKey = key
        cachedBmpRef = SoftReference(dimmed)
        return dimmed
    }

    private fun decode(path: String): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, opts)
        val target = 1280
        var sample = 1
        val maxEdge = maxOf(opts.outWidth, opts.outHeight)
        while (maxEdge / sample > target) sample *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sample
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()

    private fun applyDim(bmp: Bitmap, amount: Float): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val alpha = (amount.coerceIn(0f, 1f) * 255).toInt()
        Canvas(out).drawColor(Color.argb(alpha, 0, 0, 0))
        return out
    }

    /**
     * 分离式盒模糊（水平+垂直两次扫描），滑动窗口求和，O(w*h)。
     */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val w = src.width
        val h = src.height
        if (w == 0 || h == 0) return src
        val r = radius.coerceIn(1, 25)
        val win = 2 * r + 1

        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val tmpPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)

        for (y in 0 until h) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val xx = k.coerceIn(0, w - 1)
                val p = srcPixels[y * w + xx]
                a += (p ushr 24) and 0xff
                rr += (p ushr 16) and 0xff
                g += (p ushr 8) and 0xff
                b += p and 0xff
            }
            for (x in 0 until w) {
                tmpPixels[y * w + x] = pack(a / win, rr / win, g / win, b / win)
                val xOut = (x - r).coerceIn(0, w - 1)
                val xIn = (x + r + 1).coerceIn(0, w - 1)
                val pOut = srcPixels[y * w + xOut]
                val pIn = srcPixels[y * w + xIn]
                a += ((pIn ushr 24) and 0xff) - ((pOut ushr 24) and 0xff)
                rr += ((pIn ushr 16) and 0xff) - ((pOut ushr 16) and 0xff)
                g += ((pIn ushr 8) and 0xff) - ((pOut ushr 8) and 0xff)
                b += (pIn and 0xff) - (pOut and 0xff)
            }
        }
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val yy = k.coerceIn(0, h - 1)
                val p = tmpPixels[yy * w + x]
                a += (p ushr 24) and 0xff
                rr += (p ushr 16) and 0xff
                g += (p ushr 8) and 0xff
                b += p and 0xff
            }
            for (y in 0 until h) {
                outPixels[y * w + x] = pack(a / win, rr / win, g / win, b / win)
                val yOut = (y - r).coerceIn(0, h - 1)
                val yIn = (y + r + 1).coerceIn(0, h - 1)
                val pOut = tmpPixels[yOut * w + x]
                val pIn = tmpPixels[yIn * w + x]
                a += ((pIn ushr 24) and 0xff) - ((pOut ushr 24) and 0xff)
                rr += ((pIn ushr 16) and 0xff) - ((pOut ushr 16) and 0xff)
                g += ((pIn ushr 8) and 0xff) - ((pOut ushr 8) and 0xff)
                b += (pIn and 0xff) - (pOut and 0xff)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xff) shl 24) or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)

    @Composable
    override fun ConfigContent(onDismiss: () -> Unit) {
        WallpaperPage(
            currentConfig = config,
            onSave = ::updateConfig,
            onDismiss = onDismiss
        )
    }
}
