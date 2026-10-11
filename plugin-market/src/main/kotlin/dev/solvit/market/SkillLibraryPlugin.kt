package dev.solvit.market

import dev.solvit.core.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Parse user-selected files in memory; never extract paths to the filesystem. */
object SkillImports {
    const val MAX_INPUT = 512 * 1024
    private fun text(bytes: ByteArray) = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun parse(bytes: ByteArray): SkillBundle {
        require(bytes.size in 1..MAX_INPUT) { "技能导入文件需在 512 KiB 内" }
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            val raw = linkedMapOf<String, String>()
            var count = 0
            var total = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 80) { "技能包文件过多" }
                    val path = entry.name
                    require(path.length <= 240 && !path.startsWith('/') && '\\' !in path && path.split('/').none { it == "." || it == ".." }) { "技能包路径无效" }
                    if (entry.isDirectory) continue
                    require(path !in raw && raw.size < 40) { "技能包文件重复或过多" }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() <= 32 * 1024) {
                        val read = zip.read(buffer, 0, minOf(buffer.size, 32 * 1024 + 1 - output.size()))
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    val content = output.toByteArray()
                    require(content.size <= 32 * 1024) { "技能文本文件超过 32 KiB" }
                    total += content.size
                    require(total <= 220 * 1024) { "技能包解压内容过大" }
                    raw[path] = text(content)
                }
            }
            val main = raw.keys.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }.singleOrNull()
                ?: error("技能包必须包含唯一的 SKILL.md")
            val prefix = main.removeSuffix("SKILL.md")
            require(prefix.isEmpty() || prefix.removeSuffix("/").matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "技能包需为技能目录或其内容" }
            require(raw.keys.all { it.startsWith(prefix) }) { "技能包包含目录外文件" }
            return SkillBundle(prefix.removeSuffix("/").ifEmpty { null }, raw.mapKeys { it.key.removePrefix(prefix) })
        }
        val source = text(bytes).removePrefix("\uFEFF")
        if (source.trimStart().startsWith('{')) {
            val root = Json.parseToJsonElement(source).jsonObject
            require(root.getValue("format").jsonPrimitive.int == 1)
            val files = root.getValue("files").jsonObject.mapValues { (_, value) ->
                require(value.jsonPrimitive.isString); value.jsonPrimitive.content
            }
            require(files.size in 1..40 && files.values.all { it.toByteArray(Charsets.UTF_8).size <= 32 * 1024 }) { "技能文本文件过多或过大" }
            return SkillBundle(root["directory"]?.jsonPrimitive?.contentOrNull, files)
        }
        require(bytes.size <= 32 * 1024) { "SKILL.md 超过 32 KiB" }
        return SkillBundle(null, mapOf("SKILL.md" to source))
    }
}

class NativeSkillLibrary(private val market: MarketplaceService) : SkillLibraryService {
    override fun preview(bytes: ByteArray) = SkillImports.parse(bytes)
    private suspend fun call(tool: String, arguments: JsonObject = buildJsonObject { }): JsonObject {
        check(market.installed.value.any { it.id == "dev.solvit.skills" && it.enabled }) { "请在插件页启用 Skills 技能库并授予 storage 权限" }
        val result = market.runTool("dev_solvit_skills_$tool", arguments.toString(), object : ToolAuthorization {
            // Only reachable through explicit library UI actions, never from the model.
            override suspend fun authorize(descriptor: ToolDescriptor) = descriptor.pluginId == "dev.solvit.skills"
        })
        return Json.parseToJsonElement(result).jsonObject
    }
    private fun summary(o: JsonObject) = SkillSummary(o.getValue("name").jsonPrimitive.content,
        o.getValue("description").jsonPrimitive.content, o.getValue("resources").jsonArray.map { it.jsonPrimitive.content })
    override suspend fun list() = call("skills_list").getValue("skills").jsonArray.map { summary(it.jsonObject) }
    override suspend fun load(name: String): SkillContent {
        val result = call("skills_load", buildJsonObject { put("name", name) })
        return SkillContent(summary(result), result.getValue("instructions").jsonPrimitive.content)
    }
    override suspend fun importSkill(bundle: SkillBundle, replace: Boolean) {
        call("skills_import", buildJsonObject {
            putJsonObject("files") { bundle.files.forEach { (path, content) -> put(path, content) } }
            bundle.directory?.let { put("directory", it) }; put("replace", replace)
        })
    }
    override suspend fun remove(name: String) { call("skills_remove", buildJsonObject { put("name", name) }) }
    override suspend fun export(name: String) = call("skills_export", buildJsonObject { put("name", name) }).toString()
}

class SkillLibraryPlugin : SolvitPlugin {
    override val id = "builtin.skills.library"
    override val requires = setOf(MarketplaceServiceKey)
    override val provides = setOf(SkillLibraryServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(SkillLibraryServiceKey, NativeSkillLibrary(context.require(MarketplaceServiceKey))) }
}
