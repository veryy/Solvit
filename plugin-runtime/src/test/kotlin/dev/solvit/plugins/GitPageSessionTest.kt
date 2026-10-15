package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class GitPageSessionTest {
    @Test fun cloneRecoveryRequiresApi29IncludingNestedStartAndRejectsScriptSuppliedFacts() = runBlocking<Unit> {
        var reads = 0; var writes = 0
        val access = object : PageGitAccess {
            override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String {
                authorize(); reads++; return "{}"
            }
            override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String {
                authorize(); writes++; return "{}"
            }
        }
        val args = buildJsonObject { put("reviewId", "host-retained-review") }
        fun action(operation: String, nested: Boolean = false) = buildJsonObject {
            put("kind", "git.perform"); put("operation", if (nested) "startRemote" else operation)
            put("arguments", if (nested) buildJsonObject { put("operation", operation); put("arguments", args) } else args)
        }
        for (operation in listOf("resumeCloneInstall", "restartCloneDownload", "discardCloneInstall")) {
            val metadata = action(operation)
            val invocation = ScriptPageExecutionContext("test.git.page", {}, metadata, git = access)
            assertFails { invocation.performGit(28, true) }
            if (operation == "restartCloneDownload") assertFails { invocation.performGit(29, false) }
            invocation.performGit(29, operation == "restartCloneDownload")
            assertFails { invocation.performGit(29, true) }
            val forged = metadata.toMutableMap().apply { put("arguments", buildJsonObject {
                put("reviewId", "host-retained-review"); put("rootPath", "/caller-controlled")
            }) }
            assertFails { ScriptPageHostAction.git(JsonObject(forged)) }
        }
        for (operation in listOf("resumeCloneInstall", "restartCloneDownload")) {
            val invocation = ScriptPageExecutionContext("test.git.page", {}, action(operation, true), git = access)
            assertFails { invocation.performGit(28, true) }
            invocation.performGit(29, true)
        }
        val query = ScriptPageExecutionContext("test.git.page", {}, git = access)
        for (operation in listOf("cloneInstalls", "reviewCloneInstall")) {
            assertFails { query.queryGit(operation, buildJsonObject {}, 28, true) }
            query.queryGit(operation, buildJsonObject {}, 29, false)
        }
        assertEquals(5, writes); assertEquals(2, reads)
    }
    @Test fun credentialControlPassesOnlyRepositoryToNativeFormAndReturnsOnlyOutcome() = runBlocking<Unit> {
        val endpoint = "https://github.com/fixture/repository.git"
        var edited = 0
        val prompt = object : PageGitCredentialPrompt {
            override suspend fun edit(endpoint: GitRemoteEndpoint, configured: Boolean, authorize: suspend () -> Unit,
                save: suspend (String, CharArray) -> Unit, remove: suspend () -> Unit): GitCredentialOutcome {
                error("Only the attached native access may open this form")
            }
        }
        val access = object : PageGitAccess {
            override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit) = error("Not called")
            override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit) = error("Not called")
            override suspend fun editCredentials(endpoint: GitRemoteEndpoint, prompt: PageGitCredentialPrompt,
                authorize: suspend () -> Unit): GitCredentialOutcome {
                authorize(); assertEquals("https://github.com/fixture/repository.git", endpoint.url); edited++
                return GitCredentialOutcome.SAVED
            }
        }
        val metadata = buildJsonObject { put("kind", "git.credentials"); put("url", endpoint) }
        fun invocation() = ScriptPageExecutionContext("test.git.page", {}, metadata, git = access, gitCredentialPrompt = prompt)
        assertFails { invocation().editGitCredentials(19, true) }
        assertFails { invocation().editGitCredentials(20, false) }
        assertEquals(0, edited)
        val allowed = invocation()
        assertTrue(allowed.credentialInvocation)
        assertEquals(GitCredentialOutcome.SAVED, allowed.editGitCredentials(20, true))
        assertFails { allowed.editGitCredentials(20, true) }
        assertEquals(1, edited)
        assertFails { ScriptPageHostAction.gitCredentials(buildJsonObject {
            put("kind", "git.credentials"); put("url", endpoint); put("secret", "must-not-be-accepted")
        }) }
    }

    @Test fun remoteGitControlsRequireNewApiNetworkPermissionAndConsumeOnlyConfirmedAction() = runBlocking<Unit> {
        var writes = 0
        var reads = 0
        val access = object : PageGitAccess {
            override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String {
                authorize(); reads++; return "null"
            }
            override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String {
                authorize(); assertEquals("reviewPush", control.operation); writes++; return "{}"
            }
        }
        val action = Json.parseToJsonElement("""{"kind":"git.perform","operation":"reviewPush","arguments":{"url":"https://github.com/veryy/Solvit.git","branch":"main"}}""").jsonObject
        fun invocation() = ScriptPageExecutionContext("test.git.page", {}, action, git = access)
        assertFails { invocation().performGit(19, true) }
        assertFails { invocation().performGit(20, false) }
        assertEquals(0, writes)
        val approved = invocation()
        approved.performGit(20, true)
        assertFails { approved.performGit(20, true) }
        assertEquals(1, writes)
        assertFails { invocation().queryGit("transferProgress", buildJsonObject {}, 19, true) }
        assertFails { invocation().queryGit("transferProgress", buildJsonObject {}, 20, false) }
        assertEquals(0, reads)
        invocation().queryGit("transferProgress", buildJsonObject {}, 20, true)
        assertEquals(1, reads)
        assertFails { ScriptPageHostAction.git(Json.parseToJsonElement("""{"kind":"git.perform","operation":"fetch","arguments":{"url":"https://secret@github.com/veryy/Solvit.git","branch":"main"}}""").jsonObject) }
        assertFails { ScriptPageHostAction.git(Json.parseToJsonElement("""{"kind":"git.perform","operation":"push","arguments":{"reviewId":"id","remoteHead":"forged"}}""").jsonObject) }
    }

    private fun pkg(api: Int = 19, permissions: String = "\"workspace\",\"git\"",
        services: String = "\"workspace.git\"", action: String = "await ctx.gitPage.perform()") = ScriptPackage.parse("""
        {"manifest":{"id":"test.git.page","name":"Git","description":"Fixture","version":"1.0.0",
          "apiVersion":$api,"permissions":[$permissions],"nativeServices":[$services],"dependencies":[],"tools":[],
          "pages":[{"id":"main","title":"Git"}]},
          "source":${JsonPrimitive("""globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
            if(input.event){ $action } else { await ctx.gitPage.query('status',{offset:0,limit:10}); }
            return {title:'Git',nodes:[{id:'init',kind:'button',text:'Initialize',action:'initialize',
              hostAction:{kind:'git.perform',operation:'initialize',arguments:{}}}]};
          }}};""")}}
        """.toByteArray())

    @Test fun manifestSeparatesGitServiceFromShellAndRejectsOldApiOrMissingPermissions() {
        assertFails { pkg(api = 18) }
        assertFails { pkg(permissions = "\"workspace\"") }
        assertFails { pkg(permissions = "\"git\"") }
        val plugin = ScriptToolPlugin(pkg(), ScriptHost { _, _, _ -> error("Not called") })
        assertTrue(WorkspaceGitServiceKey in plugin.requires)
        assertTrue(WorkspaceServiceKey in plugin.requires)
        assertFalse(TerminalServiceKey in plugin.requires)
    }

    @Test fun actualQuickJsQueriesAndConsumesOnlyTheDisplayedConfirmedGitControl() = runBlocking<Unit> {
        var reads = 0
        var writes = 0
        var closed = false
        val access = object : PageGitAccess {
            override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String {
                authorize(); assertEquals("status", operation)
                assertEquals(JsonPrimitive(10), Json.parseToJsonElement(argumentsJson).jsonObject["limit"])
                reads++; return "{}"
            }
            override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String {
                authorize(); assertEquals("initialize", control.operation); assertEquals("{}", control.argumentsJson)
                writes++; return "{}"
            }
            override suspend fun close() { closed = true }
        }
        fun page(source: ScriptPackage = pkg()) = ScriptPageSession(source, "main", "en", ScriptHost { _, _, _ -> error("Git must use attached page capability") }) {}
        val initialPage = page()
        initialPage.attachGit(access)
        try {
            val view = initialPage.open()
            assertEquals(1, reads)
            assertFails { initialPage.dispatch(view, "init") }; assertEquals(0, writes)
            initialPage.dispatch(view, "init", confirmed = true); assertEquals(1, writes)
            assertFails { initialPage.dispatch(view, "init", confirmed = true) }; assertEquals(1, writes)
        } finally { initialPage.close() }
        assertTrue(closed)
        for (action in listOf("await ctx.gitPage.perform(); await ctx.gitPage.perform()",
            "await __solvitHostCall('gitPage.perform',JSON.stringify({operation:'commit'}))")) {
            val other = page(pkg(action = action))
            other.attachGit(access)
            try { val view = other.open(); assertFails { other.dispatch(view, "init", confirmed = true) } }
            finally { other.close() }
        }
        assertEquals(2, writes) // The first approved operation runs, a second hidden operation cannot.
        val missing = page()
        try { assertFails { missing.open() } } finally { missing.close() }
    }

    @Test fun invalidGitMetadataCannotSupplyPatchOrExecuteShell() {
        fun metadata(operation: String, args: String) = Json.parseToJsonElement("""{"kind":"git.perform","operation":"$operation","arguments":$args}""").jsonObject
        assertFails { ScriptPageHostAction.git(metadata("stage", """{"reviewId":"id","patch":"forged"}""")) }
        assertFails { ScriptPageHostAction.git(metadata("shell", """{"command":"git add ."}""")) }
        assertFails { ScriptPageHostAction.git(metadata("commit", """{"revisionId":"id","message":"","author":"A","email":"e"}""")) }
    }
}
