package me.yxp.qfun.hook.troop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.runtime.Composable
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import kotlinx.coroutines.runBlocking
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.conf.ChatStatsData
import me.yxp.qfun.conf.GroupStats
import me.yxp.qfun.hook.api.MenuClickListener
import me.yxp.qfun.hook.api.ReceiveMsgListener
import me.yxp.qfun.hook.base.BaseClickableHookItem
import me.yxp.qfun.plugin.bean.MemberInfo
import me.yxp.qfun.plugin.bean.MsgData
import me.yxp.qfun.ui.pages.configs.ChatStatsPage
import me.yxp.qfun.utils.io.FileUtils
import me.yxp.qfun.utils.qq.MsgTool
import me.yxp.qfun.utils.qq.QQCurrentEnv
import me.yxp.qfun.utils.qq.TroopTool
import me.yxp.qfun.utils.qq.Toasts
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HookItemAnnotation(
    "群聊统计仪表盘",
    "实时追踪群聊活跃度，生成数据可视化仪表盘（排行榜/热力图）",
    HookCategory.GROUP
)
object ChatStats : BaseClickableHookItem<ChatStatsData>(ChatStatsData.serializer()),
    ReceiveMsgListener, MenuClickListener {

    override val defaultConfig: ChatStatsData = ChatStatsData()

    override val menuKey: String
        get() = if (isEnable) "[QFun],ChatStats,查看本群统计,," else ""

    private var updateCounter = 0

    // QAFUN 主题色
    private const val C_BG = 0xFF0A0B14.toInt()
    private const val C_BG2 = 0xFF12132A.toInt()
    private const val C_CARD = 0xFF161827.toInt()
    private const val C_CARD2 = 0xFF1E2033.toInt()
    private const val C_PURPLE = 0xFF7C5CFF.toInt()
    private const val C_CYAN = 0xFF00D9C0.toInt()
    private const val C_TEXT_MAIN = 0xFFEAEBF5.toInt()
    private const val C_TEXT_SUB = 0xFF8B8DA3.toInt()
    private const val C_GOLD = 0xFFFFD700.toInt()
    private const val C_STROKE = 0x337C5CFF

    override fun onReceive(msgRecord: MsgRecord) {
        if (msgRecord.chatType != 2) return
        val peerUin = msgRecord.peerUin.toString()
        val userUin = msgRecord.senderUin.toString()
        val time = msgRecord.msgTime
        if (peerUin.isEmpty() || userUin.isEmpty()) return

        val groups = configState.groups.toMutableMap()
        val group = groups[peerUin] ?: GroupStats()
        val hour = ((time / 3600) % 24).toInt()
        val newHours = group.hours.toMutableList()
        newHours[hour] = newHours[hour] + 1
        val newMembers = group.members.toMutableMap()
        newMembers[userUin] = (newMembers[userUin] ?: 0L) + 1
        groups[peerUin] = group.copy(
            total = group.total + 1,
            startTime = if (group.startTime == 0L) time else group.startTime,
            members = newMembers,
            hours = newHours
        )
        configState = configState.copy(groups = groups)

        updateCounter++
        if (updateCounter % 10 == 0) saveData()
    }

    override fun onClick(msgData: MsgData) {
        val peerUin = msgData.peerUin
        val group = config.groups[peerUin]
        if (group == null || group.total == 0L) {
            Toasts.qqToast(0, "本群暂无统计数据")
            return
        }
        runCatching {
            val path = generateDashboardImage(peerUin, group, msgData.contact.chatType)
            MsgTool.sendPic(msgData.contact, path)
            Toasts.qqToast(2, "统计卡片已发送")
        }.onFailure {
            Toasts.qqToast(1, "发送失败: ${it.message}")
        }
    }

    // ==================== 渲染引擎 ====================

    fun getMemberNames(groupUin: String): Map<String, String> {
        return runCatching {
            runBlocking {
                val list = TroopTool.getGroupMemberList(groupUin) ?: return@runBlocking emptyMap()
                list.associate { it.uin to it.uinName.ifEmpty { "QQ${it.uin}" } }
            }
        }.getOrDefault(emptyMap())
    }

    fun renderDashboard(peerUin: String, group: GroupStats, groupName: String): Bitmap {
        val nameMap = getMemberNames(peerUin)
        val displayName = groupName.ifEmpty { "群 $peerUin" }
        val now = System.currentTimeMillis() / 1000

        val leaderboard = group.members.entries.sortedByDescending { it.value }.take(10)
        val maxMember = leaderboard.maxOfOrNull { it.value } ?: 1L

        var maxHour = 0L
        var peakHour = 0
        for (i in 0 until 24) {
            if (group.hours[i] > maxHour) { maxHour = group.hours[i]; peakHour = i }
        }

        val W = 900
        val pad = 32
        val contentW = W - pad * 2
        val sGap = 22
        val rows = leaderboard.size.coerceAtLeast(1)

        var H = pad + 72 + 18 + 30 + 6 + 26 + sGap + 120 + sGap + 34 + rows * 46 + sGap + 34 + 96 + 6 + 28 + sGap + 26 + pad

        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景
        val bgPaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), C_BG, C_BG2, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bgPaint)

        // 网格点缀
        val dotPaint = Paint().apply { color = 0x117C5CFF }
        var i = 0
        while (i < W) { var j = 0; while (j < H) { canvas.drawCircle(i.toFloat(), j.toFloat(), 1f, dotPaint); j += 40 }; i += 40 }

        var y = pad

        // 品牌标题栏
        val brandRect = RectF(pad.toFloat(), y.toFloat(), (W - pad).toFloat(), (y + 72).toFloat())
        val brandPaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(pad.toFloat(), y.toFloat(), (W - pad).toFloat(), (y + 72).toFloat(), C_PURPLE, C_CYAN, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(brandRect, 18f, 18f, brandPaint)
        val brandText = TextPaint().apply { isAntiAlias = true; color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 30f }
        val brandStr = "QAFUN  群聊统计仪表盘"
        canvas.drawText(brandStr, (W - brandText.measureText(brandStr)) / 2, y + 46f, brandText)
        y += 72 + 18

        // 群名
        val groupPaint = TextPaint().apply { isAntiAlias = true; color = C_TEXT_MAIN; textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        canvas.drawText("群名称: $displayName", pad.toFloat(), (y + 24).toFloat(), groupPaint)
        y += 30 + 6

        // 统计周期
        val periodPaint = TextPaint().apply { isAntiAlias = true; color = C_TEXT_SUB; textSize = 18f }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val period = "统计周期: ${sdf.format(Date(group.startTime * 1000))} ~ ${sdf.format(Date(now * 1000))}"
        canvas.drawText(period, pad.toFloat(), (y + 22).toFloat(), periodPaint)
        y += 26 + sGap

        // 统计卡片
        val cardW = (contentW - 16) / 2
        drawStatCard(canvas, pad, y, cardW, 120, fmtNum(group.total), "总消息数", C_PURPLE)
        drawStatCard(canvas, pad + cardW + 16, y, cardW, 120, fmtNum(group.members.size.toLong()), "活跃成员", C_CYAN)
        y += 120 + sGap

        // 排行榜标题
        drawSectionTitle(canvas, pad, y, "活跃排行榜 TOP $rows")
        y += 34

        // 排行榜行
        if (leaderboard.isEmpty()) {
            val emptyPaint = TextPaint().apply { isAntiAlias = true; color = C_TEXT_SUB; textSize = 16f }
            canvas.drawText("暂无统计数据,多发几条消息试试吧 ~", pad.toFloat(), (y + 28).toFloat(), emptyPaint)
            y += 46
        } else {
            val medals = intArrayOf(C_GOLD, 0xFFC0C0C0.toInt(), 0xFFCD7F32.toInt())
            for (idx in leaderboard.indices) {
                val (uin, cnt) = leaderboard[idx]
                val name = nameMap[uin] ?: "QQ$uin"
                val ratio = maxMember.toFloat() / maxMember.coerceAtLeast(1)
                val pct = if (group.total > 0) cnt.toFloat() / group.total * 100 else 0f
                drawLeaderboardRow(canvas, pad, y, contentW, 46, idx + 1, name, cnt,
                    cnt.toFloat() / maxMember.coerceAtLeast(1), pct, if (idx < 3) medals[idx] else 0)
                y += 46
            }
        }
        y += sGap

        // 热力图标题
        drawSectionTitle(canvas, pad, y, "24 小时活跃热力图")
        y += 34

        // 热力图 2x12
        val block = 30
        val gap = 6
        val totalBlockW = block + gap
        for (row in 0 until 2) {
            for (col in 0 until 12) {
                val hour = row * 12 + col
                val cnt = group.hours[hour]
                val intensity = if (maxHour > 0) cnt.toFloat() / maxHour else 0f
                val blockColor = when {
                    cnt == 0L -> C_CARD2
                    intensity > 0.7f -> lerpColor(C_CYAN, C_PURPLE, (intensity - 0.7f) / 0.3f)
                    else -> lerpColor(0xFF2A1F4A.toInt(), C_CYAN, intensity)
                }
                val bp = Paint().apply { isAntiAlias = true; color = blockColor }
                val br = RectF((pad + col * totalBlockW).toFloat(), y.toFloat(),
                    (pad + col * totalBlockW + block).toFloat(), (y + block).toFloat())
                canvas.drawRoundRect(br, 5f, 5f, bp)
                val hp = TextPaint().apply { isAntiAlias = true; color = if (cnt > 0) 0xFFFFFFFF.toInt() else C_TEXT_SUB; textSize = 11f }
                val hs = hour.toString()
                canvas.drawText(hs, br.centerX() - hp.measureText(hs) / 2, br.centerY() + 4, hp)
            }
            y += block + gap
        }
        y += 22

        // 最活跃时段
        val peakPaint = TextPaint().apply { isAntiAlias = true; color = C_CYAN; textSize = 17f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val peakStr = if (maxHour == 0L) "最活跃时段: 暂无数据"
                      else "最活跃时段: ${String.format("%02d", peakHour)}:00 - ${String.format("%02d", (peakHour + 1) % 24)}:00"
        canvas.drawText(peakStr, pad.toFloat(), (y + 20).toFloat(), peakPaint)
        y += 28 + sGap

        // 页脚
        val footPaint = TextPaint().apply { isAntiAlias = true; color = C_TEXT_SUB; textSize = 13f }
        val foot = "Powered by QAFUN · 数据本地持久化保存"
        canvas.drawText(foot, (W - footPaint.measureText(foot)) / 2, (y + 18).toFloat(), footPaint)

        return bitmap
    }

    private fun drawStatCard(c: Canvas, x: Int, y: Int, w: Int, h: Int, value: String, label: String, accent: Int) {
        val cp = Paint().apply { isAntiAlias = true; shader = LinearGradient(x.toFloat(), y.toFloat(), x.toFloat(), (y + h).toFloat(), C_CARD2, C_CARD, Shader.TileMode.CLAMP) }
        val rect = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        c.drawRoundRect(rect, 16f, 16f, cp)
        val sp = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f; color = C_STROKE }
        c.drawRoundRect(rect, 16f, 16f, sp)
        val dp = Paint().apply { isAntiAlias = true; color = accent }
        c.drawRoundRect(RectF((x + 16).toFloat(), (y + 12).toFloat(), (x + 48).toFloat(), (y + 16).toFloat()), 2f, 2f, dp)
        val vp = TextPaint().apply { isAntiAlias = true; color = 0xFFFFFFFF.toInt(); textSize = 40f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        c.drawText(value, x + (w - vp.measureText(value)) / 2, (y + 68).toFloat(), vp)
        val lp = TextPaint().apply { isAntiAlias = true; color = C_TEXT_SUB; textSize = 16f }
        c.drawText(label, x + (w - lp.measureText(label)) / 2, (y + 98).toFloat(), lp)
    }

    private fun drawSectionTitle(c: Canvas, x: Int, y: Int, title: String) {
        val bp = Paint().apply { isAntiAlias = true; shader = LinearGradient(x.toFloat(), (y + 4).toFloat(), x.toFloat(), (y + 28).toFloat(), C_PURPLE, C_CYAN, Shader.TileMode.CLAMP) }
        c.drawRoundRect(RectF(x.toFloat(), (y + 4).toFloat(), (x + 5).toFloat(), (y + 28).toFloat()), 2f, 2f, bp)
        val tp = TextPaint().apply { isAntiAlias = true; color = C_TEXT_MAIN; textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        c.drawText(title, (x + 14).toFloat(), (y + 24).toFloat(), tp)
    }

    private fun drawLeaderboardRow(c: Canvas, x: Int, y: Int, w: Int, h: Int, rank: Int, name: String, count: Long, ratio: Float, pct: Float, medal: Int) {
        val cy = y + h / 2
        val rp = Paint().apply { isAntiAlias = true; color = if (medal != 0) medal else C_CARD2 }
        c.drawCircle((x + 18).toFloat(), cy.toFloat(), 14f, rp)
        val rkp = TextPaint().apply { isAntiAlias = true; color = 0xFFFFFFFF.toInt(); textSize = 15f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val rs = rank.toString()
        c.drawText(rs, (x + 18 - rkp.measureText(rs) / 2).toFloat(), (cy + 5).toFloat(), rkp)

        val np = TextPaint().apply { isAntiAlias = true; color = C_TEXT_MAIN; textSize = 16f }
        val dn = if (name.length > 10) name.substring(0, 10) + "..." else name
        c.drawText(dn, (x + 42).toFloat(), (cy - 4).toFloat(), np)

        val barX = x + 42; val barY = cy + 8; val barW = w - 42 - 150; val barH = 6
        val bbp = Paint().apply { isAntiAlias = true; color = C_CARD2 }
        c.drawRoundRect(RectF(barX.toFloat(), barY.toFloat(), (barX + barW).toFloat(), (barY + barH).toFloat()), 3f, 3f, bbp)
        if (ratio > 0) {
            val bfp = Paint().apply { isAntiAlias = true; shader = LinearGradient(barX.toFloat(), barY.toFloat(), (barX + barW * ratio).toFloat(), barY.toFloat(), C_PURPLE, C_CYAN, Shader.TileMode.CLAMP) }
            c.drawRoundRect(RectF(barX.toFloat(), barY.toFloat(), (barX + barW * ratio).toFloat(), (barY + barH).toFloat()), 3f, 3f, bfp)
        }
        val cntP = TextPaint().apply { isAntiAlias = true; color = C_CYAN; textSize = 15f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val cs = fmtNum(count)
        c.drawText(cs, (x + w - cntP.measureText(cs) - 4).toFloat(), (cy - 4).toFloat(), cntP)
        val pp = TextPaint().apply { isAntiAlias = true; color = C_TEXT_SUB; textSize = 12f }
        val ps = String.format("%.1f%%", pct)
        c.drawText(ps, (x + w - pp.measureText(ps) - 4).toFloat(), (cy + 14).toFloat(), pp)
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val a1 = (c1 shr 24) and 0xFF; val r1 = (c1 shr 16) and 0xFF; val g1 = (c1 shr 8) and 0xFF; val b1 = c1 and 0xFF
        val a2 = (c2 shr 24) and 0xFF; val r2 = (c2 shr 16) and 0xFF; val g2 = (c2 shr 8) and 0xFF; val b2 = c2 and 0xFF
        return ((a1 + (a2 - a1) * tt).toInt() shl 24) or ((r1 + (r2 - r1) * tt).toInt() shl 16) or ((g1 + (g2 - g1) * tt).toInt() shl 8) or (b1 + (b2 - b1) * tt).toInt()
    }

    private fun fmtNum(n: Long): String {
        val s = n.toString()
        val sb = StringBuilder()
        val len = s.length
        for (i in s.indices) {
            sb.append(s[i])
            if (i < len - 1 && (len - i - 1) % 3 == 0) sb.append(',')
        }
        return sb.toString()
    }

    fun generateDashboardImage(peerUin: String, group: GroupStats, chatType: Int, groupName: String = ""): String {
        val bitmap = renderDashboard(peerUin, group, groupName)
        val cacheDir = File("${QQCurrentEnv.currentDir}cache/chat_stats").apply { mkdirs() }
        val file = File(cacheDir, "stats_${peerUin}_${System.currentTimeMillis()}.png")
        FileUtils.ensureFile(file)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    fun clearGroup(peerUin: String) {
        val groups = configState.groups.toMutableMap()
        groups.remove(peerUin)
        configState = configState.copy(groups = groups)
        saveData()
    }

    @Composable
    override fun ConfigContent(onDismiss: () -> Unit) {
        ChatStatsPage(
            statsData = config,
            onSave = ::updateConfig,
            onDismiss = onDismiss
        )
    }
}
