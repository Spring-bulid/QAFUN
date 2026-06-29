package me.yxp.qfun.hook.file

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.qqnt.kernel.nativeinterface.FileElement
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.common.ModuleScope
import me.yxp.qfun.hook.api.MenuClickListener
import me.yxp.qfun.hook.base.BaseSwitchHookItem
import me.yxp.qfun.plugin.bean.MsgData
import me.yxp.qfun.ui.core.compatibility.QFunBottomDialog
import me.yxp.qfun.utils.log.LogUtils
import me.yxp.qfun.utils.qq.QQCurrentEnv
import me.yxp.qfun.utils.reflect.TAG
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL

/**
 * 文件转直链：长按文件消息 → 弹出面板展示文件元信息 + 尝试获取下载直链。
 *
 * 实现策略：
 *  - 通过 MenuClickListener 复用 OnMenuBuild 的菜单扩展入口，菜单项「文件直链」对全消息类型显示，
 *    onClick 内判断是否含 FileElement，不含则提示「非文件消息」。
 *  - 同步展示：文件名、大小、MD5、SHA、UUID、本地路径（已下载时）。
 *  - 群聊场景（chatType==2）：异步反射 IKernelRichMediaService.getGroupFileInfo(groupCode, fileUuid, callback)
 *    尝试获取直链；回调对象结构因 QQ 版本而异，做 best-effort 字段提取 + dump 日志。
 *  - 私聊场景：FileElement 无 fileUrl 字段，私聊文件直链需用户手动下载后从本地路径获取。
 *
 * 限制：NTQQ 的 FileElement 没有内置 fileUrl 字段，群文件直链依赖 kernel 回调对象结构，
 * 不同 QQ 版本可能字段名不同（url / fileUrl / downloadUrl / downloadAddr）。
 */
@HookItemAnnotation(
    "文件转直链",
    "长按文件消息获取文件下载直链、MD5/SHA/UUID 及本地路径",
    HookCategory.FILE
)
object FileDirectLink : BaseSwitchHookItem(), MenuClickListener {

    /** 全消息类型生效，onClick 内判断是否含 FileElement */
    override val menuKey: String
        get() = if (isEnable) "[QFun],FileDirectLink,文件直链,," else ""

    override fun onClick(msgData: MsgData) {
        val activity = QQCurrentEnv.activity ?: run {
            LogUtils.d("$TAG: activity is null")
            return
        }
        // 取第一个 FileElement
        val fileElement = msgData.data.elements.firstOrNull { it.elementType == 3 }?.fileElement
        if (fileElement == null) {
            me.yxp.qfun.utils.qq.Toasts.qqToast(1, "非文件消息")
            return
        }
        runCatching { showFileDirectLinkSheet(activity, msgData, fileElement) }
            .onFailure { LogUtils.e(TAG, it) }
    }

    private fun showFileDirectLinkSheet(activity: Activity, msgData: MsgData, file: FileElement) {
        val fileName = file.fileName ?: "(未知)"
        val fileSize = file.fileSize
        val fileMd5 = file.fileMd5 ?: ""
        val fileSha = file.fileSha ?: ""
        val fileUuid = file.fileUuid ?: ""
        val filePath = file.filePath ?: ""
        val chatType = msgData.type
        val peerUin = msgData.peerUin
        val contact = msgData.contact

        QFunBottomDialog(activity) { dismiss ->
            FileDirectLinkContent(
                activity = activity,
                fileName = fileName,
                fileSize = fileSize,
                fileMd5 = fileMd5,
                fileSha = fileSha,
                fileUuid = fileUuid,
                filePath = filePath,
                chatType = chatType,
                peerUin = peerUin,
                contact = contact,
                onDismiss = dismiss
            )
        }.show()
    }
}

// ==================== 直链获取（反射 IKernelRichMediaService） ====================

/**
 * 群聊场景：反射 IKernelRichMediaService.getGroupFileInfo(groupCode, fileId, callback) 尝试获取直链。
 *
 * @return 直链 URL，获取失败返回 null
 */
private suspend fun tryFetchGroupFileUrl(groupCode: Long, fileId: String): String? =
    withContext(Dispatchers.IO) {
        if (fileId.isEmpty()) return@withContext null
        runCatching {
            // 1. 拿 KernelService 实例
            val kernelService = runCatching {
                me.yxp.qfun.utils.qq.runtime<com.tencent.qqnt.kernel.api.IKernelService>()
            }.getOrNull() ?: return@runCatching null

            // 2. 反射 getRichMediaService（KernelServiceImpl 真实实现里有这个方法，存根里没有）
            val rmsMethod = kernelService.javaClass.methods.firstOrNull { it.name == "getRichMediaService" }
                ?: return@runCatching null
            val rms = rmsMethod.invoke(kernelService) ?: return@runCatching null

            // 3. 找 getGroupFileInfo(long, String, IGroupFileInfoCallback)
            val getGroupFileInfoMethod = rms.javaClass.methods.firstOrNull {
                it.name == "getGroupFileInfo" && it.parameterTypes.size == 3
            } ?: return@runCatching null

            val callbackClass = getGroupFileInfoMethod.parameterTypes[2]
            LogUtils.d("FileDirectLink: getGroupFileInfo callback class: ${callbackClass.name}")

            // 4. 创建动态代理
            val urlDeferred = CompletableDeferred<String?>()
            val proxy = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                LogUtils.d("FileDirectLink: callback invoked: ${method.name} args=${args?.size}")
                if (args != null && args.isNotEmpty()) {
                    // 回调通常 onResult(int code, GroupFileInfo info)，info 是最后一个参数
                    val info = args.last()
                    val url = extractUrlFromObject(info)
                    LogUtils.d("FileDirectLink: extracted url: $url")
                    if (urlDeferred.isActive) urlDeferred.complete(url)
                }
                null
            }

            // 5. 调用
            getGroupFileInfoMethod.invoke(rms, groupCode, fileId, proxy)

            // 6. 等待回调（5 秒超时）
            withTimeoutOrNull(5000) { urlDeferred.await() }
        }.getOrNull()
    }

/**
 * 反射遍历对象所有字段，尝试常见 URL 字段名（不同 QQ 版本可能不同）。
 * 同时把对象结构 dump 到日志，方便后续迭代。
 */
private fun extractUrlFromObject(obj: Any?): String? {
    if (obj == null) return null
    return runCatching {
        LogUtils.d("FileDirectLink: GroupFileInfo obj: $obj")
        val clazz = obj.javaClass
        // 直接 toString 看是否含 http
        val str = obj.toString()
        if (str.contains("http")) {
            // 尝试从 toString 提取 URL
            extractUrlFromString(str)?.let { return@runCatching it }
        }
        // 遍历所有 getter 方法
        val urlCandidates = listOf("getUrl", "getFileUrl", "getDownloadUrl", "getDownloadAddr", "getDownUrl")
        for (getterName in urlCandidates) {
            val m = runCatching { clazz.getMethod(getterName) }.getOrNull()
            if (m != null) {
                val v = runCatching { m.invoke(obj) as? String }.getOrNull()
                if (!v.isNullOrEmpty() && (v.startsWith("http") || v.contains("tencent.com"))) {
                    return@runCatching v
                }
            }
        }
        // 遍历所有 String 类型 getter，找含 http 的
        clazz.methods.forEach { m ->
            if (m.name.startsWith("get") && m.parameterTypes.isEmpty()
                && m.returnType == String::class.java
            ) {
                val v = runCatching { m.invoke(obj) as? String }.getOrNull()
                if (!v.isNullOrEmpty() && v.startsWith("http")) {
                    return@runCatching v
                }
            }
        }
        null
    }.getOrNull()
}

/** 从字符串中提取第一个 http(s) URL */
private fun extractUrlFromString(s: String): String? {
    val regex = Regex("https?://[^\\s\"'<>\\]}]+")
    return regex.find(s)?.value
}

/** 测试 URL 是否可访问（HEAD 请求，仅验证可连通性） */
@Suppress("unused")
private suspend fun testUrlAccessible(url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        conn.responseCode in 200..399
    }.getOrDefault(false)
}

// ==================== 弹窗 UI ====================

@Composable
private fun FileDirectLinkContent(
    activity: Activity,
    fileName: String,
    fileSize: Long,
    fileMd5: String,
    fileSha: String,
    fileUuid: String,
    filePath: String,
    chatType: Int,
    peerUin: String,
    contact: Contact,
    onDismiss: () -> Unit
) {
    // 直链状态：null=查询中，""=失败，其它=成功
    var directUrl by remember { mutableStateOf<String?>(if (chatType == 2) null else "") }
    var queryError by remember { mutableStateOf<String?>(null) }

    // 群聊：异步反射获取直链
    LaunchedEffect(fileUuid, chatType, peerUin) {
        if (chatType == 2 && fileUuid.isNotEmpty()) {
            directUrl = null
            queryError = null
            val url = runCatching {
                tryFetchGroupFileUrl(peerUin.toLongOrNull() ?: 0L, fileUuid)
            }.getOrNull()
            directUrl = url ?: ""
            if (url.isNullOrEmpty()) {
                queryError = "kernel 未返回直链，可尝试「触发下载」后从本地路径获取"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题
        Text(
            text = "文件直链",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF7C5CFF),
            modifier = Modifier.fillMaxWidth()
        )

        // 文件信息卡
        InfoCard(title = "文件信息") {
            InfoRow(label = "文件名", value = fileName, copyable = true, activity = activity)
            InfoRow(label = "大小", value = formatFileSize(fileSize))
            InfoRow(label = "MD5", value = fileMd5.ifEmpty { "(空)" }, copyable = fileMd5.isNotEmpty(), activity = activity)
            InfoRow(label = "SHA", value = fileSha.ifEmpty { "(空)" }, copyable = fileSha.isNotEmpty(), activity = activity)
            InfoRow(label = "UUID", value = fileUuid.ifEmpty { "(空)" }, copyable = fileUuid.isNotEmpty(), activity = activity)
        }

        // 直链卡（仅群聊显示查询状态）
        if (chatType == 2) {
            InfoCard(title = "下载直链") {
                when {
                    directUrl == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = Color(0xFF7C5CFF),
                                modifier = Modifier.height(20.dp).width(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("查询中...", fontSize = 13.sp, color = Color(0xFF666666))
                        }
                    }
                    directUrl.isNullOrEmpty() -> {
                        Text(
                            queryError ?: "获取失败",
                            fontSize = 13.sp,
                            color = Color(0xFFE53935)
                        )
                    }
                    else -> {
                        Text(
                            directUrl!!,
                            fontSize = 12.sp,
                            color = Color(0xFF1A1A1A),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { copyToClipboard(activity, directUrl!!) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(28.dp)
                        ) { Text("复制直链", fontSize = 11.sp) }
                    }
                }
            }
        } else {
            // 私聊文件没有直链接口
            InfoCard(title = "下载直链") {
                Text(
                    "私聊文件无 kernel 直链接口，请用下方「触发下载」后从本地路径获取",
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )
            }
        }

        // 本地路径卡（已下载时显示）
        if (filePath.isNotEmpty()) {
            InfoCard(title = "本地路径") {
                Text(
                    filePath,
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { copyToClipboard(activity, filePath) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("复制路径", fontSize = 11.sp) }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { copyToClipboard(activity, fileUuid) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                enabled = fileUuid.isNotEmpty()
            ) { Text("复制 UUID", fontSize = 12.sp) }

            Button(
                onClick = {
                    onDismiss()
                    openInBrowser(activity, directUrl)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                enabled = !directUrl.isNullOrEmpty()
            ) { Text("浏览器打开", fontSize = 12.sp) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { onDismiss() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("关闭", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF7C5CFF)
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    copyable: Boolean = false,
    activity: Activity? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label：",
            fontSize = 13.sp,
            color = Color(0xFF666666),
            modifier = Modifier.width(60.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = Color(0xFF1A1A1A),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (copyable && activity != null) {
            OutlinedButton(
                onClick = { copyToClipboard(activity, value) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("复制", fontSize = 11.sp)
            }
        }
    }
}

// ==================== 工具函数 ====================

/** 格式化文件大小（B/KB/MB/GB） */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024.0
        unitIndex++
    }
    val formatted = if (unitIndex == 0) size.toInt().toString()
    else String.format("%.2f", size)
    return "$formatted ${units[unitIndex]}"
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qfun", text))
        me.yxp.qfun.utils.qq.Toasts.qqToast(2, "已复制")
    }.onFailure {
        me.yxp.qfun.utils.qq.Toasts.qqToast(1, "复制失败")
    }
}

private fun openInBrowser(context: Context, url: String?) {
    if (url.isNullOrEmpty()) {
        me.yxp.qfun.utils.qq.Toasts.qqToast(1, "无可用直链")
        return
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        me.yxp.qfun.utils.qq.Toasts.qqToast(1, "打开失败")
    }
}
