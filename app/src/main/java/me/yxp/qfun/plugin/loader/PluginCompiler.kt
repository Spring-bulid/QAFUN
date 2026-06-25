package me.yxp.qfun.plugin.loader

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.runBlocking
import me.yxp.qfun.hook.api.MenuClickListener
import me.yxp.qfun.hook.api.OnMenuBuild
import me.yxp.qfun.hook.api.OnPaiYiPai
import me.yxp.qfun.hook.api.OnReceiveMsg
import me.yxp.qfun.hook.api.OnSendMsg
import me.yxp.qfun.hook.api.OnTroopJoin
import me.yxp.qfun.hook.api.OnTroopQuit
import me.yxp.qfun.hook.api.OnTroopShutUp
import me.yxp.qfun.lifecycle.DynamicActivityRegistry
import me.yxp.qfun.plugin.api.PluginBridge
import me.yxp.qfun.plugin.api.PluginCallback
import me.yxp.qfun.plugin.bean.MsgData
import me.yxp.qfun.plugin.bean.PluginInfo
import me.yxp.qfun.utils.log.PluginError
import me.yxp.qfun.utils.qq.QQCurrentEnv
import java.io.File

/**
 * 第三方插件编译器：基于 QuickJS 的 TypeScript 引擎。
 *
 * 加载流程：
 *  1. 读取 main.ts，经 [TsTranspiler] 剥离类型注解转为 JS
 *  2. 创建 QuickJs 实例并注入 [PluginBridge] 全局 API (sendMsg/sendPic/onMsg 注册等)
 *  3. 执行 JS 代码 (插件顶层逻辑：注册菜单、注册回调)
 *  4. 通过 [PluginCallback] 将 QQ 事件分发到 JS 函数
 *
 * JS 调用 Kotlin：通过 quickJs.function(...) 绑定的全局函数 (PluginBridge)
 * Kotlin 调用 JS：通过 evaluate("(onMsg && onMsg(data))") 形式
 */
class PluginCompiler(val info: PluginInfo) {

    /** 当前 QuickJs 实例，运行时非空。 */
    @Volatile
    var quickJs: QuickJs? = null

    /** 全局 API 桥接。 */
    val bridge = PluginBridge(this)

    /** 事件回调分发。 */
    val callback = PluginCallback(this)

    /** 插件注册的悬浮球菜单项: name -> JS 回调函数名。 */
    val menuItems = linkedMapOf<String, String>()

    /** 插件注册的消息长按菜单项集合 (menuKey 字符串)。 */
    val msgMenuItem = mutableSetOf<String>()

    /** 已注册的动态 Activity 类名 (用于卸载时反注册)。 */
    val registedActivitys = mutableSetOf<String>()

    @Synchronized
    fun start() {
        if (info.isRunning) {
            stop(false)
        }
        info.updateFromDisk()

        // 优先 main.ts，兼容旧 main.js
        val tsFile = File(info.dirPath, "main.ts")
        val jsFile = File(info.dirPath, "main.js")
        val scriptFile = when {
            tsFile.exists() -> tsFile
            jsFile.exists() -> jsFile
            else -> throw IllegalStateException("未找到 main.ts / main.js 脚本文件")
        }

        try {
            val rawCode = scriptFile.readText()
            val jsCode = if (scriptFile.name.endsWith(".ts")) {
                TsTranspiler.transpile(rawCode)
            } else {
                rawCode
            }

            val instance = QuickJs.create(kotlinx.coroutines.Dispatchers.Default)
            quickJs = instance

            // 注入全局 API 桥接函数 (JS -> Kotlin)
            bridge.bindAll(instance)

            // 执行插件顶层代码
            runBlocking {
                instance.evaluate<Any?>(jsCode, filename = "main.ts", asModule = false)
            }

            registerCallbacks()
            info.isRunning = true
        } catch (e: Exception) {
            stop(false)
            throw e
        }
    }

    @Synchronized
    fun stop(invokeCallback: Boolean = true) {
        if (!info.isRunning && quickJs == null) return

        if (invokeCallback) {
            try {
                callback.unLoadPlugin()
            } catch (e: Exception) {
                PluginError.callError(e, info)
            }
        }

        try {
            registedActivitys.forEach { DynamicActivityRegistry.unregister(it) }
            removeCallbacks()
            menuItems.clear()
            msgMenuItem.clear()
            registedActivitys.clear()
        } catch (e: Exception) {
            PluginError.callError(e, info)
        } finally {
            runCatching { bridge.dispose() }
            runCatching { quickJs?.close() }
            quickJs = null
            info.isRunning = false
        }
    }

    /**
     * 在 QuickJs 上下文中执行一段 JS 表达式并返回结果。
     * 用于 Kotlin 侧调用 JS 全局函数/读取全局变量。
     * 返回 Any?，由调用方按需转换 (QuickJs evaluate 的 reified 泛型无法跨非 inline 函数使用)。
     */
    fun evaluateSync(code: String): Any? {
        val instance = quickJs ?: return null
        return runCatching {
            runBlocking { instance.evaluate<Any?>(code, "eval.ts") }
        }.onFailure {
            PluginError.callError(it, info)
        }.getOrNull()
    }

    /**
     * 判断 JS 全局是否存在某函数 (用于按需触发回调)。
     */
    fun hasFunction(name: String): Boolean {
        val instance = quickJs ?: return false
        return runCatching {
            runBlocking {
                instance.evaluate<Boolean?>("typeof $name === 'function'")
            }
        }.onFailure { PluginError.callError(it, info) }.getOrNull() == true
    }

    private fun registerCallbacks() {
        OnReceiveMsg.addListener(callback.receiveMsgListener)
        OnSendMsg.addListener(callback.sendMsgListener)
        OnTroopJoin.addListener(callback.troopJoinListener)
        OnTroopQuit.addListener(callback.troopQuitListener)
        OnTroopShutUp.addListener(callback.troopShutUpListener)
        OnPaiYiPai.addListener(callback.paiYiPaiListener)
        msgMenuItem.forEach { item ->
            val args = item.split(",")
            // menuKey 格式: [QFun],<id>,<name>,<callback>,<msgTypes...>
            if (args.size >= 4) {
                val callbackName = args[3]
                OnMenuBuild.addListener(object : MenuClickListener {
                    override val menuKey: String get() = item
                    override fun onClick(msgData: MsgData) {
                        callback.invokeMsgMenuItem(callbackName, msgData)
                    }
                })
            }
        }
    }

    private fun removeCallbacks() {
        OnReceiveMsg.removeListener(callback.receiveMsgListener)
        OnSendMsg.removeListener(callback.sendMsgListener)
        OnTroopJoin.removeListener(callback.troopJoinListener)
        OnTroopQuit.removeListener(callback.troopQuitListener)
        OnTroopShutUp.removeListener(callback.troopShutUpListener)
        OnPaiYiPai.removeListener(callback.paiYiPaiListener)
        msgMenuItem.forEach { item ->
            OnMenuBuild.listenerSet.removeIf { it.menuKey == item }
        }
    }

    companion object {
        @JvmStatic
        val myUin: String get() = QQCurrentEnv.currentUin
    }
}
