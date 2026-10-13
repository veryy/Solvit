package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuiltinTerminalUpgradeTest {
    private suspend fun fixture(block:suspend (AndroidMarketplace,File)->Unit) {
        val app=RuntimeEnvironment.getApplication()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val workspace=AndroidWorkspaces(app,scope)
        val manager=PluginManager(scope=scope)
        val client=OkHttpClient()
        try {
            manager.load(ToolsPlugin())
            manager.load(object:SolvitPlugin {
                override val id="test.terminal-services"
                override val provides=setOf(WorkspaceServiceKey,TerminalServiceKey,SystemExecutionServiceKey)
                override suspend fun enable(context:PluginContext) {
                    context.register(WorkspaceServiceKey,workspace);context.register(SystemExecutionServiceKey,workspace.execution)
                    context.register(TerminalServiceKey,object:TerminalService {
                        override val sessions=MutableStateFlow<List<TerminalSession>>(emptyList())
                        override suspend fun open(workspaceId:String,shellId:String):TerminalSession=error("Upgrade must not start processes")
                        override suspend fun status(workspaceId:String,id:String):TerminalSession=error("Unused")
                        override suspend fun input(workspaceId:String,id:String,text:String):TerminalSession=error("Unused")
                        override suspend fun interrupt(workspaceId:String,id:String):TerminalSession=error("Unused")
                        override suspend fun close(workspaceId:String,id:String) { error("Unused") }
                        override suspend fun cancelOwner(owner:String) {}
                    })
                }
            });manager.enableAll()
            val file=File(app.filesDir,"extensions/dev.solvit.terminal.solvit.json");file.parentFile!!.mkdirs()
            val market=AndroidMarketplace(app,manager,client,tools=manager.registry.require(Services.Tools))
            block(market,file)
        } finally { manager.shutdown();workspace.close();scope.cancel();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
    }
    @Test fun originalTerminalUpgradesToHtmlWithoutLosingActivationAndGrants()=runBlocking<Unit> {
        verifyTerminalUpgrade("terminal-1.0.0.solvit.json")
    }
    @Test fun previousHtmlTerminalUpgradesWithoutLosingActivationAndGrants()=runBlocking<Unit> {
        verifyTerminalUpgrade("terminal-1.1.0.solvit.json")
    }
    @Test fun compactTerminalUpgradesWithoutLosingActivationAndGrants()=runBlocking<Unit> {
        verifyTerminalUpgrade("terminal-1.1.1.solvit.json")
    }
    private suspend fun verifyTerminalUpgrade(resource:String) {
        fixture { market,file ->
            file.writeBytes(javaClass.getResourceAsStream("/$resource")!!.use { it.readBytes() })
            val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("extension_permissions",0)
            prefs.edit().putBoolean("seeded.dev.solvit.terminal.solvit.json",true).putBoolean("enabled.dev.solvit.terminal",true)
                .putStringSet("grants.dev.solvit.terminal",setOf("workspace","shell")).commit()
            market.restore()
            val installed=market.installed.value.single { it.id=="dev.solvit.terminal" }
            assertEquals("1.1.2",installed.version);assertTrue(installed.enabled)
            assertEquals(setOf("workspace","shell"),prefs.getStringSet("grants.dev.solvit.terminal",emptySet()))
            val page=market.openPage(installed.id,"main","zh")
            try { page.open();assertEquals("ui/index.html",page.document()!!.entry);assertTrue(page.resource("ui/xterm.js").content.isNotEmpty()) }
            finally { page.close() }
        }
    }
    @Test fun modifiedTerminalIsKeptAndUninstalledSeedIsNotResurrected()=runBlocking<Unit> {
        fixture { market,file ->
            val original=javaClass.getResourceAsStream("/terminal-1.0.0.solvit.json")!!.use { it.readBytes() }
            val modified=original+"\n".toByteArray();file.writeBytes(modified)
            market.restore();assertContentEquals(modified,file.readBytes())
            assertEquals("1.0.0",market.installed.value.single { it.id=="dev.solvit.terminal" }.version)
            market.uninstall("dev.solvit.terminal");market.restore()
            assertFalse(file.exists());assertTrue(market.installed.value.none { it.id=="dev.solvit.terminal" })
        }
    }
}
