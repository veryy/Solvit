package dev.solvit.plugins

import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class ScriptPageViewTest {
    private fun page(nodes: String) = ScriptPageView.parse("""{"title":"记忆","nodes":[$nodes]}""")
    @Test fun editsResolveDisplayedActionsAndRejectStaleOrInventedFields() {
        val view = page("""{"id":"body","kind":"field","label":"内容","value":"旧记忆"},
            {"id":"save","kind":"button","text":"保存","action":"save-memory"},
            {"id":"disabled","kind":"button","text":"删除","action":"delete","enabled":false}""")
        val event = view.event("save", fields = mapOf("body" to "新记忆"))
        assertEquals(JsonPrimitive("save-memory"), event["action"])
        assertEquals(JsonPrimitive("新记忆"), event.getValue("fields").jsonObject["body"])
        assertFails { view.event("removed") }
        assertFails { view.event("disabled") }
        assertFails { view.event("save", fields = mapOf("other-plugin-data" to "x")) }
        assertFails { view.event("save", value = "shell-command") }
    }
    @Test fun choicesAndGraphSelectionsMustBelongToDisplayedData() {
        val view = page("""{"id":"filter","kind":"choice","label":"类别","value":"all","action":"filter",
            "options":[{"value":"all","label":"全部"}]},
            {"id":"map","kind":"graph","action":"details","nodes":[{"id":"fact1","label":"事实"}],"edges":[]}""")
        assertEquals(JsonPrimitive("fact1"), view.event("map", "fact1")["value"])
        assertFails { view.event("map", "deleted-fact") }
        assertFails { view.event("filter", "undeclared-category") }
    }
    @Test fun destructiveActionsRequireHostConfirmation() {
        val view = page("""{"id":"delete","kind":"button","text":"删除","action":"delete-memory","confirm":"确认删除这条记忆？"}""")
        assertFails { view.event("delete") }
        assertEquals(JsonPrimitive("delete-memory"), view.event("delete", confirmed = true)["action"])
    }
    @Test fun rejectsExecutableFieldsAmbiguousIdsAndBrokenRelations() {
        assertFails { page("""{"id":"x","kind":"webview","html":"<script/>"}""") }
        assertFails { page("""{"id":"x","kind":"text","text":"x","onClick":"eval()"}""") }
        assertFails { page("""{"id":"x","kind":"text","text":"1"},{"id":"x","kind":"text","text":"2"}""") }
        assertFails { page("""{"id":"g","kind":"graph","nodes":[],"edges":[{"from":"missing","to":"other","label":"关系"}]}""") }
        assertFails { page("""{"id":"b","kind":"button","text":"保存","action":"save","enabled":"false"}""") }
        assertFails { page("""{"id":"f","kind":"field","label":"内容","value":123}""") }
    }
    @Test fun boundsUtf8PayloadAndNativeComponentCount() {
        assertFails { page("""{"id":"text","kind":"text","text":"${"中".repeat(ScriptPageView.MAX_BYTES)}"}""") }
        val nodes = (0..ScriptPageView.MAX_NODES).joinToString(",") { """{"id":"n$it","kind":"text","text":"x"}""" }
        assertFails { page(nodes) }
        var nested = """{"id":"leaf","kind":"text","text":"x"}"""
        repeat(14) { nested = """{"id":"level$it","kind":"column","children":[$nested]}""" }
        assertFails { page(nested) }
    }
}
