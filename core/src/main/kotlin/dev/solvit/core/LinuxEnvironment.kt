package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow

enum class ShellRuntime { ANDROID, UBUNTU }
enum class LinuxSoftware { PYTHON, NODE, JAVA }
data class LinuxEnvironmentState(val supported: Boolean, val installed: Boolean, val stage: String,
    val bytes: Long = 0, val total: Long? = null, val output: String = "", val failure: String? = null)
/** Native host contract, never a script supplied executable, environment, or private path. */
data class HostProcessPlan(val arguments: List<String>, val environment: Map<String, String?>)
interface LinuxEnvironmentService {
    val state: StateFlow<LinuxEnvironmentState>
    suspend fun install()
    suspend fun installSoftware(software: LinuxSoftware)
    suspend fun cancel()
    fun command(workspaceDirectory: String, command: String, cwd: String = workspaceDirectory): HostProcessPlan
    fun terminal(workspaceDirectory: String): HostProcessPlan
}
val LinuxEnvironmentServiceKey = ServiceKey("environment.linux", LinuxEnvironmentService::class)
