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
    @Test fun nativeControlsRequireConfirmationEvenWithoutPluginConfirmationText() {
        val node = """{"id":"retry","kind":"button","text":"Retry","action":"retry",
            "hostAction":{"kind":"postTurn.retry","jobId":"task-a","attempt":1}}"""
        val view = page(node)
        assertFails { view.event("retry") }
        assertEquals(JsonPrimitive("retry"), view.event("retry", confirmed = true)["action"])
        assertEquals(JsonPrimitive("task-a"), view.hostAction("retry")!!["jobId"])
        assertFails { page(node.replace("\"attempt\":1", "\"attempt\":\"1\"")) }
        assertFails { page(node.replace("\"attempt\":1", "\"attempt\":3")) }
        assertFails { page(node.replace("postTurn.retry", "shell.execute")) }
        assertFails { page(node.replace("\"attempt\":1", "\"attempt\":1,\"pluginId\":\"other.plugin\"")) }
        assertFails { page("""{"id":"d","kind":"button","text":"Delete","action":"delete",
            "hostAction":{"kind":"postTurn.delete","jobId":"task-a","attempt":1,"status":"RUNNING"}}""") }
        assertFails { page("""{"id":"s","kind":"button","text":"Stop","action":"stop",
            "hostAction":{"kind":"postTurn.stop","jobId":"task-a","attempt":0}}""") }
    }
    @Test fun fixedFooterActionsAreValidatedWithTheSamePageIdentity() {
        val view = ScriptPageView.parse("""{"title":"编辑","nodes":[{"id":"field","kind":"field","label":"内容","value":"x"}],
            "footer":[{"id":"save","kind":"button","text":"保存","action":"save"}]}""")
        assertEquals(1, view.footer.size)
        assertEquals(JsonPrimitive("save"), view.event("save", fields = mapOf("field" to "new"))["action"])
        assertFails { ScriptPageView.parse("""{"title":"编辑","nodes":[],"footer":[{"id":"field","kind":"field","label":"内容","value":"x"}]}""") }
    }
    @Test fun documentControlsAreDeclaredByTheDisplayedPageAndRequireHumanConfirmation() {
        val view = page("""{"id":"import","kind":"button","text":"Import","action":"choose-file",
            "hostAction":{"kind":"document.open","mimeTypes":["text/*","application/json","application/zip"],"maxBytes":524288}},
            {"id":"export","kind":"button","text":"Export","action":"save-file",
            "hostAction":{"kind":"document.save","name":"code-review.skill.json","mimeType":"application/json","maxBytes":524288}}""")
        assertFails { view.event("import") }
        assertFails { view.event("export") }
        assertEquals(JsonPrimitive("choose-file"), view.event("import", confirmed = true)["action"])
        assertEquals(JsonPrimitive("code-review.skill.json"), view.hostAction("export")!!["name"])
        assertFails { view.event("import", value = "content://invented/document", confirmed = true) }
    }
    @Test fun documentControlsRejectInventedPathsOversizedLimitsAndAmbiguousMimeTypes() {
        fun node(control: String) = """{"id":"file","kind":"button","text":"File","action":"file","hostAction":$control}"""
        for (control in listOf(
            """{"kind":"document.open","mimeTypes":[],"maxBytes":524288}""",
            """{"kind":"document.open","mimeTypes":["text/plain"],"maxBytes":524289}""",
            """{"kind":"document.open","mimeTypes":["text/plain"],"maxBytes":0}""",
            """{"kind":"document.open","mimeTypes":["text/plain"],"maxBytes":"12"}""",
            """{"kind":"document.open","mimeTypes":["text/plain; charset=utf-8"],"maxBytes":12}""",
            """{"kind":"document.open","mimeTypes":["text/plain"],"maxBytes":12,"uri":"content://other/document"}""",
            """{"kind":"document.save","name":"../secret.json","mimeType":"application/json","maxBytes":12}""",
            """{"kind":"document.save","name":"secret.json","mimeType":"application/*","maxBytes":12}""",
        )) assertFails { page(node(control)) }
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
