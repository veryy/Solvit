package dev.solvit.plugins

import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceResourcesContractTest {
    private fun source(api: Int = 31, flag: JsonElement = JsonPrimitive(false), permission: Boolean = true) = buildJsonObject {
        putJsonObject("manifest") {
            put("id", "test.resources"); put("name", "Resources"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", api)
            put("permissions", buildJsonArray { if (permission) add("workspace") }); put("dependencies", JsonArray(emptyList()))
            put("nativeServices", buildJsonArray { add("workspace.resources") })
            put("tools", buildJsonArray { add(buildJsonObject {
                put("name", "list"); put("description", "List without workspace"); put("requiresWorkspace", flag)
                put("inputSchema", buildJsonObject { put("type", "object") })
            }) })
        }
        put("source", "globalThis.solvitPlugin={tools:{list:async()=>({ok:true})}};")
    }.toString().toByteArray()
    @Test fun visibilityFlagIsExplicitBooleanAndNeverGrantsANativeServiceOnAnOlderHost() {
        assertFails { ScriptPackage.parse(source(api = 30)) }
        assertFails { ScriptPackage.parse(source(flag = JsonPrimitive("false"))) }
        assertFails { ScriptPackage.parse(source(permission = false)) }
        assertEquals(false, ScriptPackage.parse(source()).manifest.tools.single().requiresWorkspace)
        assertEquals(true, ScriptPackage.parse(source(flag = JsonPrimitive(true))).manifest.tools.single().requiresWorkspace)
    }
}
