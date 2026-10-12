package dev.solvit.plugins

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import dev.solvit.core.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/** Carries live page authority all the way to the actual native storage write lock. */
class ScriptPageExecutionContext(val pluginId: String, private val verify: suspend () -> Unit,
    private val approvedAction: JsonObject? = null, private val documents: PageDocumentAccess? = null) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScriptPageExecutionContext>
    private val consumed = AtomicBoolean(false)
    val documentInvocation: Boolean get() = approvedAction?.get("kind")?.toString()?.trim('"')?.startsWith("document.") == true
    suspend fun ensureAuthorized() = verify()
    suspend fun consumeTaskControl(): PostTurnTaskControl {
        ensureAuthorized()
        val approved = checkNotNull(approvedAction) { "No confirmed native task control" }
        check(consumed.compareAndSet(false, true)) { "Native task control already consumed" }
        return ScriptPageHostAction.parse(approved)
    }
    suspend fun openDocument(): PageTextDocument? {
        ensureAuthorized()
        val control = ScriptPageHostAction.document(checkNotNull(approvedAction)) as? PageDocumentControl.Open
            ?: error("Displayed control does not select a document")
        val access = checkNotNull(documents) { "No page document picker" }
        check(consumed.compareAndSet(false, true)) { "Native page control already consumed" }
        return access.open(control, ::ensureAuthorized).also { ensureAuthorized() }
    }
    suspend fun saveDocument(text: String): Boolean {
        ensureAuthorized()
        val control = ScriptPageHostAction.document(checkNotNull(approvedAction)) as? PageDocumentControl.Save
            ?: error("Displayed control does not save a document")
        require(text.toByteArray(Charsets.UTF_8).size <= control.maxBytes)
        val access = checkNotNull(documents) { "No page document picker" }
        check(consumed.compareAndSet(false, true)) { "Native page control already consumed" }
        return access.save(control, text, ::ensureAuthorized).also { ensureAuthorized() }
    }
}
