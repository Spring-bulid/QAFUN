@file:Suppress("unused")

package me.yxp.qfun.plugin.api

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.tencent.mobileqq.data.troop.TroopInfo
import kotlinx.coroutines.runBlocking
import me.yxp.qfun.plugin.bean.ForbidInfo
import me.yxp.qfun.plugin.bean.FriendInfo
import me.yxp.qfun.plugin.bean.GroupInfo
import me.yxp.qfun.plugin.bean.MemberInfo
import me.yxp.qfun.plugin.loader.PluginCompiler
import me.yxp.qfun.utils.io.FileUtils
import me.yxp.qfun.utils.json.JsonConfigUtils
import me.yxp.qfun.utils.log.PluginError
import me.yxp.qfun.utils.qq.CookieTool
import me.yxp.qfun.utils.qq.FriendTool
import me.yxp.qfun.utils.qq.MsgTool
import me.yxp.qfun.utils.qq.QQCurrentEnv
import me.yxp.qfun.utils.qq.Toasts
import me.yxp.qfun.utils.qq.TroopTool
import java.io.File

/**
 * 第三方插件 API 桥接层：将原 BeanShell 的 PluginMethod 全部能力
 * 通过 QuickJS 的 `function` 绑定注入到 JS 全局作用域。
 *
 * TS 插件可直接调用全局函数，例如：
 * ```ts
 * sendMsg(peerUin, "hello", chatType);
 * sendPic(peerUin, "/sdcard/x.png", chatType);
 * addItem("我的菜单", "onClick");
 * ```
 *
 * 注：所有函数均为同步执行。需异步/挂起的宿主调用在内部用 runBlocking 阻塞。
 */
class PluginBridge(private val compiler: PluginCompiler) {

    private val configPath = "${compiler.info.dirPath}/config/"
    private val info get() = compiler.info

    /** 将全部 API 注入到 QuickJs 全局作用域。 */
    fun bindAll(quickJs: QuickJs) {
        // 基础工具
        // log(filename, msg): 写日志到插件目录文件
        quickJs.function("log") { args -> log(args.stringValue(0), args.stringValue(1)) }
        quickJs.function("toast") { args -> toast(args.anyValue(0)) }
        quickJs.function("qqToast") { args -> qqToast(args.intValue(0), args.anyValue(1)) }
        quickJs.function("getNowActivity") { getNowActivity() }
        quickJs.function("getMyUin") { QQCurrentEnv.currentUin }
        quickJs.function("getPluginPath") { info.dirPath }
        quickJs.function("getPluginId") { info.id }

        // 菜单注册
        quickJs.function("addItem") { args ->
            addItem(args.stringValue(0), args.stringValue(1)); null
        }
        quickJs.function("addMenuItem") { args ->
            addMenuItem(args.stringValue(0), args.stringValue(1), args.intArrayValue(2)); null
        }

        // 消息发送 (统一使用 peerUin + chatType 形式，JS 侧无 Contact 对象)
        quickJs.function("sendMsg") { args ->
            sendMsg(args.stringValue(0), args.stringValue(1), args.intValue(2)); null
        }
        quickJs.function("sendMsgByUin") { args ->
            sendMsg(args.stringValue(0), args.stringValue(1), args.intValue(2)); null
        }
        quickJs.function("sendPic") { args ->
            sendPic(args.stringValue(0), args.stringValue(1), args.intValue(2)); null
        }
        quickJs.function("sendPtt") { args ->
            sendPtt(args.stringValue(0), args.stringValue(1), args.intValue(2)); null
        }
        quickJs.function("sendCard") { args ->
            sendCard(args.stringValue(0), args.stringValue(1), args.intValue(2)); null
        }
        quickJs.function("sendVideo") { args ->
            sendVideo(args.stringValue(0), args.stringValue(1), args.intValue(2)); null
        }
        quickJs.function("sendFile") { args ->
            sendFile(args.stringValue(0), args.stringValue(1), args.intValue(2)); null
        }
        quickJs.function("sendReplyMsg") { args ->
            sendReplyMsg(args.stringValue(0), args.longValue(1), args.stringValue(2), args.intValue(3)); null
        }
        quickJs.function("recallMsg") { args ->
            recallMsg(args.intValue(0), args.stringValue(1), args.longValue(2)); null
        }
        quickJs.function("sendPai") { args ->
            sendPai(args.stringValue(0), args.stringValue(1), args.intValue(2)); null
        }

        // 好友 API
        quickJs.function("getAllFriend") { getAllFriend() }
        quickJs.function("isFriend") { args -> isFriend(args.stringValue(0)) }
        quickJs.function("getUidFromUin") { args -> getUidFromUin(args.stringValue(0)) }
        quickJs.function("getUinFromUid") { args -> getUinFromUid(args.stringValue(0)) }
        quickJs.function("sendZan") { args -> sendZan(args.stringValue(0), args.intValue(1)); null }

        // 群 API
        quickJs.function("getGroupList") { getGroupList() }
        quickJs.function("getGroupInfo") { args -> getGroupInfo(args.stringValue(0)) }
        quickJs.function("getGroupMemberList") { args -> getGroupMemberList(args.stringValue(0)) }
        quickJs.function("getProhibitList") { args -> getProhibitList(args.stringValue(0)) }
        quickJs.function("getMemberInfo") { args -> getMemberInfo(args.stringValue(0), args.stringValue(1)) }
        quickJs.function("isShutUp") { args -> isShutUp(args.stringValue(0)) }
        quickJs.function("shutUpAll") { args -> shutUpAll(args.stringValue(0), args.booleanValue(1)); null }
        quickJs.function("shutUp") { args -> shutUp(args.stringValue(0), args.stringValue(1), args.longValue(2)); null }
        quickJs.function("setGroupAdmin") { args -> setGroupAdmin(args.stringValue(0), args.stringValue(1), args.booleanValue(2)); null }
        quickJs.function("setGroupMemberTitle") { args -> setGroupMemberTitle(args.stringValue(0), args.stringValue(1), args.stringValue(2)); null }
        quickJs.function("changeMemberName") { args -> changeMemberName(args.stringValue(0), args.stringValue(1), args.stringValue(2)); null }
        quickJs.function("kickGroup") { args -> kickGroup(args.stringValue(0), args.stringValue(1), args.booleanValue(2)); null }
        quickJs.function("clockIn") { args -> clockIn(args.stringValue(0)); null }

        // 配置持久化
        quickJs.function("putString") { args -> putString(args.stringValue(0), args.stringValue(1), args.stringValue(2)); null }
        quickJs.function("putInt") { args -> putInt(args.stringValue(0), args.stringValue(1), args.intValue(2)); null }
        quickJs.function("putBoolean") { args -> putBoolean(args.stringValue(0), args.stringValue(1), args.booleanValue(2)); null }
        quickJs.function("putLong") { args -> putLong(args.stringValue(0), args.stringValue(1), args.longValue(2)); null }
        quickJs.function("getString") { args -> getString(args.stringValue(0), args.stringValue(1), args.stringValue(2)) }
        quickJs.function("getInt") { args -> getInt(args.stringValue(0), args.stringValue(1), args.intValue(2)) }
        quickJs.function("getBoolean") { args -> getBoolean(args.stringValue(0), args.stringValue(1), args.booleanValue(2)) }
        quickJs.function("getLong") { args -> getLong(args.stringValue(0), args.stringValue(1), args.longValue(2)) }

        // Cookie / 登录态
        quickJs.function("getRealSkey") { getRealSkey() }
        quickJs.function("getSkey") { getSkey() }
        quickJs.function("getStweb") { getStweb() }
        quickJs.function("getPt4Token") { args -> getPt4Token(args.stringValue(0)) }
        quickJs.function("getGTK") { args -> getGTK(args.stringValue(0)) }
        quickJs.function("getBkn") { args -> getBkn(args.stringValue(0)) }
        quickJs.function("getGroupRKey") { getGroupRKey() }
        quickJs.function("getFriendRKey") { getFriendRKey() }
        quickJs.function("getPskey") { args -> getPskey(args.stringValue(0)) }

        // 文件操作
        quickJs.function("readFile") { args -> readFile(args.stringValue(0)) }
        quickJs.function("writeFile") { args -> writeFile(args.stringValue(0), args.stringValue(1)); null }
        quickJs.function("appendFile") { args -> appendFile(args.stringValue(0), args.stringValue(1)); null }
        quickJs.function("fileExists") { args -> fileExists(args.stringValue(0)) }

        // 图片渲染 (宿主 Canvas，TS 沙盒无法直接绘图)
        // renderTextImage(text, fontSize?, color1?, color2?, padding?) -> 图片绝对路径 / 失败返回 ""
        quickJs.function("renderTextImage") { args ->
            renderTextImage(
                args.stringValue(0),
                args.intValue(1).takeIf { it > 0 } ?: 64,
                args.intValue(2),
                args.intValue(3),
                args.intValue(4).takeIf { it > 0 } ?: 50
            )
        }

        // ==================== 运行时 polyfill ====================
        // QuickJS 无内置 setTimeout/clearTimeout/console/JSON 兜底，这里统一注入。
        // setTimeout: 在独立线程延迟执行回调 (不阻塞 JS 线程)
        quickJs.function("__qfun_setTimeout") { args ->
            val delay = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
            val cbName = args.getOrNull(1)?.toString() ?: ""
            scheduleTimeout(delay, cbName); 0
        }
        quickJs.function("__qfun_clearTimeout") { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            cancelTimeout(id); null
        }
        // 注入 JS 侧包装：setTimeout(fn, delay) -> __qfun_setTimeout(delay, fnName)
        runBlocking {
            quickJs.evaluate<Any?>(
                """
                var __qfun_timers = {};
                if (typeof setTimeout === 'undefined') {
                    globalThis.setTimeout = function(fn, delay) {
                        delay = delay || 0;
                        var id = __qfun_setTimeout(delay, '__qfun_timer_cb');
                        __qfun_timers[id] = fn;
                        return id;
                    };
                }
                if (typeof clearTimeout === 'undefined') {
                    globalThis.clearTimeout = function(id) {
                        __qfun_clearTimeout(id);
                        delete __qfun_timers[id];
                    };
                }
                // 宿主延迟到期后调用此函数触发实际回调
                globalThis.__qfun_timer_cb = function(id) {
                    var fn = __qfun_timers[id];
                    if (fn) { delete __qfun_timers[id]; try { fn(); } catch(e) {} }
                };
                // console 兜底
                if (typeof console === 'undefined') {
                    globalThis.console = { log: function() { log('console.txt', Array.prototype.join.call(arguments, ' ')); } };
                } else {
                    if (!console.log) console.log = function() { log('console.txt', Array.prototype.join.call(arguments, ' ')); };
                }
                """.trimIndent(),
                "polyfill.js"
            )
        }
    }

    // ==================== setTimeout 实现 ====================

    private val timerIds = java.util.concurrent.atomic.AtomicInteger(1)
    private val timers = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ScheduledFuture<*>>()
    private val timerExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    private fun scheduleTimeout(delay: Long, cbName: String): Int {
        val id = timerIds.getAndIncrement()
        // 回调需要在 QuickJs 线程执行 (evaluate 是 suspend)
        val future = timerExecutor.schedule({
            runCatching {
                val instance = compiler.quickJs ?: return@schedule
                kotlinx.coroutines.runBlocking {
                    instance.evaluate<Any?>("__qfun_timer_cb($id)", "timer.js")
                }
            }.onFailure { PluginError.callError(it, info) }
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        timers[id] = future
        return id
    }

    private fun cancelTimeout(id: Int) {
        timers.remove(id)?.cancel(false)
    }

    /** 插件卸载时释放定时器线程池。 */
    fun dispose() {
        timers.values.forEach { it.cancel(false) }
        timers.clear()
        timerExecutor.shutdownNow()
    }

    // ==================== 基础 ====================

    fun log(fileName: String, msg: String) {
        runCatching {
            FileUtils.writeText(File(info.dirPath, fileName.ifEmpty { "log.txt" }), "$msg\n", true)
        }.onFailure { PluginError.callError(it, info) }
    }

    fun toast(msg: Any?) = runCatching { Toasts.toast("$msg") }
        .onFailure { PluginError.callError(it, info) }.getOrNull()

    fun qqToast(icon: Int, msg: Any?) = runCatching { Toasts.qqToast(icon, "$msg") }
        .onFailure { PluginError.callError(it, info) }.getOrNull()

    fun getNowActivity() = QQCurrentEnv.activity

    // ==================== 菜单 ====================

    fun addItem(name: String, callback: String) {
        compiler.menuItems[name] = callback
    }

    fun addMenuItem(name: String, callback: String, msgTypes: IntArray) {
        val types = msgTypes.joinToString(",")
        compiler.msgMenuItem.add("[QFun],${info.id},$name,$callback,$types")
    }

    // ==================== 消息发送 ====================

    fun sendMsg(peerUin: String, msg: String, chatType: Int) =
        runErr { MsgTool.sendMsg(peerUin, msg, chatType) }

    fun sendPic(peerUin: String, path: String, chatType: Int) =
        runErr { MsgTool.sendPic(peerUin, path, chatType) }

    fun sendPtt(peerUin: String, path: String, chatType: Int) =
        runErr { MsgTool.sendPtt(peerUin, path, chatType) }

    fun sendCard(peerUin: String, data: String, chatType: Int) =
        runErr { MsgTool.sendCard(peerUin, data, chatType) }

    fun sendVideo(peerUin: String, path: String, chatType: Int) =
        runErr { MsgTool.sendVideo(peerUin, path, chatType) }

    fun sendFile(peerUin: String, path: String, chatType: Int) =
        runErr { MsgTool.sendFile(peerUin, path, chatType) }

    fun sendReplyMsg(peerUin: String, replyMsgId: Long, msg: String, chatType: Int) =
        runErr { MsgTool.sendReplyMsg(peerUin, replyMsgId, msg, chatType) }

    fun recallMsg(chatType: Int, peerUin: String, msgId: Long) =
        runErr { MsgTool.recallMsg(chatType, peerUin, msgId) }

    fun sendPai(toUin: String, peerUin: String, chatType: Int) =
        runErr { MsgTool.sendPai(toUin, peerUin, chatType) }

    // ==================== 好友 ====================

    fun getAllFriend(): List<FriendInfo>? = runErr(null) { FriendTool.getAllFriend() }
    fun isFriend(uin: String): Boolean = runErr(false) { FriendTool.isFriend(uin) }
    fun getUidFromUin(uin: String): String = runErr("") { FriendTool.getUidFromUin(uin) }
    fun getUinFromUid(uid: String): String = runErr("") { FriendTool.getUinFromUid(uid) }
    fun sendZan(uin: String, num: Int) = runErr { FriendTool.sendZan(uin, num) }

    // ==================== 群 ====================

    fun getGroupList(): List<GroupInfo>? = runErr(null) { TroopTool.getGroupList() }
    fun getGroupInfo(troopUin: String): TroopInfo? = runErr(null) { TroopTool.getGroupInfo(troopUin) }
    fun getGroupMemberList(troopUin: String): List<MemberInfo>? =
        runErr(null) { runBlocking { TroopTool.getGroupMemberList(troopUin) } }
    fun getProhibitList(troopUin: String): List<ForbidInfo>? =
        runErr(null) { runBlocking { TroopTool.getProhibitList(troopUin) } }
    fun getMemberInfo(troopUin: String, uin: String): MemberInfo? =
        runErr(null) { runBlocking { TroopTool.getMemberInfo(troopUin, uin) } }
    fun isShutUp(troopUin: String): Boolean = runErr(false) { TroopTool.isShutUp(troopUin) }
    fun shutUpAll(troopUin: String, enable: Boolean) = runErr { TroopTool.shutUpAll(troopUin, enable) }
    fun shutUp(troopUin: String, uin: String, time: Long) = runErr { TroopTool.shutUp(troopUin, uin, time) }
    fun setGroupAdmin(troopUin: String, uin: String, enable: Boolean) =
        runErr { TroopTool.setGroupAdmin(troopUin, uin, enable) }
    fun setGroupMemberTitle(troopUin: String, uin: String, title: String) =
        runErr { TroopTool.setGroupMemberTitle(troopUin, uin, title) }
    fun changeMemberName(troopUin: String, uin: String, name: String) =
        runErr { TroopTool.changeMemberName(troopUin, uin, name) }
    fun kickGroup(troopUin: String, uin: String, block: Boolean) =
        runErr { TroopTool.kickGroup(troopUin, uin, block) }
    fun clockIn(troopUin: String) = runErr { TroopTool.clockIn(troopUin) }

    // ==================== 配置持久化 ====================

    fun putString(configName: String, key: String, value: String) =
        runErr { JsonConfigUtils.putString(configPath, configName, key, value) }
    fun putInt(configName: String, key: String, value: Int) =
        runErr { JsonConfigUtils.putInt(configPath, configName, key, value) }
    fun putBoolean(configName: String, key: String, value: Boolean) =
        runErr { JsonConfigUtils.putBoolean(configPath, configName, key, value) }
    fun putLong(configName: String, key: String, value: Long) =
        runErr { JsonConfigUtils.putLong(configPath, configName, key, value) }
    fun getString(configName: String, key: String, defaultValue: String): String =
        runErr(defaultValue) { JsonConfigUtils.getString(configPath, configName, key, defaultValue) }
    fun getInt(configName: String, key: String, defaultValue: Int): Int =
        runErr(defaultValue) { JsonConfigUtils.getInt(configPath, configName, key, defaultValue) }
    fun getBoolean(configName: String, key: String, defaultValue: Boolean): Boolean =
        runErr(defaultValue) { JsonConfigUtils.getBoolean(configPath, configName, key, defaultValue) }
    fun getLong(configName: String, key: String, defaultValue: Long): Long =
        runErr(defaultValue) { JsonConfigUtils.getLong(configPath, configName, key, defaultValue) }

    // ==================== Cookie ====================

    fun getRealSkey(): String = runErr("") { CookieTool.getRealSkey() ?: "" }
    fun getSkey(): String = runErr("") { CookieTool.getSkey() ?: "" }
    fun getStweb(): String = runErr("") { CookieTool.getStweb() ?: "" }
    fun getPt4Token(url: String): String = runErr("") { CookieTool.getPt4Token(url) ?: "" }
    fun getGTK(url: String): String = runErr("") { CookieTool.getGTK(url) }
    fun getBkn(key: String): Long = runErr(0L) { CookieTool.getBkn(key) }
    fun getGroupRKey(): String = runErr("") { CookieTool.getGroupRKey() }
    fun getFriendRKey(): String = runErr("") { CookieTool.getFriendRKey() }
    fun getPskey(url: String): String = runErr("") { CookieTool.getPskey(url) ?: "" }

    // ==================== 文件 ====================

    fun readFile(relPath: String): String = runErr("") {
        File(info.dirPath, relPath).takeIf { it.exists() }?.readText() ?: ""
    }

    fun writeFile(relPath: String, content: String) = runErr {
        FileUtils.writeText(File(info.dirPath, relPath), content)
    }

    fun appendFile(relPath: String, content: String) = runErr {
        FileUtils.writeText(File(info.dirPath, relPath), content, true)
    }

    fun fileExists(relPath: String): Boolean = runErr(false) {
        File(info.dirPath, relPath).exists()
    }

    // ==================== 图片渲染 (宿主 Canvas) ====================

    /**
     * 将文字渲染为 PNG 图片并保存到插件缓存目录，返回绝对路径。
     * TS 插件无法直接调用 Android Canvas，故由宿主提供。
     *
     * @param text     文字内容
     * @param fontSize 字号 (px)，默认 64
     * @param color1   渐变起始色 ARGB (int)，0 则用默认紫
     * @param color2   渐变结束色 ARGB (int)，0 则用默认青
     * @param padding  内边距，默认 50
     * @return 图片绝对路径，失败返回 ""
     */
    fun renderTextImage(
        text: String, fontSize: Int, color1: Int, color2: Int, padding: Int
    ): String = runErr("") {
        val c1 = if (color1 != 0) color1 else 0xFF7C5CFF.toInt()
        val c2 = if (color2 != 0) color2 else 0xFF00D9C0.toInt()
        val safeText = if (text.isEmpty()) " " else text

        val textPaint = android.text.TextPaint().apply {
            isAntiAlias = true
            this.textSize = fontSize.toFloat()
            color = 0xFFFFFFFF.toInt()
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
            setShadowLayer(fontSize * 0.15f, 2f, 2f, 0x80000000)
        }
        val maxWidth = 800
        val layout = android.text.StaticLayout(
            safeText, textPaint, maxWidth,
            android.text.Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false
        )
        val tw = layout.width.toInt()
        val th = layout.height.toInt()
        val bw = (tw + padding * 2).coerceAtLeast(200)
        val bh = (th + padding * 2).coerceAtLeast(100)

        val bitmap = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val bgPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            shader = android.graphics.LinearGradient(
                0f, 0f, bw.toFloat(), bh.toFloat(), c1, c2, android.graphics.Shader.TileMode.CLAMP
            )
        }
        val r = (padding * 0.6f).coerceAtLeast(8f)
        canvas.drawRoundRect(
            android.graphics.RectF(0f, 0f, bw.toFloat(), bh.toFloat()), r, r, bgPaint
        )
        canvas.save()
        canvas.translate(((bw - tw) / 2).toFloat(), padding.toFloat())
        layout.draw(canvas)
        canvas.restore()

        val cacheDir = File(info.dirPath, "cache").apply { mkdirs() }
        val file = File(cacheDir, "img_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(file).use { fos ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
        }
        bitmap.recycle()
        file.absolutePath
    }

    // ==================== 工具 ====================

    private inline fun <T> runErr(default: T, block: () -> T): T = runCatching(block)
        .onFailure { PluginError.callError(it, info) }
        .getOrElse { default }

    private inline fun runErr(block: () -> Unit) {
        runCatching(block).onFailure { PluginError.callError(it, info) }
    }
}

// ==================== JS 参数提取扩展 ====================

/** 从 QuickJS 的 Array<Any?> 中按位置安全提取各类型参数。 */
private fun Array<Any?>.stringValue(index: Int): String = this.getOrNull(index)?.toString() ?: ""
private fun Array<Any?>.intValue(index: Int): Int =
    (this.getOrNull(index) as? Number)?.toInt() ?: 0
private fun Array<Any?>.longValue(index: Int): Long =
    (this.getOrNull(index) as? Number)?.toLong() ?: 0L
private fun Array<Any?>.booleanValue(index: Int): Boolean =
    this.getOrNull(index) as? Boolean ?: false
private fun Array<Any?>.anyValue(index: Int): Any? = this.getOrNull(index)
private fun Array<Any?>.intArrayValue(index: Int): IntArray {
    val v = this.getOrNull(index) ?: return IntArray(0)
    return when (v) {
        is IntArray -> v
        is List<*> -> v.mapNotNull { (it as? Number)?.toInt() }.toIntArray()
        else -> IntArray(0)
    }
}
