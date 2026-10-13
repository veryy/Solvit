package dev.solvit.core

/** Live HTML page capability, never injected into model tools or QuickJS hooks. */
interface PageTerminalAccess {
    suspend fun request(operation: String, argumentsJson: String,
        confirmed: Boolean = false, authorize: suspend () -> Unit): String
}
