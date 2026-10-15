package dev.solvit.market

import dev.solvit.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalMcpConfigurationTest {
    private val profile = buildJsonObject {
        put("name", "local"); put("type", "stdio"); put("command", "python3")
        put("args", buildJsonArray { add("scripts/服务.py"); add("a b"); add("'\"") })
        put("env", buildJsonObject { put("PRIVATE_TOKEN", "never-print-me") }); put("cwd", "tools")
    }
    private fun encoded(value: JsonObject = profile) = JsonArray(listOf(value)).toString()
    @Test fun preservesLiteralArgumentsAndExplicitEnvironmentWithoutLoggingSecrets() {
        val config = LocalMcpConfiguration.select(encoded(), null)
        assertEquals(listOf("scripts/服务.py", "a b", "'\""), config.arguments)
        assertEquals(mapOf("PRIVATE_TOKEN" to "never-print-me"), config.environment)
        assertEquals("tools", config.cwd)
        assertFalse(config.toString().contains("never-print-me"))
        assertEquals(config.fingerprint, LocalMcpConfiguration.select(encoded(), "local").fingerprint)
        val changed = JsonObject(profile + ("command" to JsonPrimitive("node")))
        assertNotEquals(config.fingerprint, LocalMcpConfiguration.select(encoded(changed), "local").fingerprint)
    }
    @Test fun rejectsAmbiguousDisabledDuplicateAndWrongTransportSelections() {
        assertFails { LocalMcpConfiguration.select(JsonArray(listOf(profile, profile)).toString(), "local") }
        assertFails { LocalMcpConfiguration.select(encoded(JsonObject(profile + ("disabled" to JsonPrimitive(true)))), "local") }
        assertFails { LocalMcpConfiguration.select(encoded(JsonObject(profile + ("type" to JsonPrimitive("http")))), "local") }
        val other = JsonObject(profile + ("name" to JsonPrimitive("other")))
        assertFails { LocalMcpConfiguration.select(JsonArray(listOf(profile, other)).toString(), null) }
        assertEquals("other", LocalMcpConfiguration.select(JsonArray(listOf(profile, other)).toString(), "other").name)
    }
    @Test fun rejectsEscapingDirectoriesUnknownFieldsAndInvalidEnvironment() {
        for (cwd in listOf("/tmp", "../other", "tools/../other", "a//b", "a\\b", ".")) {
            assertFails { LocalMcpConfiguration.select(encoded(JsonObject(profile + ("cwd" to JsonPrimitive(cwd)))), "local") }
        }
        assertFails { LocalMcpConfiguration.select(encoded(JsonObject(profile + ("unreviewed" to JsonPrimitive(true)))), "local") }
        assertFails { LocalMcpConfiguration.select(encoded(JsonObject(profile + ("env" to buildJsonObject { put("BAD=KEY", "secret") }))), "local") }
    }
    @Test fun malformedPrivateConfigurationHasOnlySafeFailureMetadata() {
        for (text in listOf("{never-print-me", "never-print-me".repeat(500), "[]")) {
            val error = assertFailsWith<ToolExecutionException> { LocalMcpConfiguration.select(text, null) }
            assertEquals(ToolFailureCode.LOCAL_MCP_CONFIGURATION, error.code)
            assertFalse(error.toString().contains("never-print-me")); assertNull(error.cause)
        }
    }
}
