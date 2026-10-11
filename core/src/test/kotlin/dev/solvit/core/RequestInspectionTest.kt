package dev.solvit.core

import kotlin.test.*

class RequestInspectionTest {
    @Test fun inspectionRetainsRequestMetadataWithoutCredentialsOrContent() {
        val secret = "DO_NOT_EXPOSE"
        val inspection = RequestInspectionService()
        inspection.record(ModelRequest(ModelConfig(apiKey = secret, systemPrompt = secret),
            listOf(ModelMessage(Role.SYSTEM, secret), ModelMessage(Role.USER, secret)),
            agentRole = AgentRole("review", "Reviewer", secret), sessionId = "session",
            promptSources = listOf(PromptSourceInfo("identity", "review", secret.length))))
        val info = inspection.latest.value!!
        assertEquals("Reviewer", info.roleName)
        assertEquals("session", info.sessionId)
        assertEquals(2, info.messageCount)
        assertEquals(secret.length, info.systemCharacters)
        assertFalse(info.toString().contains(secret))
        assertTrue(info.tools.isEmpty())
    }
}
