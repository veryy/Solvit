package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.GitPageBridge
import dev.solvit.market.WorkspacePageBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Invoked only by the external adb script. Production clone, durable intents and
 * native file/metadata moves run before a host checkpoint blocks for actual force-stop.
 * Verify starts in a different process and operates the actual packaged JS/HTML page. */
class WorkspaceCloneProcessDeathTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as SolvitApplication
    private val fixture get()=File(app.noBackupFilesDir,"clone-recovery-fixture.json")
    private val marker get()=File(app.noBackupFilesDir,"clone-recovery-ready")
    private val original="original 中文 😀\n"
    private val userText="user content must survive process death\n"
    private fun sync(file:File,text:String) { FileOutputStream(file).use { it.write(text.toByteArray());it.fd.sync() } }
    private suspend fun ready() { withTimeout(30_000){app.ready.filterNotNull().first().getOrThrow()} }
    private fun tls():SSLSocketFactory {
        val certificate=InstrumentationRegistry.getInstrumentation().context.assets.open("git-fixture.crt")
            .use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null);setCertificateEntry("fixture",certificate) }
        val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null,trust.trustManagers,null) }.socketFactory
    }
    private fun control(path:String):String {
        val connection=URL("https://localhost:19443$path").openConnection() as HttpsURLConnection
        connection.connectTimeout=3_000;connection.readTimeout=3_000
        try { return connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
    }
    private fun requests(endpoint:GitRemoteEndpoint):Int {
        val path=java.net.URI(endpoint.url).path+"/"
        return Json.parseToJsonElement(control("/control/records")).jsonArray.count {
            it.jsonObject.getValue("path").jsonPrimitive.content.startsWith(path)
        }
    }
    private fun bind(chat:String)=(app.workspaceGit as WorkspaceRemoteGitService).bindRemote(chat,{chat},{},{},{})
    private suspend fun runShell(workspace:String,shell:String,command:String):String {
        val job=app.workspaces.run(workspace,shell,command,10_000)
        val result=withTimeout(15_000){app.workspaces.jobs.first { jobs->jobs.any { it.id==job.id&&it.stage in setOf(ShellStage.COMPLETED,ShellStage.FAILED,ShellStage.CANCELLED,ShellStage.TIMED_OUT) } }.first { it.id==job.id }}
        assertEquals(ShellStage.COMPLETED,result.stage);assertEquals(0,result.exitCode)
        return result.output.joinToString(""){it.text}
    }

    @Test fun prepareActualCloneAndWaitForExternalKill()=runBlocking<Unit> {
        ready()
        val scenario=InstrumentationRegistry.getArguments().getString("recoveryScenario")!!
        require(scenario in setOf("DOWNLOADING","FILE","CONFLICT","METADATA","COMMITTED","CLEANUP"))
        check(!fixture.exists()) { "Previous clone process-death fixture was not verified" }
        val chat=app.chatStore.createSession().id
        val workspace=app.workspaces.create("Clone recovery $scenario").also { app.workspaces.bind(chat,it.id) }
        val root=File(app.workspaces.location(workspace.id)).canonicalFile
        val shell=app.workspaces.createShell(workspace.id,"Clone recovery shell")
        val endpoint=GitRemoteEndpoint.parse("https://localhost:19443/"+if(scenario=="DOWNLOADING")"clone-death-download.git" else "clone.git")
        val saved=buildJsonObject {
            put("scenario",scenario);put("chat",chat);put("workspace",workspace.id);put("shell",shell.id)
            put("url",endpoint.url);put("rootInode",android.system.Os.lstat(root.path).st_ino)
        }
        sync(fixture,saved.toString())
        val oldTls=HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(tls())
        val native=bind(chat)
        try {
            native.saveCredentials(endpoint,"fixture-user","fixture-secret".toCharArray())
            fun markAndBlock() {
                sync(fixture,JsonObject(saved+mapOf("requests" to JsonPrimitive(requests(endpoint)))).toString())
                sync(marker,scenario)
                check(CountDownLatch(1).await(60,TimeUnit.SECONDS)) { "External force-stop did not arrive" }
            }
            if(scenario=="DOWNLOADING") {
                val cloning=async(Dispatchers.IO) { native.clone(endpoint,"main") }
                withContext(Dispatchers.IO) {
                    withTimeout(15_000){while(control("/control/clone-death-started")!="true")delay(25)}
                    assertEquals("DOWNLOADING",native.cloneInstalls().entries.single().phase)
                    markAndBlock()
                }
                cloning.await()
            } else {
                val phase=when(scenario){"FILE","CONFLICT"->"FILE_PUBLISHED";"METADATA"->"METADATA_PUBLISHED";"CLEANUP"->"PRIVATE_ENTRY_REMOVED";else->"COMMITTED"}
                withContext(WorkspaceCloneMonitor { stage ->
                    if(stage==phase) {
                        if(scenario=="CONFLICT")runBlocking { runShell(workspace.id,shell.id,"printf 'user content must survive process death\\n' > from-shell.txt") }
                        markAndBlock()
                    }
                }) { native.clone(endpoint,"main") }
            }
            error("Clone checkpoint completed instead of being killed")
        } finally { native.close();HttpsURLConnection.setDefaultSSLSocketFactory(oldTls) }
    }

    @Test fun verifyFreshProcessFilesShellAndExplicitHtmlRecovery() {
        runBlocking { ready() }
        val saved=Json.parseToJsonElement(fixture.readText()).jsonObject
        val scenario=saved.getValue("scenario").jsonPrimitive.content
        val chat=saved.getValue("chat").jsonPrimitive.content
        val workspace=saved.getValue("workspace").jsonPrimitive.content
        val shell=saved.getValue("shell").jsonPrimitive.content
        val endpoint=GitRemoteEndpoint.parse(saved.getValue("url").jsonPrimitive.content)
        val root=File(runBlocking { app.workspaces.location(workspace) }).canonicalFile
        val native=bind(chat)
        val oldTls=HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(tls())
        val html=HtmlPageProbe(rule.activity);val manager=BuiltinWorkspaceManager(app,"dev.solvit.git")
        val workspaceBridge=WorkspacePageBridge(app.workspaces,null,chat)
        fun waitDom(code:String){rule.waitUntil(15_000){runCatching { html.js(code)=="true" }.getOrDefault(false)}}
        fun click(id:String){waitDom("!!document.querySelector('[data-node=$id]')&&!document.querySelector('[data-node=$id]').disabled");html.js("document.querySelector('[data-node=$id]').click()")}
        fun confirm(allow:Boolean) {
            rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty()}
            if(allow)rule.onNodeWithTag("plugin_page_confirm").performClick() else rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000){rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty()}
        }
        val published=scenario in setOf("METADATA","COMMITTED","CLEANUP")
        fun preserved() {
            assertEquals(saved.getValue("rootInode").jsonPrimitive.long,android.system.Os.lstat(root.path).st_ino)
            if(scenario=="DOWNLOADING")assertTrue(root.listFiles()!!.isEmpty())
            else if(published){assertFalse(File(root,"content.txt").exists());assertEquals(userText,File(root,"later.txt").readText());assertTrue(File(root,".git").isDirectory)}
            else {assertEquals(original,File(root,"content.txt").readText());assertFalse(File(root,".git").exists())}
            if(scenario=="CONFLICT")assertEquals(userText,File(root,"from-shell.txt").readText())
        }
        try {
            val pending=runBlocking { native.cloneInstalls().entries.single() }
            assertEquals(if(scenario=="DOWNLOADING")"DOWNLOAD_INTERRUPTED" else if(published)"PUBLISHED" else "INSTALL_INTERRUPTED",pending.status)
            if(published)runBlocking { runShell(workspace,shell,"rm content.txt && printf 'user content must survive process death\\n' > later.txt") }
            preserved()
            val before=saved.getValue("requests").jsonPrimitive.int
            assertEquals(before,requests(endpoint))
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                PluginPageScreen("Git",{manager.openPage("zh",workspaceBridge,git=GitPageBridge(app.workspaceGit,chat){chat})}){}
            } } }
            click("git_clones");click("git_clone_review_0")
            waitDom("!!document.querySelector('[data-node=git_clone_source]')")
            assertEquals(before,requests(endpoint));preserved()
            if(scenario in setOf("CONFLICT","CLEANUP")) {
                assertEquals("false",html.js("!!document.querySelector('[data-node=git_clone_resume]')"))
                click("git_clone_discard")
            } else if(scenario=="DOWNLOADING") {
                assertEquals("false",html.js("!!document.querySelector('[data-node=git_clone_resume]')"));click("git_clone_restart")
            } else click("git_clone_resume")
            click("git_clone_confirm");confirm(false)
            assertEquals(1,runBlocking { native.cloneInstalls().entries.size });preserved();assertEquals(before,requests(endpoint))
            html.screenshot("git-clone-process-death-$scenario").recycle()
            click("git_clone_confirm");confirm(true)
            if(scenario in setOf("CONFLICT","CLEANUP"))waitDom("!!document.querySelector('[data-node=git_clone_empty]')")
            else waitDom("!!document.querySelector('[data-node=git_branch]')&&!document.querySelector('[data-node=git_transfer]')")
            assertTrue(runBlocking { native.cloneInstalls().entries.isEmpty() })
            if(scenario=="DOWNLOADING") {
                assertTrue(requests(endpoint)>before);assertEquals(original,File(root,"content.txt").readText())
                assertTrue(runBlocking { native.status().entries.isEmpty() })
            } else {
                assertEquals(before,requests(endpoint))
                if(scenario=="FILE") { assertEquals(original,File(root,"content.txt").readText());assertTrue(runBlocking { native.status().entries.isEmpty() }) }
                else preserved()
            }
            assertEquals(saved.getValue("rootInode").jsonPrimitive.long,android.system.Os.lstat(root.path).st_ino)
            val listing=runBlocking { runShell(workspace,shell,"pwd && ls -a") }
            assertTrue(listing.contains(root.name));assertTrue(listing.contains(if(published)"later.txt" else "content.txt"))
            native.removeCredentials(endpoint)
            assertTrue(marker.delete());assertTrue(fixture.delete())
        } finally {
            runBlocking { native.close();workspaceBridge.close() }
            HttpsURLConnection.setDefaultSSLSocketFactory(oldTls)
        }
    }
}
