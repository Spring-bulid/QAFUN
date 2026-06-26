package me.yxp.qfun.hook.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.mobileqq.aio.msg.AIOMsgItem
import com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.common.ModuleScope
import me.yxp.qfun.hook.base.BaseSwitchHookItem
import me.yxp.qfun.loader.hookapi.Chain
import me.yxp.qfun.ui.core.compatibility.QFunBottomDialog
import me.yxp.qfun.utils.dexkit.DexKitTask
import me.yxp.qfun.utils.hook.hookReplace
import me.yxp.qfun.utils.hook.invokeOriginal
import me.yxp.qfun.utils.log.LogUtils
import me.yxp.qfun.utils.reflect.TAG
import me.yxp.qfun.utils.reflect.getObjectByType
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.base.BaseMatcher
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL

/**
 * QQ秀截取：点击聊天消息头像 → 弹出对方QQ秀面板，可预览/保存/打开。
 *
 * 复用 SimpleTroopManagement 的头像点击 DexKit 查询（同一个 OnClickListener），
 * 但对所有聊天场景生效。与简洁群管互补：
 *  - 群聊 + 群主/管理员 + 简洁群管已开 → 走群管面板（invokeOriginal 让出）
 *  - 其它场景 → 拦截点击，弹QQ秀面板
 *
 * QQ秀图片 URL（经典QQ秀，基于 uin 固定格式）：
 *  - 全身：https://qqshow-user.tencent.com/{uin}/10/00/
 *  - 半身：https://qqshow-user.tencent.com/{uin}/11/00/
 * 同时尝试从 MsgRecord.avatarMeta 解析超级QQ秀信息作为补充。
 */
@HookItemAnnotation(
    "QQ秀截取",
    "点击聊天消息头像即可查看对方QQ秀，支持保存图片和复制链接",
    HookCategory.CHAT
)
object StealQQShow : BaseSwitchHookItem(), DexKitTask {

    private lateinit var onClick: Method

    override fun onInit(): Boolean {
        onClick = requireClass("listener")
            .getDeclaredMethod("onClick", View::class.java)
        return super.onInit()
    }

    override fun onHook() {
        onClick.hookReplace(this) { param ->
            val listener = param.thisObject
            val view = param.args[0] as View

            // 从 OnClickListener → AIOAvatarContentComponent → AIOMsgItem → MsgRecord
            val msgRecord = runCatching {
                val component = listener.getObjectByType<AIOAvatarContentComponent>()
                val msgItem = component.getObjectByType<AIOMsgItem>()
                msgItem.msgRecord
            }.getOrNull() ?: return@hookReplace param.invokeOriginal()

            val activity = view.context as? Activity ?: return@hookReplace param.invokeOriginal()

            // 群聊 + 简洁群管已启用时让出（避免和群管面板冲突）
            if (msgRecord.chatType == 2 && isSimpleTroopManagementEnabled()) {
                return@hookReplace param.invokeOriginal()
            }

            runCatching { showQQShowSheet(activity, msgRecord) }
                .onFailure { LogUtils.e(TAG, it) }
            return@hookReplace null
        }
    }

    /** 检查简洁群管是否启用（运行时反射，避免硬依赖） */
    private fun isSimpleTroopManagementEnabled(): Boolean = runCatching {
        val clazz = Class.forName("me.yxp.qfun.hook.troop.SimpleTroopManagement")
        val instance = clazz.getDeclaredField("INSTANCE").get(null)
        val isEnableField = clazz.superclass.getDeclaredField("isEnable\$delegate")
        isEnableField.isAccessible = true
        val delegate = isEnableField.get(instance)
        val getValue = delegate.javaClass.methods.first { it.name == "getValue" }
        getValue.invoke(delegate, instance, null) as? Boolean ?: false
    }.getOrDefault(false)

    private fun showQQShowSheet(activity: Activity, msgRecord: MsgRecord) {
        val senderUin = msgRecord.senderUin.toString()
        val peerUin = msgRecord.peerUin.toString()
        val chatType = msgRecord.chatType
        val nick = msgRecord.sendMemberName.ifEmpty { msgRecord.sendNickName }
        val avatarMeta = msgRecord.avatarMeta ?: ""

        // 对方QQ号：私聊用peerUin（可靠），群聊用senderUin（可能为0，需反查）
        val targetUin = when {
            senderUin.isNotEmpty() && senderUin != "0" -> senderUin
            chatType == 1 -> peerUin
            else -> senderUin.ifEmpty { peerUin }
        }

        QFunBottomDialog(activity) { dismiss ->
            QQShowContent(
                activity = activity,
                targetUin = targetUin,
                nick = nick,
                avatarMeta = avatarMeta,
                onDismiss = dismiss
            )
        }.show()
    }

    override fun getQueryMap(): Map<String, BaseMatcher> = mapOf(
        "listener" to FindClass().apply {
            searchPackages("com.tencent.mobileqq.aio.msglist.holder.component.avatar")
            matcher {
                addInterface(View.OnClickListener::class.java.name)
                methods { add { name("onClick") } }
            }
        }
    )
}

// ==================== QQ秀 URL 与图片下载 ====================

/** 经典QQ秀全身图URL */
private fun classicQQShowUrl(uin: String): String =
    "https://qqshow-user.tencent.com/$uin/10/00/"

/** 经典QQ秀半身图URL */
private fun classicQQShowHalfUrl(uin: String): String =
    "https://qqshow-user.tencent.com/$uin/11/00/"

/** 下载图片为 Bitmap（挂起函数） */
private suspend fun downloadBitmap(urlStr: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        conn.connect()
        if (conn.responseCode != 200) return@runCatching null
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

/** 保存 Bitmap 到相册，返回文件路径 */
private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, uin: String): String? = runCatching {
    val dir = File(Environment.getExternalStorageDirectory(), "DCIM/QQShow")
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "qqshow_$uin.jpg")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
    }
    // 通知相册刷新
    val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
        data = Uri.fromFile(file)
    }
    context.sendBroadcast(intent)
    file.absolutePath
}.getOrNull()

// ==================== 弹窗 UI ====================

@Composable
private fun QQShowContent(
    activity: Activity,
    targetUin: String,
    nick: String,
    avatarMeta: String,
    onDismiss: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentUrl by remember { mutableStateOf(classicQQShowUrl(targetUin)) }

    // 加载QQ秀图片
    LaunchedEffect(currentUrl) {
        loading = true
        error = null
        val bmp = downloadBitmap(currentUrl)
        if (bmp != null) {
            bitmap = bmp
        } else {
            error = "加载失败"
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "QQ秀截取",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF7C5CFF),
            modifier = Modifier.fillMaxWidth()
        )

        // 用户信息卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("QQ号：$targetUin", fontSize = 14.sp, color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium)
                if (nick.isNotEmpty()) {
                    Text("昵称：$nick", fontSize = 13.sp, color = Color(0xFF666666))
                }
            }
        }

        // QQ秀图片预览
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF))
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loading -> CircularProgressIndicator(color = Color(0xFF7C5CFF))
                    bitmap != null -> {
                        val imgBitmap = bitmap!!.asImageBitmap()
                        Image(
                            bitmap = imgBitmap,
                            contentDescription = "QQ秀",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    else -> Text(error ?: "加载失败", color = Color(0xFF999999))
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 切换全身/半身
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { currentUrl = classicQQShowUrl(targetUin) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("全身", fontSize = 12.sp) }

            OutlinedButton(
                onClick = { currentUrl = classicQQShowHalfUrl(targetUin) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("半身", fontSize = 12.sp) }
        }

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { copyToClipboard(activity, currentUrl) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("复制链接", fontSize = 12.sp) }

            Button(
                onClick = {
                    val bmp = bitmap
                    if (bmp != null) {
                        ModuleScope.launchMain {
                            val path = saveBitmapToGallery(activity, bmp, targetUin)
                            me.yxp.qfun.utils.qq.Toasts.qqToast(
                                2,
                                if (path != null) "已保存到 $path" else "保存失败"
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                enabled = bitmap != null
            ) { Text("保存图片", fontSize = 12.sp) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onDismiss()
                    openInBrowser(activity, currentUrl)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("浏览器打开", fontSize = 12.sp) }

            OutlinedButton(
                onClick = { onDismiss() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("关闭", fontSize = 12.sp) }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qfun", text))
        me.yxp.qfun.utils.qq.Toasts.qqToast(2, "已复制链接")
    }
}

private fun openInBrowser(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        me.yxp.qfun.utils.qq.Toasts.qqToast(1, "打开失败")
    }
}
