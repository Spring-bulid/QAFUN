package me.yxp.qfun.hook.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.tencent.mobileqq.aio.msg.AIOMsgItem
import com.tencent.qqnt.aio.adapter.api.IContactApi
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.troopmemberlist.ITroopMemberListRepoApi
import kotlinx.coroutines.delay
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.hook.api.MenuClickListener
import me.yxp.qfun.hook.base.BaseSwitchHookItem
import me.yxp.qfun.plugin.bean.MsgData
import me.yxp.qfun.ui.core.compatibility.QFunBottomDialog
import me.yxp.qfun.utils.log.LogUtils
import me.yxp.qfun.utils.qq.FriendTool
import me.yxp.qfun.utils.qq.QQCurrentEnv
import me.yxp.qfun.utils.qq.TroopTool
import me.yxp.qfun.utils.qq.api
import me.yxp.qfun.utils.reflect.TAG
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 逆向查号：长按任意消息 → 逆向出发送者账号 + 所在群聊信息。
 *
 * NTQQ 新版 MsgRecord.senderUin 经常返回 0（改用 senderUid 即 u_xxx 标识用户），
 * 所以需要多接口反查真实 QQ 号：
 *  1. 群聊：优先 ITroopMemberListRepoApi.fetchTroopMemberUin（群成员缓存，最可靠）
 *  2. 兜底：FriendTool.getUinFromUid（IRelationNTUinAndUidApi，关系链缓存）
 *
 * 查询走协程异步，弹窗先显示「查询中...」，查到后 Compose state 自动刷新。
 */
@HookItemAnnotation(
    "逆向查号",
    "长按任意消息即可逆向出对方的 QQ 号、昵称及所在群聊信息（群名/群号/群主）",
    HookCategory.CHAT
)
object ReverseLookup : BaseSwitchHookItem(), MenuClickListener {

    /** 全消息类型生效（args[4..] 留空表示不过滤 msgType） */
    override val menuKey: String
        get() = if (isEnable) "[QFun],ReverseLookup,逆向查号,," else ""

    override fun onClick(msgData: MsgData) {
        val activity = QQCurrentEnv.activity ?: run {
            LogUtils.d("ReverseLookup: activity is null")
            return
        }
        runCatching { showReverseLookupSheet(activity, msgData) }
            .onFailure { LogUtils.e(TAG, it) }
    }

    private fun showReverseLookupSheet(activity: Activity, msgData: MsgData) {
        val record: MsgRecord = msgData.data
        val chatType = msgData.type                  // 1=私聊 2=群聊
        val peerUin = msgData.peerUin                // 群号 / 对方 QQ
        val senderUid = msgData.userUid
        val rawSenderUin = msgData.userUin           // 原始值，可能为 "0"
        val nick = record.sendMemberName.ifEmpty { record.sendNickName }
        val msgTime = record.msgTime
        val msgId = record.msgId

        // 群聊：同步查群信息（TroopTool.getGroupInfo 走 IRuntimeService，很快）
        var groupName = ""
        var ownerUin = ""
        if (chatType == 2) {
            runCatching {
                val info = TroopTool.getGroupInfo(peerUin)
                groupName = info.troopNameFromNT ?: ""
                ownerUin = info.troopowneruin ?: ""
            }.onFailure { LogUtils.e(TAG, it) }
        }

        QFunBottomDialog(activity) { dismiss ->
            ReverseLookupContent(
                activity = activity,
                chatType = chatType,
                rawSenderUin = rawSenderUin,
                senderUid = senderUid,
                senderNick = nick,
                peerUin = peerUin,
                groupName = groupName,
                ownerUin = ownerUin,
                msgTime = msgTime,
                msgId = msgId,
                msgRecord = record,
                onDismiss = dismiss
            )
        }.show()
    }
}

// ==================== uid → uin 反查（多接口兜底） ====================

/**
 * 挂起函数：通过 NT uid 反查真实 QQ 号。
 * 群聊场景优先用群成员缓存（最可靠），私聊/兜底用关系链缓存。
 */
private suspend fun resolveUinFromUid(senderUid: String, chatType: Int): String {
    if (senderUid.isEmpty() || !senderUid.startsWith("u_")) return ""

    // 群聊：优先用群成员缓存接口（OnTroopJoin 同款，带重试）
    if (chatType == 2) {
        repeat(5) {
            val uin = runCatching { fetchTroopMemberUin(senderUid) }.getOrNull()
            if (!uin.isNullOrEmpty() && uin != "0") return uin
            delay(100)
        }
    }

    // 兜底：关系链缓存（对好友/自己见过的用户有效）
    repeat(3) {
        val uin = runCatching { FriendTool.getUinFromUid(senderUid) }.getOrNull()
        if (!uin.isNullOrEmpty() && uin != "0") return uin
        delay(80)
    }

    return ""
}

/** ITroopMemberListRepoApi.fetchTroopMemberUin 转 suspend */
private suspend fun fetchTroopMemberUin(uid: String): String =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        runCatching {
            api<ITroopMemberListRepoApi>().fetchTroopMemberUin(uid) { success, uin ->
                if (cont.isActive) {
                    val result = if (success) uin else ""
                    cont.resumeWith(Result.success(result))
                }
            }
        }.onFailure {
            if (cont.isActive) cont.resumeWith(Result.success(""))
        }
    }

// ==================== 弹窗 UI ====================

@Composable
private fun ReverseLookupContent(
    activity: Activity,
    chatType: Int,
    rawSenderUin: String,
    senderUid: String,
    senderNick: String,
    peerUin: String,
    groupName: String,
    ownerUin: String,
    msgTime: Long,
    msgId: Long,
    msgRecord: MsgRecord,
    onDismiss: () -> Unit
) {
    val timeStr = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msgTime * 1000L))
    }.getOrDefault(msgTime.toString())

    val isGroup = chatType == 2

    // QQ 号查询状态：null=查询中，""=失败，其它=成功
    var resolvedUin by remember {
        mutableStateOf(
            if (rawSenderUin.isNotEmpty() && rawSenderUin != "0") rawSenderUin else null
        )
    }

    // 原始 senderUin 有效就直接用；否则启动协程异步反查
    LaunchedEffect(senderUid, chatType) {
        if (resolvedUin == null) {
            val uin = resolveUinFromUid(senderUid, chatType)
            resolvedUin = if (uin.isNotEmpty() && uin != "0") uin else ""
        }
    }

    val uinValid = !resolvedUin.isNullOrEmpty() && resolvedUin != "0"
    val uinDisplay = when {
        resolvedUin == null -> "查询中..."
        resolvedUin.isNullOrEmpty() || resolvedUin == "0" -> "(反查失败，请看 NT UID)"
        else -> resolvedUin!!
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题
        Text(
            text = "逆向查号结果",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF7C5CFF),
            modifier = Modifier.fillMaxWidth()
        )

        // 发送者信息卡
        InfoCard(title = "发送者") {
            InfoRow(
                label = "QQ 号",
                value = uinDisplay,
                copyable = uinValid,
                activity = activity
            )
            InfoRow(label = "NT UID", value = senderUid.ifEmpty { "(空)" }, copyable = senderUid.isNotEmpty(), activity = activity)
            InfoRow(label = "昵称/群名片", value = senderNick.ifEmpty { "(空)" })
        }

        // 群聊信息卡（仅群聊显示）
        if (isGroup) {
            InfoCard(title = "所在群聊") {
                InfoRow(label = "群名", value = groupName.ifEmpty { "(未获取)" })
                InfoRow(label = "群号", value = peerUin, copyable = true, activity = activity)
                InfoRow(label = "群主", value = ownerUin.ifEmpty { "(未获取)" }, copyable = ownerUin.isNotEmpty(), activity = activity)
            }
        } else {
            InfoCard(title = "会话") {
                InfoRow(label = "类型", value = "私聊")
                InfoRow(label = "对方 QQ", value = peerUin, copyable = true, activity = activity)
            }
        }

        // 消息元信息
        InfoCard(title = "消息") {
            InfoRow(label = "发送时间", value = timeStr)
            InfoRow(label = "消息 ID", value = msgId.toString(), copyable = true, activity = activity)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (uinValid) copyToClipboard(activity, resolvedUin!!)
                    else copyToClipboard(activity, senderUid)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text(if (uinValid) "复制 QQ 号" else "复制 NT UID") }

            Button(
                onClick = {
                    onDismiss()
                    openProfileCard(activity, msgRecord, resolvedUin, senderUid)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF))
            ) { Text("查看资料卡") }
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
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            fontSize = 14.sp,
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

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qfun", text))
        me.yxp.qfun.utils.qq.Toasts.qqToast(2, "已复制: $text")
    }.onFailure {
        me.yxp.qfun.utils.qq.Toasts.qqToast(1, "复制失败")
    }
}

private fun openProfileCard(activity: Activity, msgRecord: MsgRecord, senderUin: String?, senderUid: String) {
    runCatching {
        // NTQQ 新版 msgRecord.senderUin 可能为 0，资料卡会打不开。
        // 这里用反查到的真实 QQ 号重建 MsgRecord，确保资料卡能定位到目标用户。
        val effectiveUin = senderUin?.toLongOrNull() ?: 0L
        val recordForCard = if (effectiveUin != 0L && msgRecord.senderUin == 0L) {
            MsgRecord().apply {
                this.senderUin = effectiveUin
                this.senderUid = senderUid
                this.peerUin = msgRecord.peerUin
                this.chatType = msgRecord.chatType
            }
        } else {
            msgRecord
        }
        api<IContactApi>().openProfileCard(activity, AIOMsgItem(recordForCard))
    }.onFailure {
        LogUtils.e("ReverseLookup", it)
        me.yxp.qfun.utils.qq.Toasts.qqToast(1, "打开资料卡失败")
    }
}
