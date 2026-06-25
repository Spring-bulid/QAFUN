package me.yxp.qfun.hook.security

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Debug
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.hook.base.BaseSwitchHookItem
import me.yxp.qfun.hook.base.BaseSwitchHookItem.BooleanPreference
import me.yxp.qfun.utils.hook.hookAfter
import me.yxp.qfun.utils.hook.hookReplace
import me.yxp.qfun.utils.log.LogUtils
import me.yxp.qfun.utils.reflect.TAG
import me.yxp.qfun.utils.reflect.findMethodOrNull

/**
 * 防检测：隐藏 Xposed/LSPosed 模块、调试器痕迹，降低 QQ 风控检测。
 *
 * 安全策略（v2.4 紧急修复）：
 * 仅保留低风险 hook，避免全局拦截 File/Runtime/SystemProperties 导致 QQ 崩溃。
 *
 * 覆盖检测面：
 * 1. PackageManager.getInstalledPackages / getInstalledApplications —— 从已安装列表过滤敏感包名
 * 2. Debug.isDebuggerConnected —— 强制返回 false
 *
 * 全进程加载（process="All"），默认开启。
 */
@HookItemAnnotation(
    "防检测（防踢号）",
    "隐藏Xposed/模块/调试器痕迹，防止QQ风控检测踢号",
    HookCategory.OTHER,
    process = "All"
)
object AntiDetect : BaseSwitchHookItem() {

    override var isEnable: Boolean by BooleanPreference(name, true)

    /** 包名关键词：命中任一即视为敏感包，从已安装列表中过滤掉 */
    private val sensitivePkgKeywords = listOf(
        "xposed", "lsposed", "edxposed", "magisk", "supersu", "superuser",
        "qfun", "framaroid", "kingroot", "kingo", "oneclickroot", "rootcloak",
        "chainfire", "noshufou", "dimonvideo", "chelpus"
    )

    override fun onHook() {
        runCatching { hookPackageManager() }
        runCatching { hookDebugger() }
    }

    // ---------- PackageManager：仅过滤列表，不拦截单个查询 ----------

    private fun hookPackageManager() {
        val apmClass = runCatching {
            Class.forName("android.app.ApplicationPackageManager")
        }.getOrNull() ?: run {
            LogUtils.e(TAG, RuntimeException("ApplicationPackageManager not found"))
            return
        }

        // getInstalledPackages(int) → 过滤敏感包
        apmClass.findMethodOrNull {
            name = "getInstalledPackages"; paramTypes(int)
        }?.hookAfter(this) { param ->
            @Suppress("UNCHECKED_CAST")
            val list = param.result as? List<PackageInfo> ?: return@hookAfter
            param.result = list.filterNot { isSensitivePkg(it.packageName) }
        }

        // getInstalledApplications(int) → 过滤敏感包
        apmClass.findMethodOrNull {
            name = "getInstalledApplications"; paramTypes(int)
        }?.hookAfter(this) { param ->
            @Suppress("UNCHECKED_CAST")
            val list = param.result as? List<ApplicationInfo> ?: return@hookAfter
            param.result = list.filterNot { isSensitivePkg(it.packageName) }
        }
    }

    // ---------- Debug：极安全 ----------

    private fun hookDebugger() {
        Debug::class.java.findMethodOrNull {
            name = "isDebuggerConnected"; paramCount = 0; returnType = boolean; isStatic = true
        }?.hookReplace(this) { false }
    }

    private fun isSensitivePkg(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val lower = pkg.lowercase()
        return sensitivePkgKeywords.any { lower.contains(it) }
    }
}
