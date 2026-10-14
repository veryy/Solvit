package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.workspace.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkspaceImportOperationTest {
    private class Fixture(val pauseStage:String="WRITING") {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val native=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,
            importPublisher=WorkspaceImportPublisher { source,destination -> Files.move(source.toPath(),destination.toPath());Unit })
        val reached=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var active="import-${java.util.UUID.randomUUID()}"
        lateinit var bridge:WorkspacePageBridge
        lateinit var root:File
        val bytes=ByteArray(300_000) { (it%251).toByte() }
        suspend fun setup():JsonObject {
            val workspace=native.forChat(active);root=File(native.location(workspace.id))
            val service=object:WorkspaceService by native {
                override suspend fun importFile(workspaceId:String,path:String,bytes:ByteArray,expectedSha256:String):WorkspaceImportedFile {
                    val monitor=currentCoroutineContext()[WorkspaceImportMonitor]
                    return withContext(WorkspaceImportMonitor { progress ->
                        monitor?.update?.invoke(progress)
                        if(progress.stage==pauseStage && !reached.isCompleted) { reached.complete(Unit);release.await() }
                    }) { native.importFile(workspaceId,path,bytes,expectedSha256) }
                }
            }
            bridge=WorkspacePageBridge(service,null,active,activeChat={active})
            val selection=Json.parseToJsonElement(bridge.stageImport(PageBinaryDocument("code.bin","application/octet-stream",bytes)) {}).jsonObject
            val args=buildJsonObject { put("id",selection.getValue("id"));put("sha256",selection.getValue("sha256"));put("path","code.bin") }
            val review=Json.parseToJsonElement(bridge.query("reviewImport",args.toString()) {}).jsonObject
            return JsonObject(args+mapOf("reviewId" to review.getValue("reviewId")))
        }
        suspend fun start(args:JsonObject):String=Json.parseToJsonElement(bridge.perform(PageWorkspaceControl("startImport",args.toString())) {}).jsonObject.getValue("operationId").jsonPrimitive.content
        fun idArgs(id:String)=buildJsonObject { put("operationId",id) }.toString()
        suspend fun status(id:String)=Json.parseToJsonElement(bridge.query("importOperation",idArgs(id)) {}).jsonObject
        suspend fun cancel(id:String)=bridge.perform(PageWorkspaceControl("cancelImport",idArgs(id))) {}
        suspend fun finished(id:String):JsonObject=withTimeout(5000) {
            var value=status(id)
            while(value.getValue("status").jsonPrimitive.content in setOf("RUNNING","CANCELLING")){yield();value=status(id)}
            value
        }
        suspend fun close(){release.complete(Unit);bridge.close();native.close();scope.cancel()}
    }
    @Test fun progressAndCancelRemainResponsiveWhileNativeImportOwnsTheWorkspaceLock()=runBlocking<Unit> {
        val f=Fixture()
        try {
            val args=f.setup();val id=f.start(args);withTimeout(5000){f.reached.await()}
            val progress=withTimeout(1000){f.status(id)}
            assertEquals(65536,progress.getValue("progress").jsonObject.getValue("completed").jsonPrimitive.int)
            assertFails { f.start(args) };assertFails { f.status("another-page-operation") }
            withTimeout(1000){f.cancel(id)}
            assertEquals("CANCELLING",f.status(id).getValue("status").jsonPrimitive.content)
            f.release.complete(Unit)
            assertEquals("CANCELLED",f.finished(id).getValue("status").jsonPrimitive.content)
            assertTrue(f.root.listFiles()!!.isEmpty())
            assertNotEquals(JsonNull,Json.parseToJsonElement(f.bridge.query("importSelection","{}") {}))
            // Old review was consumed. A new review is required to retry.
            val retry=f.start(args);assertEquals("FAILED",f.finished(retry).getValue("status").jsonPrimitive.content)
        }finally{f.close()}
    }
    @Test fun completedOperationRetainsNativeResultAndOriginalBytes()=runBlocking<Unit> {
        val f=Fixture()
        try {
            val id=f.start(f.setup());withTimeout(5000){f.reached.await()};f.release.complete(Unit)
            val result=f.finished(id);assertEquals("SUCCEEDED",result.getValue("status").jsonPrimitive.content)
            assertEquals("code.bin",result.getValue("result").jsonObject.getValue("lastImport").jsonObject.getValue("path").jsonPrimitive.content)
            assertContentEquals(f.bytes,File(f.root,"code.bin").readBytes())
            assertEquals(JsonNull,Json.parseToJsonElement(f.bridge.query("importSelection","{}") {}))
        }finally{f.close()}
    }
    @Test fun closingPageInterruptsActiveImportBeforeReleasingItsSelectedDocument()=runBlocking<Unit> {
        val f=Fixture()
        try {
            f.start(f.setup());withTimeout(5000){f.reached.await()}
            withTimeout(1000){f.bridge.close()}
            assertTrue(f.root.listFiles()!!.isEmpty())
            assertFails { f.bridge.query("snapshot","{}") {} }
        }finally{f.close()}
    }
    @Test fun conversationChangeRevokesQueriesAndCommitEvenWithAnUnchangedWorkspace()=runBlocking<Unit> {
        val f=Fixture()
        try {
            val id=f.start(f.setup());withTimeout(5000){f.reached.await()}
            f.active="other-chat";assertFails { f.status(id) };assertFails { f.cancel(id) }
            f.release.complete(Unit)
            // Restoring selection lets us inspect the terminal error; the original review is not retried.
            withTimeout(5000){while(f.root.listFiles()!!.any { it.name.startsWith(".solvit-import-") })yield()}
            assertFalse(File(f.root,"code.bin").exists())
        }finally{f.close()}
    }
}
