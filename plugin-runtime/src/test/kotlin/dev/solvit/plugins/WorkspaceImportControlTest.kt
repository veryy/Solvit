package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Exercise the host boundary before the API22 package/catalog rollout. */
class WorkspaceImportControlTest {
    @Test fun chatAttachmentsRequireApi24AndDeclaredFilesBeforeListingOrConsumingTheDisplayedSelection():Unit=runBlocking {
        var reads=0;var selections=0
        val workspace=object:PageWorkspaceAccess {
            override suspend fun query(operation:String,argumentsJson:String,authorize:suspend()->Unit):String {
                authorize();assertEquals("chatAttachments",operation);reads++;return "{}"
            }
            override suspend fun perform(control:PageWorkspaceControl,authorize:suspend()->Unit):String {
                authorize();assertEquals("selectChatAttachment",control.operation);selections++;return "{}"
            }
        }
        val approved=control("selectChatAttachment",buildJsonObject {
            put("messageId","source");put("attachmentId",java.util.UUID.randomUUID().toString());put("sha256","a".repeat(64))
        })
        for((api,declared)in listOf(22 to true,23 to true,24 to false)) {
            val context=ScriptPageExecutionContext("test.chat.import",{},approved,workspace=workspace)
            assertFails { context.queryWorkspace("chatAttachments",buildJsonObject { put("offset",0) },api,declared) }
            assertFails { context.performWorkspace(api,filesDeclared=declared) }
        }
        assertEquals(0,reads);assertEquals(0,selections)
        val context=ScriptPageExecutionContext("test.chat.import",{},approved,workspace=workspace)
        context.queryWorkspace("chatAttachments",buildJsonObject { put("offset",0) },24,true)
        context.performWorkspace(24,filesDeclared=true)
        assertFails { context.performWorkspace(24,filesDeclared=true) }
        assertEquals(1,reads);assertEquals(1,selections)
        assertFails { ScriptPageHostAction.workspace(control("selectChatAttachment",buildJsonObject {
            put("messageId","source");put("attachmentId",java.util.UUID.randomUUID().toString());put("sha256","a".repeat(64));put("uri","file:///private")
        })) }
    }
    @Test fun pdfQueryRejectsOldApisOrMissingServiceBeforeCallingTheNativePage():Unit=runBlocking {
        var calls=0
        val workspace=object:PageWorkspaceAccess {
            override suspend fun query(operation:String,argumentsJson:String,authorize:suspend()->Unit):String {
                authorize();assertEquals("importPdf",operation);calls++;return "{}"
            }
            override suspend fun perform(control:PageWorkspaceControl,authorize:suspend()->Unit):String=error("Read only")
        }
        val context=ScriptPageExecutionContext("test.pdf.page",{},workspace=workspace)
        for((api,declared)in listOf(21 to true,22 to true,23 to false)) {
            assertFails { context.queryWorkspace("importPdf",buildJsonObject {},api,declared) }
        }
        assertEquals(0,calls)
        assertEquals("{}",context.queryWorkspace("importPdf",buildJsonObject {},23,true));assertEquals(1,calls)
    }
    private fun pkg(api: Int = 22, declared: Boolean = true, workspace: Boolean = true) = ScriptPackage.parse(buildJsonObject {
        putJsonObject("manifest") {
            put("id", "test.import.page"); put("name", "Import"); put("description", "Fixture"); put("version", "1.0.0"); put("apiVersion", api)
            put("permissions", buildJsonArray { if (workspace) add("workspace") })
            put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
            put("nativeServices", buildJsonArray { if (declared) add("workspace.files") })
            put("pages", Json.parseToJsonElement("""[{"id":"main","title":"Import"}]"""))
        }
        put("source", """globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
          let selection=null;
          if(input.event) selection=await ctx.workspacePage.perform();
          return {title:'Import',nodes:[{id:'select',kind:'button',text:'Select',action:'select',
            hostAction:{kind:'workspace.perform',operation:'selectImport',arguments:{}}},
            {id:'metadata',kind:'text',text:JSON.stringify(selection)}]};
        }}};""")
    }.toString().toByteArray())
    private fun control(operation: String = "selectImport", args: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("kind", "workspace.perform"); put("operation", operation); put("arguments", args)
    }
    private class Workspace : PageWorkspaceAccess {
        var selected: PageBinaryDocument? = null
        var valid = true
        override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String {
            authorize(); check(valid); return if (operation == "importSelection") "null" else "{}"
        }
        override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String = error("Selection must not write")
        override suspend fun stageImport(document: PageBinaryDocument, authorize: suspend () -> Unit): String {
            authorize(); check(valid); selected = document; return "{\"name\":\"ramp.bin\"}"
        }
        override suspend fun close() { selected?.close(); selected = null }
    }
    private fun picker(select: suspend () -> PageBinaryDocument?) = object : PageDocumentAccess {
        override suspend fun selectFile(authorize: suspend () -> Unit): PageBinaryDocument? { authorize(); return select() }
        override suspend fun open(control: PageDocumentControl.Open, authorize: suspend () -> Unit): PageTextDocument? = error("Not text")
        override suspend fun save(control: PageDocumentControl.Save, text: String, authorize: suspend () -> Unit): Boolean = error("Not save")
    }

    @Test fun selectionNeedsExplicitApiServiceAndAnEmptyDisplayedControl(): Unit = runBlocking {
        var opens = 0
        val workspace = Workspace()
        for ((api, declared) in listOf(21 to true, 22 to false)) {
            val page = ScriptPageExecutionContext("test.import", {}, control(), picker { opens++; null }, workspace)
            assertFails { page.performWorkspace(api, filesDeclared = declared) }
            assertFails { page.queryWorkspace("importSelection", buildJsonObject {}, api, declared) }
        }
        assertEquals(0, opens)
        assertFails { ScriptPageHostAction.workspace(control(args = buildJsonObject { put("uri", "file:///private") })) }
        workspace.valid = false
        val unbound = ScriptPageExecutionContext("test.import", {}, control(), picker { opens++; null }, workspace)
        assertFails { unbound.performWorkspace(22, filesDeclared = true) }
        assertEquals(0, opens)
    }

    @Test fun confirmedSelectionTransfersOriginalBytesOnceWithoutWriting(): Unit = runBlocking {
        val bytes = ByteArray(300_000) { it.toByte() }
        val selected = PageBinaryDocument("ramp.bin", "application/octet-stream", bytes)
        val workspace = Workspace()
        var opens = 0
        val page = ScriptPageExecutionContext("test.import", {}, control(), picker { opens++; selected }, workspace)
        assertEquals("{\"name\":\"ramp.bin\"}", page.performWorkspace(22, filesDeclared = true))
        assertSame(selected, workspace.selected)
        assertContentEquals(bytes, workspace.selected!!.readBytes())
        assertFails { page.performWorkspace(22, filesDeclared = true) }
        assertEquals(1, opens)
        workspace.close()
        assertFails { selected.readBytes() }
    }

    @Test fun latePickerResultAfterRevocationIsClosedAndNeverStaged(): Unit = runBlocking {
        val selected = PageBinaryDocument("ramp.bin", "application/octet-stream", byteArrayOf(0, -1, 65))
        val workspace = Workspace()
        var allowed = true
        val page = ScriptPageExecutionContext("test.import", { check(allowed) }, control(), picker {
            allowed = false; selected
        }, workspace)
        assertFails { page.performWorkspace(22, filesDeclared = true) }
        assertNull(workspace.selected)
        assertFails { selected.readBytes() }
    }

    @Test fun pickerCancellationKeepsThePreviousPageSelection(): Unit = runBlocking {
        val old = PageBinaryDocument("old.bin", "application/octet-stream", byteArrayOf(42))
        val workspace = Workspace().also { it.selected = old }
        val page = ScriptPageExecutionContext("test.import", {}, control(), picker { null }, workspace)
        assertEquals("null", page.performWorkspace(22, filesDeclared = true))
        assertContentEquals(byteArrayOf(42), old.readBytes())
        workspace.close()
    }

    @Test fun actualQuickJsUsesTheConfirmedPickerAndClosesItsWorkspaceOwner(): Unit = runBlocking {
        assertFails { pkg(api = 21) }; assertFails { pkg(workspace = false) }
        val selected = PageBinaryDocument("ramp.bin", "application/octet-stream", byteArrayOf(0, -1, 42))
        val workspace = Workspace()
        var opens = 0
        for (source in listOf(pkg(declared = false), pkg(api = 21, declared = false), pkg())) {
            val page = ScriptPageSession(source, "main", "en", ScriptHost { _, _, _ -> error("No arbitrary host calls") }) {}
            page.attachWorkspace(workspace); page.attachDocuments(picker { opens++; selected })
            try {
                val view = page.open()
                assertFails { page.dispatch(view, "select") }; assertEquals(0, opens)
                if (source.manifest.apiVersion == 22 && "workspace.files" in source.manifest.nativeServices) {
                    val result = page.dispatch(view, "select", confirmed = true)
                    assertTrue(result.toJson().contains("ramp.bin")); assertEquals(1, opens)
                    assertContentEquals(byteArrayOf(0, -1, 42), workspace.selected!!.readBytes())
                } else {
                    assertFails { page.dispatch(view, "select", confirmed = true) }; assertEquals(0, opens)
                }
            } finally { page.close() }
        }
        assertFails { selected.readBytes() }
    }
}
