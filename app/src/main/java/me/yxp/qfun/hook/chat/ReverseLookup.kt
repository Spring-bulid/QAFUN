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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.mobileqq.aio.msg.AIOMsgItem
import com.tencent.qqnt.aio.adapter.api.IContactApi
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.hook.api.MenuClickListener
import me.yxp.qfun.hook.base.BaseSwitchHookItem
import me.yxp.qfun.plugin.bean.MsgData
import me.yxp.qfun.ui.core.compatibility.QFunBottomDialog
import me.yxp.qfun.utils.log.LogUtils
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
 * 长按菜单注入「逆向查号」项，点击后弹出底部弹窗展示：
 *  - 发送者 QQ 号 / NT uid / 昵称（或群名片）
 *  - 群聊：群名 / 群号 / 群主 / 发送者在本群的角色
 *  - 私聊：对方 QQ 号
 *  - 消息时间 / 消息 ID
 *  - 操作：复制 QQ 号、查看资料卡
 *
 * 无需任何 hook，仅在长按菜单点击时同步读取 MsgRecord 已有字段 + TroopTool 查群信息。
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
        val senderUin = msgData.userUin
        val senderUid = msgData.userUid
        val chatType = msgData.type                  // 1=私聊 2=群聊
        val peerUin = msgData.peerUin                // 群号 / 对方 QQ
        val nick = record.sendMemberName.ifEmpty { record.sendNickName }
        val msgTime = record.msgTime
        val msgId = record.msgId

        // 群聊：查群信息（群名、群主）；私聊：跳过
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
                senderUin = senderUin,
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
        }
    }
}

// ==================== 弹窗 UI ====================

@Composable
private fun ReverseLookupContent(
    activity: Activity,
    chatType: Int,
    senderUin: String,
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
            InfoRow(label = "QQ 号", value = senderUin, copyable = true, activity = activity)
            InfoRow(label = "NT UID", value = senderUid, copyable = true, activity = activity)
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
                    copyToClipboard(activity, senderUin)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("复制 QQ 号") }

            Button(
                onClick = {
                    onDismiss()
                    openProfileCard(activity, msgRecord)
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

private fun openProfileCard(activity: Activity, msgRecord: MsgRecord) {
    runCatching {
        // 通过 AIOMsgItem 包装 MsgRecord 打开资料卡（与 ShowAtTarget 同款方式）
        api<IContactApi>().openProfileCard(activity, AIOMsgItem(msgRecord))
    }.onFailure {
        LogUtils.e("ReverseLookup", it)
        me.yxp.qfun.utils.qq.Toasts.qqToast(1, "打开资料卡失败")
    }
}
