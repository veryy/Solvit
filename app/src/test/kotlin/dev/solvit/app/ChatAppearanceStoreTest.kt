package dev.solvit.app

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ChatAppearanceStoreTest {
    @Test fun chatPreferencesSurviveReloadWithoutChangingThemeAndInvalidValuesLeaveThemIntact() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("appearance",0).edit().clear().commit()
        val store=AppearanceStore(context);store.setTheme("dark")
        val selected=ChatAppearance(22f,30,"plain");store.setChat(selected)
        assertEquals(selected,AppearanceStore(context).chat.value)
        assertEquals("dark",AppearanceStore(context).theme.value)
        for(invalid in listOf(selected.copy(fontSize=Float.NaN),selected.copy(replySpeed=1),selected.copy(style="unknown"))) {
            assertFailsWith<IllegalArgumentException> { store.setChat(invalid) }
            assertEquals(selected,AppearanceStore(context).chat.value)
        }
    }
}
