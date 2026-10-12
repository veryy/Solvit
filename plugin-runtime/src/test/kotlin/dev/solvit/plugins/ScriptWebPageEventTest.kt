package dev.solvit.plugins

import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class ScriptWebPageEventTest {
    private val view = ScriptPageView.parse("""{"title":"Memory","nodes":[
        {"id":"text","kind":"field","label":"Text","value":"old"},
        {"id":"save","kind":"button","text":"Save","action":"store"},
        {"id":"delete","kind":"button","text":"Delete","action":"delete","confirm":"Delete?"}]}""")
    private fun input(node: String = "save", token: String = "current", fields: String = "{\"text\":\"new\"}") =
        """{"type":"event","token":"$token","nodeId":"$node","fields":$fields}"""

    @Test fun resolvesCurrentDeclaredActionAndPreservesTheHtmlFormValue() {
        val event = ScriptWebPageEvent.parse(view, "current", input())
        assertEquals(JsonPrimitive("store"), event.node["action"])
        assertEquals("new", event.fields["text"])
    }
    @Test fun rejectsOldViewsInventedNodesFieldsAndCallerSuppliedAuthority() {
        assertFails { ScriptWebPageEvent.parse(view, "current", input(token = "old")) }
        assertFails { ScriptWebPageEvent.parse(view, "current", input(node = "shell")) }
        assertFails { ScriptWebPageEvent.parse(view, "current", input(fields = "{\"other-plugin\":\"x\"}")) }
        for (injected in listOf("\"confirmed\":true", "\"action\":\"shell\"", "\"pluginId\":\"other.plugin\"")) {
            assertFails { ScriptWebPageEvent.parse(view, "current", input().dropLast(1) + ",$injected}") }
        }
    }
    @Test fun browserDeleteRequestStillRequiresTheSessionHumanConfirmation() {
        val event = ScriptWebPageEvent.parse(view, "current", input(node = "delete", fields = "{}"))
        assertEquals(JsonPrimitive("Delete?"), event.node["confirm"])
        assertFails { view.event("delete", fields = event.fields) }
        assertEquals(JsonPrimitive("delete"), view.event("delete", fields = event.fields, confirmed = true)["action"])
    }
}
