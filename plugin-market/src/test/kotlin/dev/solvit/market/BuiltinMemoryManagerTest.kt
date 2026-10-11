package dev.solvit.market

import android.content.Context
import kotlinx.coroutines.runBlocking
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
class BuiltinMemoryManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.getSharedPreferences("extension_data.dev.solvit.memory", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun humanManagementWorksWithoutEnablingPluginOrGrantingModelAndSurvivesRestart() = runBlocking<Unit> {
        val manager = BuiltinMemoryManager(context)
        assertTrue(manager.snapshot().getValue("facts").jsonArray.isEmpty())
        val created = manager.save("Prefer Kotlin", "preference", listOf("coding"))
        val id = created.getValue("fact").jsonObject.getValue("id").jsonPrimitive.content
        val reopened = BuiltinMemoryManager(context)
        assertEquals("Prefer Kotlin", reopened.snapshot().getValue("facts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        reopened.save("Prefer TypeScript", "preference", listOf("plugins"), id)
        assertEquals(id, manager.snapshot().getValue("facts").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        reopened.delete(id)
        assertTrue(manager.snapshot().getValue("facts").jsonArray.isEmpty())
        assertFailsWith<Exception> { reopened.save("Do not recreate", "fact", emptyList(), id) }
        assertTrue(context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).all.isEmpty())
    }
    @Test fun invalidStoredDataIsPreservedInsteadOfOverwritten() = runBlocking<Unit> {
        val prefs = context.getSharedPreferences("extension_data.dev.solvit.memory", Context.MODE_PRIVATE)
        prefs.edit().putString("memory.v1", "{\"format\":99}").commit()
        assertFailsWith<Exception> { BuiltinMemoryManager(context).save("A fact", "fact", emptyList()) }
        assertEquals("{\"format\":99}", prefs.getString("memory.v1", null))
    }
}
