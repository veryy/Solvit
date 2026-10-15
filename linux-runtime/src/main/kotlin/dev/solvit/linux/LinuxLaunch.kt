package dev.solvit.linux

import java.io.File

/** Trusted host inputs only. This is a userland runtime, not an Android permission boundary. */
class LinuxLaunch(private val nativeDirectory: File, private val temporaryDirectory: File) {
    data class Plan(val arguments: List<String>, val environment: Map<String, String?>) {
        fun processBuilder(): ProcessBuilder = ProcessBuilder(arguments).apply {
            environment.forEach { (name, value) ->
                if (value == null) environment().remove(name) else environment()[name] = value
            }
        }
    }

    fun command(root: File, workspace: File, command: String, cwd: File = workspace): Plan {
        require(command.isNotBlank() && '\u0000' !in command && command.length <= 32_768)
        return plan(root, workspace, cwd, listOf("/bin/bash", "-c", command))
    }

    fun terminal(root: File, workspace: File, cwd: File = workspace): Plan =
        plan(root, workspace, cwd, listOf("/bin/bash", "-i"))

    /** Direct argv for protocol subprocesses: never parse a user command with bash -c. */
    fun process(root: File, workspace: File, executable: String, arguments: List<String>,
        environment: Map<String, String> = emptyMap(), cwd: File = workspace): Plan {
        require(executable.isNotBlank() && !executable.startsWith('-') && '=' !in executable)
        require(arguments.size <= 64 && environment.size <= 32)
        require(environment.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) })
        val strings = listOf(executable) + arguments + environment.map { (key, value) -> "$key=$value" }
        require(strings.all { '\u0000' !in it && it.length <= 16_000 })
        require(strings.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 1 } <= 48 * 1024)
        return plan(root, workspace, cwd, listOf(executable) + arguments, environment)
    }

    private fun plan(root: File, workspace: File, cwd: File, guestCommand: List<String>,
        guestEnvironment: Map<String, String> = emptyMap()): Plan {
        val base = workspace.canonicalFile
        val directory = cwd.canonicalFile
        require(base.isDirectory && directory.isDirectory)
        require(directory == base || directory.path.startsWith(base.path + File.separator)) {
            "Linux 工作目录需在当前工作区内"
        }
        // ':' is the PRoot bind separator; never reinterpret a host path as extra bind arguments.
        require(':' !in base.path && '\u0000' !in base.path)
        val rootDirectory = root.canonicalFile
        check(rootDirectory.isDirectory && File(rootDirectory, "usr/bin/bash").isFile) { "Linux 环境未安装" }
        val proot = File(nativeDirectory, "libsolvit_proot.so")
        val loader = File(nativeDirectory, "libsolvit_loader.so")
        check(proot.isFile && loader.isFile) { "当前 APK 未提供此架构的 Linux 执行器" }
        check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs())
        val links = File(rootDirectory, ".l2s")
        check(links.isDirectory || links.mkdir())
        val relative = base.toPath().relativize(directory.toPath()).toString().replace(File.separatorChar, '/')
        val guestCwd = if (relative.isEmpty()) "/workspace" else "/workspace/$relative"
        val defaults = linkedMapOf("HOME" to "/root", "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color", "LANG" to "C.UTF-8", "DEBIAN_FRONTEND" to "noninteractive")
        val args = listOf(proot.absolutePath, "-0", "--link2symlink", "-r", rootDirectory.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${base.path}:/workspace", "-w", guestCwd,
            "/usr/bin/env", "-i") + (defaults + guestEnvironment).map { (key, value) -> "$key=$value" } + guestCommand
        return Plan(args, mapOf("PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to temporaryDirectory.absolutePath, "PROOT_L2S_DIR" to links.absolutePath,
            "PROOT_NO_SECCOMP" to null, "LD_LIBRARY_PATH" to null))
    }
}
