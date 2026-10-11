package dev.solvit.market

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/** Serializes every writer, including old storage.set, across isolated JS instances. */
internal class PluginDataStore(context: Context) {
    private val app = context.applicationContext
    private fun lock(pluginId: String) = locks.computeIfAbsent(app.filesDir.path + ":" + pluginId) { Mutex() }
    private fun preferences(pluginId: String) = app.getSharedPreferences("extension_data.$pluginId", Context.MODE_PRIVATE)
    private fun validateKey(key: String) { require(key.length in 1..256) { "存储键长度无效" } }
    suspend fun get(pluginId: String, key: String, verify: suspend () -> Unit = {}): JsonElement = withContext(Dispatchers.IO) {
        validateKey(key)
        lock(pluginId).withLock {
            currentCoroutineContext().ensureActive(); verify()
            preferences(pluginId).getString(key, null)?.let { Json.parseToJsonElement(it) } ?: JsonNull
        }
    }
    suspend fun set(pluginId: String, key: String, value: JsonElement, strictBytes: Boolean,
        verify: suspend () -> Unit) = withContext(Dispatchers.IO) {
        validateKey(key)
        val encoded = value.toString()
        require(if (strictBytes) encoded.toByteArray().size <= 256 * 1024 else encoded.length <= 256 * 1024) { "存储值过大" }
        lock(pluginId).withLock {
            currentCoroutineContext().ensureActive(); verify()
            check(preferences(pluginId).edit().putString(key, encoded).commit()) { "插件数据保存失败" }
        }
    }
    suspend fun compareAndSet(pluginId: String, key: String, expected: JsonElement, value: JsonElement,
        verify: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        validateKey(key)
        val encoded = value.toString()
        require(encoded.toByteArray().size <= 256 * 1024) { "存储值过大" }
        lock(pluginId).withLock {
            currentCoroutineContext().ensureActive(); verify()
            val prefs = preferences(pluginId)
            val current = prefs.getString(key, null)?.let { Json.parseToJsonElement(it) } ?: JsonNull
            if (current != expected) false else {
                check(prefs.edit().putString(key, encoded).commit()) { "插件数据保存失败" }
                true
            }
        }
    }
    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
