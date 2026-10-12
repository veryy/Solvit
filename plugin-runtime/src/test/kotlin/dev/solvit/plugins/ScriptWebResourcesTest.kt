package dev.solvit.plugins

import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

/** Package contract for offline HTML/JS plugin pages, before the Android renderer. */
class ScriptWebResourcesTest {
    private fun packageBytes(api: Int = 10, entry: String = "ui/index.html",
        resources: Map<String, String> = mapOf(
            "ui/index.html" to "<!doctype html><html><script src=\"main.js\"></script><div id=\"graph\"></div></html>",
            "ui/main.js" to "document.getElementById('graph').textContent='关系图';",
        )) = buildJsonObject {
        putJsonObject("manifest") {
            put("id", "test.web-pages"); put("name", "Web pages"); put("description", "Fixture")
            put("version", "1.0.0"); put("apiVersion", api)
            put("permissions", JsonArray(emptyList())); put("dependencies", JsonArray(emptyList()))
            put("tools", JsonArray(emptyList()))
            putJsonArray("pages") { add(buildJsonObject {
                put("id", "main"); put("title", "Memory"); put("entry", entry)
            }) }
        }
        put("source", "globalThis.solvitPlugin={pages:{main:async()=>({title:'Memory',nodes:[]})}};")
        put("resources", JsonObject(resources.mapValues { JsonPrimitive(it.value) }))
    }.toString().toByteArray(Charsets.UTF_8)

    @Test fun acceptsAnOfflineHtmlPageAndItsJavascriptInsideTheVerifiedPackage() {
        val bytes = packageBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val parsed = ScriptPackage.parse(bytes, digest)
        assertEquals("Memory", parsed.manifest.pages.single().title)
        assertEquals("ui/index.html", parsed.manifest.pages.single().entry)
        assertTrue(parsed.resources.getValue("ui/main.js").contains("关系图"))
        assertContentEquals(bytes, parsed.payload)
        val changed = bytes.toString(Charsets.UTF_8).replace("关系图", "modified").toByteArray()
        assertFails { ScriptPackage.parse(changed, digest) }
    }

    @Test fun rejectsMissingEntriesTraversalRemoteResourcesAndNonTextExecutables() {
        assertFails { ScriptPackage.parse(packageBytes(entry = "ui/missing.html")) }
        for (path in listOf("../outside.html", "/ui/index.html", "ui/../index.html", "ui/%2e%2e/index.html",
            "https://example.com/index.html", "ui\\index.html", "ui/payload.exe")) {
            assertFails(path) { ScriptPackage.parse(packageBytes(entry = path, resources = mapOf(path to "not a plugin page"))) }
        }
    }

    @Test fun rejectsWebEntriesOnOlderHostsAndHtmlEntriesWithTheWrongResourceType() {
        assertFails { ScriptPackage.parse(packageBytes(api = 9)) }
        assertFails { ScriptPackage.parse(packageBytes(entry = "ui/main.js")) }
    }

    @Test fun boundsResourceCountAndIndividualResourceBytes() {
        val many = (0..32).associate { "ui/file$it.js" to "x" } + ("ui/index.html" to "<html></html>")
        assertFails { ScriptPackage.parse(packageBytes(resources = many)) }
        assertFails { ScriptPackage.parse(packageBytes(resources = mapOf("ui/index.html" to "中".repeat(400_000)))) }
    }
}
