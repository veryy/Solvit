package dev.solvit.updates

import dev.solvit.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class StableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int =
        compareValuesBy(this, other, StableVersion::major, StableVersion::minor, StableVersion::patch)

    companion object {
        fun parse(value: String): StableVersion {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(value)
                ?: throw IOException("发布版本格式不正确")
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: throw IOException("发布版本超出范围") }
            return StableVersion(parts[0], parts[1], parts[2])
        }
    }
}

class GitHubReleaseUpdates(
    private val client: OkHttpClient,
    private val endpoint: String = "https://api.github.com/repos/veryy/Solvit/releases/latest",
) : UpdateService {
    override suspend fun check(installedVersion: String): UpdateResult {
        val installed = StableVersion.parse(installedVersion)
        val call = client.newCall(Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Solvit/$installedVersion")
            .header("X-GitHub-Api-Version", "2022-11-28").build())
        return call.await().use { response ->
            if (response.code == 404) return@use UpdateResult.Current
            if (!response.isSuccessful) throw IOException(when (response.code) {
                403, 429 -> "更新检查暂时受限，请稍后重试"
                else -> "更新检查失败（HTTP ${response.code}）"
            })
            val release = try {
                Json.parseToJsonElement(response.body?.string() ?: throw IOException("更新服务返回空响应")).jsonObject
            } catch (e: IllegalArgumentException) { throw IOException("更新服务返回无效数据", e) }
            if (release["draft"]?.jsonPrimitive?.booleanOrNull != false ||
                release["prerelease"]?.jsonPrimitive?.booleanOrNull != false) throw IOException("更新服务返回非正式版本")
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw IOException("发布版本缺失")
            if (StableVersion.parse(tag) <= installed) return@use UpdateResult.Current
            val page = release["html_url"]?.jsonPrimitive?.contentOrNull ?: throw IOException("发布页面缺失")
            checkGitHubUrl(page, "/veryy/Solvit/releases/tag/")
            val asset = release["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull {
                it["name"]?.jsonPrimitive?.contentOrNull == "solvit-debug.apk" &&
                    (it["size"]?.jsonPrimitive?.longOrNull ?: 0) > 0 &&
                    it["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
            } ?: throw IOException("新版本尚未提供 APK，请稍后检查")
            val apk = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: throw IOException("APK 下载地址缺失")
            checkGitHubUrl(apk, "/veryy/Solvit/releases/download/")
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull
            if (digest != null && !digest.matches(Regex("sha256:[a-f0-9]{64}"))) throw IOException("APK 校验摘要无效")
            UpdateResult.Available(AppRelease(tag.removePrefix("v"),
                release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), page, apk,
                asset.getValue("size").jsonPrimitive.long, digest?.removePrefix("sha256:")))
        }
    }

    private fun checkGitHubUrl(value: String, prefix: String) {
        val uri = try { URI(value) } catch (e: Exception) { throw IOException("发布链接无效", e) }
        if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null ||
            uri.port != -1 || !uri.path.startsWith(prefix)) throw IOException("发布链接不属于 Solvit 官方仓库")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}

class ReleaseUpdatesPlugin : SolvitPlugin {
    override val id = "builtin.app.updates"
    override val provides = setOf(UpdateServiceKey)
    override suspend fun enable(context: PluginContext) {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        context.own {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        context.register(UpdateServiceKey, GitHubReleaseUpdates(client))
    }
}
