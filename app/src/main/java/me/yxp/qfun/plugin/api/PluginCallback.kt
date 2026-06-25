package me.yxp.qfun.plugin.api

import me.yxp.qfun.common.ModuleScope
import me.yxp.qfun.hook.api.PaiYiPaiListener
import me.yxp.qfun.hook.api.ReceiveMsgListener
import me.yxp.qfun.hook.api.SendMsgListener
import me.yxp.qfun.hook.api.TroopJoinListener
import me.yxp.qfun.hook.api.TroopQuitListener
import me.yxp.qfun.hook.api.TroopShutUpListener
import me.yxp.qfun.plugin.bean.MsgData
import me.yxp.qfun.plugin.loader.PluginCompiler
import me.yxp.qfun.utils.log.PluginError
import me.yxp.qfun.utils.reflect.TAG

/**
 * 事件回调分发：将 QQ 各类事件转发到 TS 插件中定义的全局函数。
 *
 * TS 插件可定义以下全局函数接收回调：
 *  - onMsg(msgData)              : 收到消息 (群/私聊均触发)
 *  - joinGroup(troopUin, memberUin) : 有人入群
 *  - quitGroup(troopUin, memberUin) : 有人退群
 *  - shutUpGroup(troopUin, memberUin, time, opUin) : 禁言
 *  - onPaiYiPai(peerUin, chatType, opUin) : 拍一拍
 *  - getMsg(text) -> string      : 发送消息内容改写 (返回新文本)
 *  - chatInterface(chatType, peerUin, peerName) : 进入聊天界面
 *  - unLoadPlugin()              : 插件卸载
 *
 * 若某函数未定义则自动跳过，不会报错。
 */
class PluginCallback(val compiler: PluginCompiler) {

    private val info = compiler.info

    val receiveMsgListener = ReceiveMsgListener { msgRecord ->
        ModuleScope.launchIO(TAG) {
            runCatching {
                if (!compiler.hasFunction("onMsg")) return@launchIO
                val data = MsgData(msgRecord)
                val js = msgDataToJs(data)
                compiler.evaluateSync("onMsg($js)")
            }.onFailure { PluginError.callError(it, info) }
        }
    }

    val troopJoinListener = TroopJoinListener { troopUin, memberUin ->
        ModuleScope.launchIO(TAG) {
            runCatching {
                if (compiler.hasFunction("joinGroup")) {
                    compiler.evaluateSync("joinGroup(${jsStr(troopUin)}, ${jsStr(memberUin)})")
                }
            }.onFailure { PluginError.callError(it, info) }
        }
    }

    val troopQuitListener = TroopQuitListener { troopUin, memberUin ->
        ModuleScope.launchIO(TAG) {
            runCatching {
                if (compiler.hasFunction("quitGroup")) {
                    compiler.evaluateSync("quitGroup(${jsStr(troopUin)}, ${jsStr(memberUin)})")
                }
            }.onFailure { PluginError.callError(it, info) }
        }
    }

    val troopShutUpListener = TroopShutUpListener { troopUin, memberUin, time, opUin ->
        ModuleScope.launchIO(TAG) {
            runCatching {
                if (compiler.hasFunction("shutUpGroup")) {
                    compiler.evaluateSync(
                        "shutUpGroup(${jsStr(troopUin)}, ${jsStr(memberUin)}, $time, ${jsStr(opUin)})"
                    )
                }
            }.onFailure { PluginError.callError(it, info) }
        }
    }

    val paiYiPaiListener = PaiYiPaiListener { peerUin, chatType, opUin ->
        ModuleScope.launchIO(TAG) {
            runCatching {
                if (compiler.hasFunction("onPaiYiPai")) {
                    compiler.evaluateSync(
                        "onPaiYiPai(${jsStr(peerUin)}, $chatType, ${jsStr(opUin)})"
                    )
                }
            }.onFailure { PluginError.callError(it, info) }
        }
    }

    val sendMsgListener = SendMsgListener { elements ->
        // 发送消息改写：若插件定义了 getMsg(text)，则用它替换文本元素内容
        if (!compiler.hasFunction("getMsg")) return@SendMsgListener
        elements.mapNotNull { it.textElement }.forEach { textElement ->
            val new = runCatching {
                val escaped = jsStr(textElement.content)
                val result = compiler.evaluateSync("getMsg($escaped)")
                (result as? String) ?: textElement.content
            }.onFailure { PluginError.callError(it, info) }
                .getOrElse { textElement.content }
            textElement.content = new
        }
    }

    fun unLoadPlugin() {
        runCatching {
            if (compiler.hasFunction("unLoadPlugin")) {
                compiler.evaluateSync("unLoadPlugin()")
            }
        }.onFailure { PluginError.callError(it, info) }
    }

    fun chatInterface(chatType: Int, peerUin: String, peerName: String) {
        ModuleScope.launchIO(TAG) {
            runCatching {
                if (compiler.hasFunction("chatInterface")) {
                    compiler.evaluateSync(
                        "chatInterface($chatType, ${jsStr(peerUin)}, ${jsStr(peerName)})"
                    )
                }
            }.onFailure { PluginError.callError(it, info) }
        }
    }

    /** 调用消息长按菜单项对应的 JS 函数：function(msgData)。 */
    fun invokeMsgMenuItem(methodName: String, msgData: MsgData) {
        ModuleScope.launchIO(TAG) {
            runCatching {
                if (compiler.hasFunction(methodName)) {
                    val js = msgDataToJs(msgData)
                    compiler.evaluateSync("$methodName($js)")
                } else {
                    PluginError.findError(
                        NoSuchMethodException("未找到插件方法: $methodName"), info, methodName
                    )
                }
            }.onFailure { PluginError.callError(it, info) }
        }
    }

    /**
     * 调用悬浮球菜单项对应的 JS 函数。
     * 支持两种函数签名：foo(chatType, peerUin, peerName) 或 foo(chatType, peerUin, peerName, contact)
     * 通过先探测函数参数数量决定调用方式 (JS 函数 .length 属性)。
     */
    fun invokeMenuItem(
        methodName: String, chatType: Int, peerUin: String, peerName: String, contact: Any?
    ) {
        ModuleScope.launchIO(TAG) {
            runCatching {
                if (!compiler.hasFunction(methodName)) {
                    PluginError.findError(
                        NoSuchMethodException("未找到插件方法: $methodName"), info, methodName
                    )
                    return@launchIO
                }
                // 探测参数数量：JS 函数 .length
                val lenResult = compiler.evaluateSync("$methodName.length")
                val argCount = (lenResult as? Number)?.toInt() ?: 3
                val call = if (argCount >= 4 && contact != null) {
                    "$methodName($chatType, ${jsStr(peerUin)}, ${jsStr(peerName)}, ${contactToJs(contact)})"
                } else {
                    "$methodName($chatType, ${jsStr(peerUin)}, ${jsStr(peerName)})"
                }
                compiler.evaluateSync(call)
            }.onFailure { PluginError.callError(it, info) }
        }
    }

    // ==================== JS 序列化辅助 ====================

    /** 将 MsgData 序列化为 JS 对象字面量字符串。 */
    private fun msgDataToJs(d: MsgData): String {
        val atList = d.atList.joinToString(",") { jsStr(it) }
        val atMapEntries = d.atMap.entries.joinToString(",") { "${jsStr(it.key)}: ${jsStr(it.value)}" }
        return buildString {
            append("{")
            append("type:").append(d.type).append(",")
            append("msgType:").append(d.msgType).append(",")
            append("peerUin:").append(jsStr(d.peerUin)).append(",")
            append("peerUid:").append(jsStr(d.peerUid)).append(",")
            append("userUin:").append(jsStr(d.userUin)).append(",")
            append("userUid:").append(jsStr(d.userUid)).append(",")
            append("time:").append(d.time).append(",")
            append("msgId:").append(d.msgId).append(",")
            append("msg:").append(jsStr(d.msg)).append(",")
            append("path:").append(jsStr(d.path)).append(",")
            append("atList:[").append(atList).append("],")
            append("atMap:{").append(atMapEntries).append("},")
            append("contact:").append(contactToJs(d.contact))
            append("}")
        }
    }

    private fun contactToJs(c: Any): String {
        // Contact(chatType, peerUid, guildId) -> {chatType, peerUid, guildId}
        val cls = c.javaClass
        return runCatching {
            val chatType = cls.getDeclaredMethod("getChatType").invoke(c)
            val peerUid = cls.getDeclaredMethod("getPeerUid").invoke(c) ?: ""
            val guildId = cls.getDeclaredMethod("getGuildId").invoke(c) ?: ""
            "{chatType:$chatType, peerUid:${jsStr(peerUid.toString())}, guildId:${jsStr(guildId.toString())}}"
        }.getOrElse {
            // QQContact 旧字段
            runCatching {
                val chatType = cls.getDeclaredField("chatType").get(c)
                val peerUid = cls.getDeclaredField("peerUid").get(c) ?: ""
                val guildId = cls.getDeclaredField("guildId").get(c) ?: ""
                "{chatType:$chatType, peerUid:${jsStr(peerUid.toString())}, guildId:${jsStr(guildId.toString())}}"
            }.getOrElse { "{}" }
        }
    }

    /** JS 字符串字面量转义。 */
    private fun jsStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('\'')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('\'')
        return sb.toString()
    }
}
