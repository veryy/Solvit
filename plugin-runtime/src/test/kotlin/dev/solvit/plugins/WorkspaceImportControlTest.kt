package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Exercise the host boundary before the API22 package/catalog rollout. */
class WorkspaceImportControlTest {
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
}
