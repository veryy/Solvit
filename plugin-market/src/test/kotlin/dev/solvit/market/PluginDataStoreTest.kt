package dev.solvit.market

import android.content.Context
import dev.solvit.core.PostTurnRevokedException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PluginDataStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val id = "test.atomic"
    @Before fun clear() { context.getSharedPreferences("extension_data.$id", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun independentInstancesShareAtomicClaimsAndPreserveManualWrites() = runBlocking<Unit> {
        val first = PluginDataStore(context); val second = PluginDataStore(context)
        val initial = buildJsonObject { put("text", "中文 🚀"); put("revision", 0) }
        assertTrue(first.compareAndSet(id, "memory", JsonNull, initial) {})
        val snapshot = second.get(id, "memory")
        val winners = awaitAll(
            async(Dispatchers.Default) { first.compareAndSet(id, "memory", snapshot, JsonPrimitive("one")) {} },
            async(Dispatchers.Default) { second.compareAndSet(id, "memory", snapshot, JsonPrimitive("two")) {} })
        assertEquals(1, winners.count { it })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writer = launch { first.set(id, "memory", JsonPrimitive("manual"), false) { entered.complete(Unit); release.await() } }
        entered.await()
        val stale = async { second.compareAndSet(id, "memory", snapshot, JsonPrimitive("stale")) {} }
        release.complete(Unit); writer.join()
        assertFalse(stale.await()); assertEquals(JsonPrimitive("manual"), first.get(id, "memory"))
    }

    @Test fun revocationIsCheckedInsideTheWriteLockAndDoesNotChangeStoredData() = runBlocking<Unit> {
        val data = PluginDataStore(context)
        data.set(id, "memory", JsonPrimitive("original"), true) {}
        assertFailsWith<PostTurnRevokedException> { data.compareAndSet(id, "memory", JsonPrimitive("original"), JsonPrimitive("changed")) { throw PostTurnRevokedException() } }
        assertFailsWith<PostTurnRevokedException> { data.set(id, "memory", JsonNull, true) { throw PostTurnRevokedException() } }
        assertEquals(JsonPrimitive("original"), data.get(id, "memory"))
        assertFalse(data.compareAndSet(id, "memory", JsonPrimitive(1), JsonNull) {})
    }
}
