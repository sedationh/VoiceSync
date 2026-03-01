package com.sedationh.voicesync

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通过 GitHub Releases API 检查应用更新
 * 参考 EpubSpoon 项目实现
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val API_URL =
        "https://api.github.com/repos/sedationh/VoiceSync/releases/latest"

    data class UpdateResult(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,    // APK 下载链接
        val htmlUrl: String         // Release 页面链接（备用）
    )

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("assets") val assets: List<GitHubAsset>?
    )

    data class GitHubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val browserDownloadUrl: String,
        @SerializedName("content_type") val contentType: String?
    )

    /**
     * 检查是否有新版本
     * @param currentVersion 当前应用版本号，如 "0.0.5"
     * @return UpdateResult 或 null（网络错误时）
     */
    suspend fun check(currentVersion: String): UpdateResult? = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "GitHub API returned $responseCode")
                return@withContext null
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val release = Gson().fromJson(json, GitHubRelease::class.java)

            // tag_name 通常是 "v0.0.5"，去掉 "v" 前缀
            val latestVersion = release.tagName.removePrefix("v")

            // 找 APK asset
            val apkAsset = release.assets?.firstOrNull { asset ->
                asset.name.contains("Android", ignoreCase = true) && 
                asset.name.endsWith(".apk", ignoreCase = true)
            }

            val hasUpdate = isNewerVersion(latestVersion, currentVersion)

            UpdateResult(
                hasUpdate = hasUpdate,
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                releaseNotes = release.body ?: release.name ?: "",
                downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl,
                htmlUrl = release.htmlUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            null
        }
    }

    /**
     * 简单的版本号比较：支持 "0.0.5", "1.0.0" 等格式
     * @return true 如果 latest > current
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
