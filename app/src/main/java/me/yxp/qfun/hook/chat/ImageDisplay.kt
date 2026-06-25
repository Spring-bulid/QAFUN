package me.yxp.qfun.hook.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Composable
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.conf.ImageDisplayConfig
import me.yxp.qfun.hook.api.MenuClickListener
import me.yxp.qfun.hook.base.BaseClickableHookItem
import me.yxp.qfun.plugin.bean.MsgData
import me.yxp.qfun.ui.pages.configs.ImageDisplayPage
import me.yxp.qfun.utils.hook.hookBefore
import me.yxp.qfun.utils.io.FileUtils
import me.yxp.qfun.utils.log.LogUtils
import me.yxp.qfun.utils.qq.MsgTool
import me.yxp.qfun.utils.qq.QQCurrentEnv
import me.yxp.qfun.utils.qq.Toasts
import me.yxp.qfun.utils.reflect.TAG
import me.yxp.qfun.utils.reflect.findMethod
import java.io.File
import java.io.FileOutputStream

@HookItemAnnotation(
    "图片外显",
    "将文字渲染为精美渐变图片发送，支持触发词自动转换与长按消息转换",
    HookCategory.CHAT
)
object ImageDisplay : BaseClickableHookItem<ImageDisplayConfig>(ImageDisplayConfig.serializer()),
    MenuClickListener {

    override val defaultConfig: ImageDisplayConfig = ImageDisplayConfig()

    override val menuKey: String
        get() = if (isEnable) "[QFun],ImageDisplay,转为外显图片,,2,9" else ""

    private val sendMsgMethod by lazy {
        IKernelMsgService.CppProxy::class.java.findMethod { name = "sendMsg" }
    }

    override fun onHook() {
        // 拦截发送消息：触发词模式 / 自动模式 -> 替换为图片元素
        sendMsgMethod.hookBefore(this) { param ->
            if (!isEnable) return@hookBefore
            val elements = param.args[2] as? ArrayList<MsgElement> ?: return@hookBefore
            if (elements.isEmpty()) return@hookBefore

            val text = StringBuilder()
            for (e in elements) {
                runCatching { e.textElement?.content?.let { text.append(it) } }
            }
            val fullText = text.toString()

            val shouldConvert = config.autoMode || fullText.startsWith(config.trigger)
            if (!shouldConvert) return@hookBefore

            val displayText = if (config.autoMode) {
                fullText.ifEmpty { config.text }
            } else {
                fullText.substring(config.trigger.length).trim().ifEmpty { config.text }
            }

            val contact = param.args[1] as? Contact ?: return@hookBefore

            // 同步渲染图片并替换消息元素
            runCatching {
                val path = generateImageFile(displayText)
                val picElement = MsgTool.createPicElement(path) ?: return@runCatching
                elements.clear()
                elements.add(picElement)
            }.onFailure {
                LogUtils.e(TAG, it)
            }
        }
    }

    override fun onClick(msgData: MsgData) {
        val raw = msgData.msg.replace(Regex("\\[pic=[^\\]]*\\]"), "").trim()
        if (raw.isEmpty()) {
            Toasts.qqToast(1, "该消息无文本内容")
            return
        }
        runCatching {
            val path = generateImageFile(raw)
            MsgTool.sendPic(msgData.contact, path)
            Toasts.qqToast(2, "外显图片已发送")
        }.onFailure {
            Toasts.qqToast(1, "发送失败: ${it.message}")
            LogUtils.e(TAG, it)
        }
    }

    // ==================== 图片渲染引擎 ====================

    fun renderTextToBitmap(text: String, cfg: ImageDisplayConfig = config): Bitmap {
        val content = text.ifEmpty { " " }

        val textPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = cfg.fontSize.toFloat()
            color = cfg.textColor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (cfg.useShadow) {
                setShadowLayer(10f, 2f, 2f, 0x80000000)
            }
        }

        val maxWidth = 800
        val staticLayout = StaticLayout(
            content, textPaint, maxWidth,
            Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false
        )
        val textWidth = staticLayout.width.toInt()
        val textHeight = staticLayout.height.toInt()

        val p = cfg.padding
        var bitmapWidth = textWidth + p * 2
        var bitmapHeight = textHeight + p * 2
        if (bitmapWidth < 200) bitmapWidth = 200
        if (bitmapHeight < 100) bitmapHeight = 100

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgRect = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
        val bgPaint = Paint().apply { isAntiAlias = true }
        if (cfg.useGradient) {
            bgPaint.shader = LinearGradient(
                0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(),
                cfg.bgColor1, cfg.bgColor2, Shader.TileMode.CLAMP
            )
        } else {
            bgPaint.color = cfg.bgColor1
        }
        canvas.drawRoundRect(bgRect, cfg.cornerRadius.toFloat(), cfg.cornerRadius.toFloat(), bgPaint)

        canvas.save()
        val textX = (bitmapWidth - textWidth) / 2.0f
        canvas.translate(textX, p.toFloat())
        staticLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    fun generateImageFile(text: String): String {
        val bitmap = renderTextToBitmap(text)
        val cacheDir = File("${QQCurrentEnv.currentDir}cache/image_display").apply { mkdirs() }
        val file = File(cacheDir, "img_${System.currentTimeMillis()}.png")
        FileUtils.ensureFile(file)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    @Composable
    override fun ConfigContent(onDismiss: () -> Unit) {
        ImageDisplayPage(
            currentConfig = config,
            onSave = ::updateConfig,
            onDismiss = onDismiss
        )
    }
}
