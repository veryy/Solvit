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

    private fun plan(root: File, workspace: File, cwd: File, guestCommand: List<String>): Plan {
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
        val args = listOf(proot.absolutePath, "-0", "--link2symlink", "-r", rootDirectory.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${base.path}:/workspace", "-w", guestCwd,
            "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive") + guestCommand
        return Plan(args, mapOf("PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to temporaryDirectory.absolutePath, "PROOT_L2S_DIR" to links.absolutePath,
            "PROOT_NO_SECCOMP" to null, "LD_LIBRARY_PATH" to null))
    }
}
