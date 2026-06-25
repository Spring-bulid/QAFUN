package me.yxp.qfun.utils.net

import me.yxp.qfun.BuildConfig
import me.yxp.qfun.utils.json.str
import org.json.JSONObject

object UpdateManager {

    data class UpdateInfo(
        val releaseDate: String,
        val latestVersion: String,
        val releaseNotes: String,
        val downloadUrl: String
    )

    suspend fun checkUpdateSuspend(): UpdateInfo? {
        return runCatching {
            // 从 GitHub 仓库静态 update.json 读取版本信息
            val jsonStr = HttpUtils.getSuspend("${HttpUtils.HOST}/update.json")
            val json = JSONObject(jsonStr)
            val latestVersion = json["latest_version"].str ?: ""
            // 若仓库最新版本号 > 当前版本号，则视为有更新
            if (latestVersion.isNotEmpty() && latestVersion != BuildConfig.VERSION_NAME) {
                UpdateInfo(
                    releaseDate = json["release_date"].str ?: "",
                    latestVersion = latestVersion,
                    releaseNotes = json["release_notes"].str ?: "",
                    downloadUrl = json["download_url"].str ?: ""
                )
            } else null
        }.getOrNull()
    }
}