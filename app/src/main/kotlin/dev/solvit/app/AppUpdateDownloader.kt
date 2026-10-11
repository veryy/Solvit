package dev.solvit.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.solvit.core.AppRelease
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

sealed interface ApkDownloadState {
    data object Idle : ApkDownloadState
    data class Downloading(val bytes: Long, val total: Long, val waiting: Boolean = false) : ApkDownloadState
    data object Verifying : ApkDownloadState
    data class Ready(val file: File) : ApkDownloadState
    data class Failed(val message: String) : ApkDownloadState
}

/** DownloadManager keeps transfers alive when the screen or process is closed. */
class AppUpdateDownloader(private val context: Context, private val scope: CoroutineScope) {
    private val strings by lazy { UiStrings.load(context) }
    private fun ui(source: String): String = strings.text(source, (context.applicationContext as? SolvitApplication)?.language?.language?.value ?: "zh")
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk_update", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle)
    val state = mutableState.asStateFlow()
    private var watcher: Job? = null
    init {
        if (prefs.getString("version", null) == BuildConfig.VERSION_NAME) cancel()
        else if (prefs.contains("id")) monitor()
    }

    @Synchronized fun download(release: AppRelease) {
        if (mutableState.value is ApkDownloadState.Downloading || mutableState.value == ApkDownloadState.Verifying) return
        try {
            require(release.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            val uri = Uri.parse(release.apkUrl)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
                uri.path?.startsWith("/veryy/Solvit/releases/download/") == true)
            cancel()
            val relative = "updates/solvit-${release.version}.apk"
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), relative).delete()
            val id = manager.enqueue(DownloadManager.Request(uri)
                .setTitle("Solvit ${release.version}")
                .setDescription(ui("下载完成后返回 Solvit 安装更新"))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, relative))
            check(prefs.edit().putLong("id", id).putString("file", relative)
                .putLong("bytes", release.apkBytes).putString("sha256", release.apkSha256).putString("version", release.version).commit())
            mutableState.value = ApkDownloadState.Downloading(0, release.apkBytes)
            monitor()
        } catch (e: Exception) { mutableState.value = ApkDownloadState.Failed("无法开始下载，请检查网络和可用空间") }
    }
    @Synchronized fun cancel() {
        watcher?.cancel(); watcher = null
        val id = prefs.getLong("id", -1)
        if (id != -1L) manager.remove(id)
        prefs.edit().clear().commit()
        mutableState.value = ApkDownloadState.Idle
    }
    private fun monitor() {
        watcher?.cancel()
        watcher = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val id = prefs.getLong("id", -1)
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        check(cursor.moveToFirst()) { "下载任务不存在，请重新下载" }
                        fun int(name: String) = cursor.getInt(cursor.getColumnIndexOrThrow(name))
                        fun long(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                        when (int(DownloadManager.COLUMN_STATUS)) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                mutableState.value = ApkDownloadState.Verifying
                                val relative = prefs.getString("file", null) ?: error("下载文件不存在")
                                require(relative.matches(Regex("updates/solvit-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk")))
                                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), relative)
                                verify(file)
                                mutableState.value = ApkDownloadState.Ready(file)
                                return@launch
                            }
                            DownloadManager.STATUS_FAILED -> error("APK 下载失败，请检查网络或存储空间后重试")
                            else -> mutableState.value = ApkDownloadState.Downloading(
                                long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                                int(DownloadManager.COLUMN_STATUS) == DownloadManager.STATUS_PAUSED)
                        }
                    }
                    delay(500)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = ApkDownloadState.Failed(e.message ?: "下载失败，请重试") }
        }
    }
    @Suppress("DEPRECATION")
    private fun verify(file: File) {
        val expected = prefs.getLong("bytes", 0)
        check(file.isFile && file.length() > 0 && (expected == 0L || file.length() == expected)) { "APK 文件不完整，请重新下载" }
        prefs.getString("sha256", null)?.let { expectedHash ->
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream -> val buffer = ByteArray(8192); while (true) {
                val count = stream.read(buffer); if (count == -1) break; hash.update(buffer, 0, count)
            } }
            check(hash.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "APK 完整性校验失败，请重新下载" }
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("下载文件不是有效 APK")
        check(archive.packageName == context.packageName) { "下载包不属于 Solvit" }
        fun certificates(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty()
                .map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        val current = certificates(installed)
        check(current.isNotEmpty() && certificates(archive) == current) {
            "新版签名与已安装版本不同，无法覆盖安装。请先备份配置；不要直接卸载。"
        }
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(newCode > oldCode) { "下载版本不高于当前版本，请重新检查更新" }
    }
    fun install() {
        val ready = mutableState.value as? ApkDownloadState.Ready ?: return
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            // Revalidate before each installation; the external file may have changed.
            scope.launch(Dispatchers.IO) {
                try {
                    verify(ready.file)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready.file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { mutableState.value = ApkDownloadState.Failed(e.message ?: "无法打开安装程序") }
            }
        } catch (e: Exception) { mutableState.value = ApkDownloadState.Failed("无法打开安装设置，请在系统设置允许 Solvit 安装应用") }
    }
}
