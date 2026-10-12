package dev.solvit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ToolDispatchCheckpointTest {
    private val allow=object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor)=true }
    @Test fun unloadingDuringSuspendedDurableCheckpointPreventsSideEffects() = runTest {
        val registry=ToolRegistry(EventBus());var effects=0
        val unload=registry.register(object : SolvitTool {
            override val descriptor=ToolDescriptor("write","write","{}")
            override suspend fun execute(argumentsJson: String): String { effects++;return "written" }
        })
        val saving=CompletableDeferred<Unit>();val saved=CompletableDeferred<Unit>()
        val invocation=async {
            assertFailsWith<IllegalStateException> {
                registry.executeCall(ToolCall("one","write","{}"),allow) { saving.complete(Unit);saved.await() }
            }
        }
        saving.await();unload();saved.complete(Unit);invocation.await()
        assertEquals(0,effects)
    }
    @Test fun cancellationDuringCheckpointCannotStartToolEvenIfCallbackReturnsNormally() = runTest {
        val registry=ToolRegistry(EventBus());var effects=0
        registry.register(object : SolvitTool {
            override val descriptor=ToolDescriptor("write","write","{}")
            override suspend fun execute(argumentsJson: String): String { effects++;return "written" }
        })
        val saving=CompletableDeferred<Unit>();val saved=CompletableDeferred<Unit>()
        val invocation=launch {
            registry.executeCall(ToolCall("one","write","{}"),allow) {
                saving.complete(Unit);withContext(NonCancellable) { saved.await() }
            }
        }
        saving.await();invocation.cancel();saved.complete(Unit);invocation.join()
        assertTrue(invocation.isCancelled);assertEquals(0,effects)
    }
}
