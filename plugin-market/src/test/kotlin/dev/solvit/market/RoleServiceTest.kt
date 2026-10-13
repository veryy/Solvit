package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoleServiceTest {
    @Before fun reset() { RuntimeEnvironment.getApplication().getSharedPreferences("agent_roles", 0).edit().clear().commit() }
    @Test fun bindingsPersistAcrossReloadAndDeletedRoleDoesNotWidenAccess() = runBlocking<Unit> {
        val ctx = RuntimeEnvironment.getApplication()
        val roles = AndroidRoleService(ctx)
        val role = AgentRole("coding", "开发", "Review carefully", setOf("dev.solvit.skills"), setOf("code-review"))
        roles.save(role); roles.select("session", role.id)
        val reloaded = AndroidRoleService(ctx)
        assertEquals(role, reloaded.roles().first().single())
        assertEquals(role, reloaded.selected("session"))
        reloaded.delete(role.id)
        assertFailsWith<IllegalStateException> { reloaded.selected("session") }
        reloaded.select("session", null)
        assertNull(reloaded.selected("session"))
    }
    @Test fun invalidRoleAndUnknownSelectionLeavePersistenceIntact() = runBlocking<Unit> {
        val roles = AndroidRoleService(RuntimeEnvironment.getApplication())
        roles.save(AgentRole("valid", "Valid"))
        assertFailsWith<IllegalArgumentException> { roles.save(AgentRole("bad", "", pluginIds = setOf("invalid"))) }
        assertFailsWith<IllegalArgumentException> { roles.select("session", "missing") }
        assertEquals("valid", roles.roles().first().single().id)
        assertNull(roles.selected("session"))
    }
    @Test fun explicitNewChatDefaultSurvivesReloadAndDoesNotFollowAnOlderChatsBinding() = runBlocking<Unit> {
        val ctx=RuntimeEnvironment.getApplication();val roles=AndroidRoleService(ctx)
        val coder=AgentRole("coder","代码助手","You are the code assistant")
        val writer=AgentRole("writer","写作助手","You are the writing assistant")
        roles.save(coder);roles.save(writer);roles.select("old",writer.id)
        roles.selectForNewChats("current",coder.id)
        val reloaded=AndroidRoleService(ctx)
        assertEquals(coder,reloaded.forNewChat(null))
        assertEquals(coder,reloaded.forNewChat("old"))
        assertEquals(writer,reloaded.selected("old"))
        reloaded.selectForNewChats("current",null)
        assertNull(AndroidRoleService(ctx).forNewChat("old"))
    }
    @Test fun deletedDefaultDoesNotSilentlyGrantUnrestrictedAccessAndLegacyBindingStillInherits() = runBlocking<Unit> {
        val roles=AndroidRoleService(RuntimeEnvironment.getApplication())
        val role=AgentRole("limited","有限角色",pluginIds=setOf("dev.solvit.clock"))
        roles.save(role);roles.select("legacy",role.id)
        assertEquals(role,roles.forNewChat("legacy"))
        roles.selectForNewChats("current",role.id);roles.delete(role.id)
        assertFailsWith<IllegalStateException> { roles.forNewChat(null) }
        roles.bindNewChat("recoverable",null)
        assertFailsWith<IllegalStateException> { roles.selected("recoverable") }
        roles.selectForNewChats("recoverable",null);assertNull(roles.selected("recoverable"))
        roles.selectForNewChats("current",null);assertNull(roles.forNewChat(null))
    }

}
