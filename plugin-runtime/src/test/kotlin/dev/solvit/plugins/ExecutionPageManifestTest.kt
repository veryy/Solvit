package dev.solvit.plugins

import kotlinx.serialization.json.*
import kotlin.test.*

class ExecutionPageManifestTest {
    private fun manifest(api: Int = 17, context: String = "execution", permission: String = "workspace") =
        ScriptPackage.parseManifest(Json.parseToJsonElement("""{"id":"test.history","name":"History","description":"fixture",
          "version":"1.0.0","apiVersion":$api,"permissions":["$permission"],"dependencies":[],"tools":[],
          "pages":[{"id":"changes","title":"Changes","entry":"review/index.html","context":"$context"}]}""").jsonObject)
    @Test fun executionPagesRequireNewApiAndWorkspaceCapabilityAndKeepManagementAsDefault() {
        assertEquals("execution", manifest().pages.single().context)
        assertFails { manifest(api = 16) }
        assertFails { manifest(context = "foreign") }
        assertFails { manifest(permission = "network") }
        assertEquals("management", manifest(context = "management").pages.single().context)
    }
}
