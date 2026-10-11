package dev.solvit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MarketplaceViewModel(private val service: MarketplaceService) : ViewModel() {
    val installed = service.installed
    val catalog = MutableStateFlow<List<MarketEntry>>(emptyList())
    val loading = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val output = MutableStateFlow<String?>(null)
    val configuring = MutableStateFlow<InstalledExtension?>(null)
    val configurationValues = MutableStateFlow<Map<String, String>>(emptyMap())
    fun openConfiguration(extension: InstalledExtension) = operation {
        configurationValues.value = service.configuration(extension.id)
        configuring.value = extension
    }
    fun closeConfiguration() { configuring.value = null; configurationValues.value = emptyMap() }
    fun saveConfiguration(id: String, values: Map<String, String>) = operation {
        service.configure(id, values); closeConfiguration()
    }
    init { refresh() }

    fun refresh() {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            try { catalog.value = service.catalog(); error.value = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "无法加载插件市场" }
            finally { loading.value = false }
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "插件操作失败" }
            finally { busy.value = false }
        }
    }
    fun install(entry: MarketEntry) = operation { service.install(entry) }
    fun enable(extension: InstalledExtension) = operation { service.enable(extension.id, extension.permissions) }
    fun disable(id: String) = operation { service.disable(id) }
    fun uninstall(id: String) = operation { service.uninstall(id) }
    fun run(tool: ToolDescriptor, arguments: String) = operation {
        output.value = null
        // Only invoked from the tool confirmation dialog, for this exact tool.
        val authorization = object : ToolAuthorization {
            override suspend fun authorize(descriptor: ToolDescriptor) = descriptor.name == tool.name
        }
        output.value = service.runTool(tool.name, arguments, authorization)
    }
}
