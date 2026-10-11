package dev.solvit.core

data class AppRelease(val version: String, val notes: String, val pageUrl: String, val apkUrl: String,
    val apkBytes: Long = 0, val apkSha256: String? = null)

sealed interface UpdateResult {
    data class Available(val release: AppRelease) : UpdateResult
    data object Current : UpdateResult
}

interface UpdateService {
    suspend fun check(installedVersion: String): UpdateResult
}

val UpdateServiceKey = ServiceKey("app.updates", UpdateService::class)
