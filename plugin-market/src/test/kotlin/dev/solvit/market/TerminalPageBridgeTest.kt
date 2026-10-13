package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TerminalPageBridgeTest {
    private class Backend : TerminalService {
        override val sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
        val inputs = mutableListOf<String>()
        override suspend fun open(workspaceId: String, shellId: String): TerminalSession {
            val authority=checkNotNull(currentCoroutineContext()[TerminalPageAuthority]);authority.verify(workspaceId)
            return TerminalSession("test-terminal",authority.owner,null,workspaceId,shellId,ExecutionMode.APP,
                chatId=authority.chatId,human=true).also { sessions.value=listOf(it) }
        }
        override suspend fun status(workspaceId: String, id: String) = sessions.value.single { it.id==id }
        override suspend fun input(workspaceId: String, id: String, text: String): TerminalSession {
            checkNotNull(currentCoroutineContext()[TerminalPageAuthority]).verify(workspaceId)
            inputs+=text;return status(workspaceId,id)
        }
        override suspend fun interrupt(workspaceId: String,id: String)=status(workspaceId,id)
        override suspend fun close(workspaceId: String,id: String) { sessions.value=emptyList() }
        override suspend fun cancelOwner(owner: String) { sessions.value=emptyList() }
    }
    @Test fun unconfirmedOpenAndForeignIdsCannotExecuteAndChatSwitchRevokesInput() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val workspace=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try {
            val ws=workspace.forChat("one");workspace.bind("two",ws.id)
            val shell=workspace.state.value.shells.single { it.workspaceId==ws.id }
            val backend=Backend();var active="one";var enabled=true
            val bridge=TerminalPageBridge(workspace,backend,"one",TERMINAL_OWNER) { active }
            suspend fun request(op:String,args:String,confirmed:Boolean=false)=bridge.request(op,args,confirmed) { check(enabled) }
            assertFails { request("open","""{"shellId":"${shell.id}"}""") }
            assertTrue(backend.sessions.value.isEmpty())
            assertFails { request("input","""{"id":"test-terminal","text":"bad"}""") }
            request("open","""{"shellId":"${shell.id}"}""",true)
            request("input","""{"id":"test-terminal","text":"approved\n"}""")
            assertEquals(listOf("approved\n"),backend.inputs)
            assertEquals("one",backend.sessions.value.single().chatId)
            assertFails { request("input","""{"id":"test-terminal","text":"bad","chatId":"two"}""") }
            enabled=false
            assertFails { request("input","""{"id":"test-terminal","text":"revoked"}""") }
            enabled=true;active="two"
            assertFails { request("input","""{"id":"test-terminal","text":"foreign-chat"}""") }
            assertEquals(listOf("approved\n"),backend.inputs)
        } finally { workspace.close();scope.cancel() }
    }
}
