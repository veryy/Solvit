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
}
