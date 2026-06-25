package me.yxp.qfun.plugin.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.yxp.qfun.plugin.loader.PluginManager
import me.yxp.qfun.utils.io.FileUtils
import me.yxp.qfun.utils.net.HttpUtils
import me.yxp.qfun.utils.qq.QQCurrentEnv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ScriptService {

    /**
     * 插件市场列表 —— 托管在 GitHub 仓库（Spring-bulid/QAFUN）的静态 JSON。
     * 仓库所有者维护此文件，用户拉取即为已审核列表。
     * 修改此文件即更新市场列表，无需服务器。
     */
    private const val PLUGIN_LIST_URL =
        "https://raw.githubusercontent.com/Spring-bulid/QAFUN/main/plugins/list.json"

    /**
     * 用户上传入口 —— GitHub Issue。
     * 因 GitHub 不支持 multipart 直传仓库，改为引导用户通过 Issue 上传插件 zip。
     * Issue 提交后由仓库所有者审核，合并到 plugins/ 目录并更新 list.json。
     */
    private const val UPLOAD_ISSUE_URL =
        "https://github.com/Spring-bulid/QAFUN/issues/new?labels=plugin-upload&title=%E6%8F%92%E4%BB%B6%E4%B8%8A%E4%BC%A0%3A+"

    suspend fun fetchScriptList(): Result<List<ScriptInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = HttpUtils.getSuspend(PLUGIN_LIST_URL)

            if (response.isEmpty()) {
                return@runCatching Result.failure<List<ScriptInfo>>(
                    ScriptException("服务器响应为空")
                )
            }

            val jsonArr = JSONArray(response)
            val list = ArrayList<ScriptInfo>()

            for (i in jsonArr.length() - 1 downTo 0) {
                val item = jsonArr.getJSONObject(i)
                val info = ScriptInfo.parse(item)
                // GitHub 静态 JSON 均为仓库所有者审核后录入，无需 status 过滤
                list.add(info)
            }

            if (list.isEmpty()) {
                Result.failure(ScriptException("暂无可用脚本"))
            } else {
                Result.success(list)
            }
        }.getOrElse { e ->
            val errorMsg = when {
                e.message?.contains("timeout", true) == true -> "网络超时，请检查网络连接"
                e.message?.contains("connection", true) == true -> "网络连接失败"
                e.message?.contains("json", true) == true -> "数据格式错误"
                else -> "数据解析失败: ${e.message}"
            }
            Result.failure(ScriptException(errorMsg))
        }
    }

    suspend fun downloadAndInstall(script: ScriptInfo): Result<Unit> = withContext(Dispatchers.IO) {
        if (script.filename.isEmpty()) {
            return@withContext Result.failure(ScriptException("文件名无效"))
        }

        val cacheDir = File(QQCurrentEnv.currentDir, "cache").apply { mkdirs() }
        val tempZip = File(cacheDir, "download_${System.currentTimeMillis()}.zip")

        try {
            val downloadSuccess =
                HttpUtils.downloadSuspend(script.downloadUrl, tempZip.absolutePath)
            if (!downloadSuccess) {
                FileUtils.delete(tempZip)
                return@withContext Result.failure(ScriptException("下载请求失败"))
            }

            val errorMsg = PluginManager.installPluginFromZip(tempZip)
            FileUtils.delete(tempZip)

            if (errorMsg == null) {
                Result.success(Unit)
            } else {
                Result.failure(ScriptException(errorMsg))
            }
        } catch (e: Exception) {
            FileUtils.delete(tempZip)
            Result.failure(ScriptException("安装失败: ${e.message}"))
        }
    }

    /**
     * 上传插件 —— 引导用户到 GitHub Issue 页面提交。
     *
     * 因 GitHub 仓库不支持 multipart 直传文件到仓库内容，
     * 改为打开浏览器到仓库的 Issue 创建页（带预填标题），
     * 用户在 Issue 描述中附上插件信息，并将 zip 作为附件上传到 Issue。
     * 仓库所有者审核后，将插件合并到 plugins/ 目录并更新 list.json。
     *
     * 这样新插件会显示为「由 Spring-bulid/QAFUN 仓库审核发布」。
     */
    suspend fun uploadScript(
        id: String,
        author: String,
        desc: String,
        version: String,
        name: String,
        zipFile: File
    ): Result<UploadResult> = withContext(Dispatchers.IO) {
        if (!zipFile.exists() || !zipFile.name.endsWith(".zip", true)) {
            return@withContext Result.failure(ScriptException("无效的脚本文件"))
        }

        // 不再向服务器 POST 文件，而是返回 Issue 链接，由调用方打开浏览器
        val issueTitle = "$name v$version"
        val encodedTitle = java.net.URLEncoder.encode(issueTitle, "UTF-8")
        val issueUrl = "$UPLOAD_ISSUE_URL$encodedTitle"

        Result.success(
            UploadResult(
                id = id,
                status = "请通过 GitHub Issue 上传: $issueUrl",
                issueUrl = issueUrl
            )
        )
    }
}

class ScriptException(message: String) : Exception(message)
