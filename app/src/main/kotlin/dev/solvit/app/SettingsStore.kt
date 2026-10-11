package dev.solvit.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.solvit.core.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SettingsStore {
    suspend fun load(): ModelConfig
    suspend fun save(config: ModelConfig)
    suspend fun isConfigured(): Boolean = false
    suspend fun loadProfiles(): ModelProfilesState = ModelProfilesState(listOf(ModelProfile("default", "默认配置", load())), mapOf(ModelTask.CHAT to "default"), isConfigured())
    suspend fun saveProfiles(state: ModelProfilesState) { state.validate(); save(state.selected(ModelTask.CHAT)!!.config) }
}
class SettingsDecryptionException(val partial: ModelConfig, cause: Exception) :
    Exception("API Key 无法解密，其他模型配置已保留", cause)

/** Keys stay in Android Keystore; no plaintext API key or cloud backup. */
class EncryptedSettingsStore(context: Context, private val keyProvider: (() -> SecretKey)? = null) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val alias = "solvit.model-key.v1"
    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) { prefs.contains("base") && prefs.contains("model") }
    @Synchronized private fun key(): SecretKey {
        keyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun load(): ModelConfig {
        if (prefs.contains("profilesCipher")) return loadProfiles().selected(ModelTask.CHAT)!!.config
        return loadLegacy()
    }
    private suspend fun loadLegacy(): ModelConfig = withContext(Dispatchers.IO) {
        fun configuration(apiKey: String, listUrl: String = "") = ModelConfig(
            prefs.getString("base", "https://api.openai.com/v1")!!,
            prefs.getString("model", "gpt-4o-mini")!!,
            apiKey,
            prefs.getString("prompt", "你是 Solvit，一个认真、可靠的 AI 助手。")!!,
            listUrl, prefs.getBoolean("modelsAuth", false),
        )
        fun decrypt(valueName: String, ivName: String): String {
            val encoded = prefs.getString(valueName, null) ?: return ""
            val iv = Base64.decode(prefs.getString(ivName, null) ?: error("Missing encryption IV"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            return String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        }
        var apiKey = ""
        try {
            apiKey = decrypt("key", "iv")
            configuration(apiKey, decrypt("modelsUrlCipher", "modelsUrlIv"))
        } catch (e: Exception) {
            throw SettingsDecryptionException(configuration(apiKey), e)
        }
    }
    override suspend fun loadProfiles(): ModelProfilesState {
        val value = prefs.getString("profilesCipher", null)
        if (value == null) return ModelProfilesState(listOf(ModelProfile("default", "默认配置", loadLegacy())), mapOf(ModelTask.CHAT to "default"), isConfigured())
        return withContext(Dispatchers.IO) {
            try {
                val iv = Base64.decode(prefs.getString("profilesIv", null) ?: error("Missing profile IV"), Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
                val bytes = cipher.doFinal(Base64.decode(value, Base64.NO_WRAP))
                try { decodeModelProfiles(JSONObject(bytes.toString(Charsets.UTF_8))) } finally { bytes.fill(0) }
            } catch (e: Exception) {
                val partial = ModelConfig(prefs.getString("base", "https://api.openai.com/v1")!!, prefs.getString("model", "gpt-4o-mini")!!,
                    "", prefs.getString("prompt", "")!!)
                throw SettingsDecryptionException(partial, e)
            }
        }
    }
    override suspend fun save(config: ModelConfig) {
        config.validate()?.let { error(it) }
        val previous = loadProfiles()
        val id = previous.routes.getValue(ModelTask.CHAT)
        saveProfiles(previous.copy(profiles=previous.profiles.map { if (it.id == id) it.copy(config=config) else it }, configured=true))
    }
    override suspend fun saveProfiles(state: ModelProfilesState) = withContext(Dispatchers.IO) {
        state.validate()
        val config = state.selected(ModelTask.CHAT)!!.config
        fun encrypt(text: String): Pair<String,String> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val encoded = try { Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP) } finally { bytes.fill(0) }
            return encoded to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }
        val (encoded, iv) = encrypt(config.apiKey)
        val (listEncoded, listIv) = encrypt(config.modelListUrl)
        val (profilesEncoded, profilesIv) = encrypt(encodeModelProfiles(state).toString())
        // Canonical profiles and legacy-compatible primary fields commit in one transaction.
        check(prefs.edit().putString("base", config.baseUrl.trimEnd('/')).putString("model", config.model)
            .putString("prompt", config.systemPrompt).putString("key", encoded).putString("iv", iv)
            .putString("modelsUrlCipher", listEncoded).putString("modelsUrlIv", listIv)
            .putBoolean("modelsAuth", config.modelListUsesApiKey)
            .putString("profilesCipher", profilesEncoded).putString("profilesIv", profilesIv).commit()) { "保存配置失败" }
    }
}

internal fun encodeModelProfiles(state: ModelProfilesState): JSONObject = JSONObject().put("format",1)
    .put("configured", state.configured).put("profiles", JSONArray(state.profiles.map { profile -> JSONObject()
        .put("id",profile.id).put("name",profile.name).put("config",JSONObject()
            .put("base",profile.config.baseUrl).put("model",profile.config.model).put("key",profile.config.apiKey)
            .put("prompt",profile.config.systemPrompt).put("listUrl",profile.config.modelListUrl).put("listAuth",profile.config.modelListUsesApiKey).put("provider",profile.config.providerId).put("options",JSONObject(profile.config.providerOptions)).put("context",encodeContextBudget(profile.config.contextBudget))) }))
    .put("routes",JSONObject().apply { state.routes.forEach { (task,id) -> put(task.name,id) } })
internal fun decodeModelProfiles(root: JSONObject): ModelProfilesState {
    require(root.getInt("format") == 1)
    val list = root.getJSONArray("profiles"); require(list.length() in 1..32)
    val profiles = (0 until list.length()).map { i -> val row=list.getJSONObject(i); val c=row.getJSONObject("config")
        ModelProfile(row.getString("id"), row.getString("name"), ModelConfig(c.getString("base"),c.getString("model"),c.getString("key"),
            c.getString("prompt"),c.optString("listUrl",""),c.optBoolean("listAuth",false),c.optString("provider","openai-compatible"),decodeProviderOptions(c),decodeContextBudget(c))) }
    val routes=root.getJSONObject("routes").let { r -> r.keys().asSequence().associate { ModelTask.valueOf(it) to r.getString(it) } }
    return ModelProfilesState(profiles,routes,root.optBoolean("configured",true)).also { it.validate() }
}

internal fun decodeProviderOptions(config:JSONObject):Map<String,String> {
    val options=config.optJSONObject("options") ?: return emptyMap()
    require(options.length()<=32)
    return options.keys().asSequence().associateWith { options.getString(it) }
}

internal fun encodeContextBudget(policy: ContextBudgetPolicy): JSONObject = JSONObject()
    .put("window", policy.windowTokens).put("output", policy.outputTokens).put("headroom", policy.headroomTokens).put("automatic", policy.automaticSummary)
internal fun decodeContextBudget(config: JSONObject): ContextBudgetPolicy {
    val value = config.optJSONObject("context") ?: return ContextBudgetPolicy()
    return ContextBudgetPolicy(value.getInt("window"), value.getInt("output"), value.getInt("headroom"), value.getBoolean("automatic")).also { it.validate() }
}
