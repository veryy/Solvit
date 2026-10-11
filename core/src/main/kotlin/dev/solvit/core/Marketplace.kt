package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow

data class MarketEntry(
    val id: String, val name: String, val description: String, val version: String,
    val permissions: Set<String>, val dependencies: Set<String>,
    val sha256: String, val bytes: Long, val downloadPath: String,
)
data class InstalledExtension(
    val id: String, val name: String, val version: String, val permissions: Set<String>,
    val enabled: Boolean, val tools: List<ToolDescriptor>, val failure: String? = null,
)

interface MarketplaceService {
    val installed: StateFlow<List<InstalledExtension>>
    suspend fun catalog(): List<MarketEntry>
    suspend fun install(entry: MarketEntry)
    suspend fun enable(id: String, grantedPermissions: Set<String>)
    suspend fun disable(id: String)
    suspend fun uninstall(id: String)
    suspend fun runTool(name: String, argumentsJson: String, authorization: ToolAuthorization): String
}

val MarketplaceServiceKey = ServiceKey("plugins.marketplace", MarketplaceService::class)
