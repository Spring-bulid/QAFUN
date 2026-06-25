package me.yxp.qfun.hook.social

import com.tencent.util.QQToastUtil
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.hook.base.BaseSwitchHookItem
import me.yxp.qfun.utils.hook.hookBefore
import me.yxp.qfun.utils.reflect.findMethod

/**
 * 无限制拍一拍：绕过 QQ 服务端对拍一拍的频率限制提示。
 *
 * 配合已有的「拍一拍连拍」使用：
 *  - MultiPaiYiPai 已绕过客户端侧的 check 校验（returnConstant true）
 *  - 本 hook 进一步拦截服务端返回的「休息一下/操作过于频繁」等 toast 提示
 *  - 同时拦截 QQ 的 Toast 显示通道，使限制提示不再弹出
 *
 * 注意：本功能仅屏蔽提示，实际发送是否成功仍取决于 QQ 服务端。
 * 在服务端真实限流时，多发的拍一拍请求可能被服务端静默丢弃。
 */
@HookItemAnnotation(
    "无限制拍一拍",
    "屏蔽拍一拍频率限制提示（休息一下/操作频繁等），配合连拍使用更爽",
    HookCategory.SOCIAL
)
object UnlimitedPaiYiPai : BaseSwitchHookItem() {

    /** 命中即拦截的关键词（覆盖拍一拍限流的常见提示文案） */
    private val blockKeywords = arrayOf(
        "休息一下",
        "操作过于频繁",
        "操作频繁",
        "稍后再试",
        "拍太多了",
        "不要太频繁"
    )

    private lateinit var showQQToast: java.lang.reflect.Method

    override fun onInit(): Boolean {
        showQQToast = QQToastUtil::class.java.findMethod {
            name = "showQQToastInUiThread"
            paramTypes(Int::class.javaPrimitiveType, String::class.java)
        }
        return super.onInit()
    }

    override fun onHook() {
        showQQToast.hookBefore(this) { param ->
            val msg = param.args[1] as? String ?: return@hookBefore
            if (blockKeywords.any { msg.contains(it) }) {
                // 命中限流提示，设置 result 跳过原方法不显示
                param.result = null
            }
        }
    }
}
