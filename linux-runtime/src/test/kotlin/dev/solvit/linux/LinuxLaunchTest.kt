package dev.solvit.linux

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LinuxLaunchTest {
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
