package dev.solvit.linux

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LinuxLaunchTest {
    private fun processFixture(action: (LinuxLaunch, File, File, File) -> Unit) {
        val parent = Files.createTempDirectory("linux stdio launch").toFile()
        try {
            val root = File(parent, "root").apply { resolve("usr/bin").mkdirs(); resolve("usr/bin/bash").writeText("fixture") }
            val native = File(parent, "native").apply { mkdir(); resolve("libsolvit_proot.so").writeText("fixture"); resolve("libsolvit_loader.so").writeText("fixture") }
            val workspace = File(parent, "shared workspace").apply { mkdir() }
            action(LinuxLaunch(native, File(parent, "temp")), root, workspace, parent)
        } finally { parent.deleteRecursively() }
    }

    @Test fun stdioUsesLiteralArgvAndExplicitGuestEnvironmentInBoundWorkspace() = processFixture { launch, root, workspace, _ ->
        val nested = File(workspace, "子目录 🌌").apply { mkdir() }
        val argument = "quoted ' \" ${'$'}(do-not-run);\\n"
        val plan = launch.process(root, workspace, "/workspace/.venv/bin/python", listOf("server.py", argument),
            mapOf("MCP_TEST_TOKEN" to "explicit-only", "LANG" to "ja_JP.UTF-8"), nested)
        assertEquals(listOf("/workspace/.venv/bin/python", "server.py", argument), plan.arguments.takeLast(3))
        assertFalse("-c" in plan.arguments)
        assertEquals("/workspace/子目录 🌌", plan.arguments[plan.arguments.indexOf("-w") + 1])
        assertTrue("MCP_TEST_TOKEN=explicit-only" in plan.arguments)
        assertTrue("LANG=ja_JP.UTF-8" in plan.arguments)
        assertFalse("LANG=C.UTF-8" in plan.arguments)
        assertFalse(plan.environment.containsKey("MCP_TEST_TOKEN"))
        assertEquals(1, plan.arguments.count { it.startsWith("LANG=") })
    }

    @Test fun stdioRejectsEnvOptionConfusionOversizedInputsAndEscapingCwd() = processFixture { launch, root, workspace, parent ->
        for (executable in listOf("", "-S", "KEY=value", "node\u0000tail")) {
            assertFailsWith<IllegalArgumentException> { launch.process(root, workspace, executable, emptyList()) }
        }
        assertFailsWith<IllegalArgumentException> { launch.process(root, workspace, "node", listOf("x".repeat(16_001))) }
        assertFailsWith<IllegalArgumentException> { launch.process(root, workspace, "node", List(65) { "x" }) }
        assertFailsWith<IllegalArgumentException> { launch.process(root, workspace, "node", emptyList(), mapOf("BAD=NAME" to "value")) }
        assertFailsWith<IllegalArgumentException> { launch.process(root, workspace, "node", emptyList(), mapOf("KEY" to "bad\u0000value")) }
        assertFailsWith<IllegalArgumentException> { launch.process(root, workspace, "node", List(4) { "x".repeat(15_000) }) }
        assertFailsWith<IllegalArgumentException> { launch.process(root, workspace, "node", emptyList(), cwd = parent) }
    }

    @Test fun commandAndTerminalShareWorkspaceAndRemoveIncompatibleHostOverrides() {
        val parent = Files.createTempDirectory("linux launch").toFile()
        try {
            val root = File(parent, "root").apply { resolve("usr/bin").mkdirs(); resolve("usr/bin/bash").writeText("fixture") }
            val native = File(parent, "native").apply { mkdir(); resolve("libsolvit_proot.so").writeText("fixture"); resolve("libsolvit_loader.so").writeText("fixture") }
            val workspace = File(parent, "shared workspace").apply { mkdir() }
            val nested = File(workspace, "nested space").apply { mkdir() }
            val launch = LinuxLaunch(native, File(parent, "temp"))
            val command = launch.command(root, workspace, "python3 'name with space.py'", nested)
            val terminal = launch.terminal(root, workspace, nested)
            assertEquals(command.arguments.dropLast(3), terminal.arguments.dropLast(2))
            assertTrue(command.arguments.contains("${workspace.path}:/workspace"))
            assertEquals("/workspace/nested space", command.arguments[command.arguments.indexOf("-w") + 1])
            assertEquals("python3 'name with space.py'", command.arguments.last())
            assertEquals(command.environment, terminal.environment)
            assertTrue(command.environment.containsKey("PROOT_NO_SECCOMP"))
            assertNull(command.environment["PROOT_NO_SECCOMP"])
            assertNull(command.environment["LD_LIBRARY_PATH"])
            assertFailsWith<IllegalArgumentException> { launch.command(root, workspace, "pwd", parent) }
        } finally { parent.deleteRecursively() }
    }
}
