package dev.solvit.core

import kotlin.test.*

class CompletedChatTurnTest {
    private val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))
    private val turn = CompletedChatTurn("a", "s", "u", "a", "coder", "Coder", setOf("dev.solvit.memory"),
        "primary", "fixture", "model", 1, listOf(target))

    @Test fun exactTargetsMustRespectCapturedRoleScopeAndPackageIdentity() {
        turn.validate()
        assertTrue(turn.allows("dev.solvit.memory"))
        assertFalse(turn.allows("dev.solvit.notes"))
        assertFailsWith<IllegalArgumentException> { turn.copy(allowedPluginIds = emptySet()).validate() }
        assertFailsWith<IllegalArgumentException> { turn.copy(targets = listOf(target, target)).validate() }
        assertFailsWith<IllegalArgumentException> { turn.copy(targets = listOf(target.copy(digest = "not-a-digest"))).validate() }
        assertFailsWith<IllegalArgumentException> { turn.copy(roleId = null).validate() }
    }

    @Test fun noOptInMeansNoTargetsIncludingLegacyUnboundRoles() {
        val unbound = turn.copy(roleId = null, roleName = null, allowedPluginIds = null, targets = emptyList())
        unbound.validate()
        assertTrue(unbound.targets.isEmpty())
        assertFailsWith<IllegalArgumentException> { turn.copy(userMessageId = "a").validate() }
        assertFailsWith<IllegalArgumentException> { turn.copy(requestedModel = "").validate() }
    }
}
