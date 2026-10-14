package dev.solvit.plugins

import kotlinx.serialization.json.*
import kotlin.test.*

class ScriptPagePictureTest {
    private fun view(source: String = "data:image/jpeg;base64,/9gq/9k=", width: Int = 12, extra: Boolean = false) = buildJsonObject {
        put("title", "Picture")
        putJsonArray("nodes") { add(buildJsonObject {
            put("id", "picture"); put("kind", "image"); put("source", source); put("alt", "File preview")
            put("width", width); put("height", 8)
            if (extra) put("action", "unreviewed")
        }) }
    }.toString()
    @Test fun pictureNodesHaveBoundedInlineJpegDataAndNoActionOrRemoteUrl() {
        val valid = ScriptPageView.parse(view())
        assertTrue(valid.hasImages); assertFails { valid.event("picture") }
        for (source in listOf("https://example.com/tracker.jpg", "file:///private/image.jpg", "data:image/svg+xml;base64,PHN2Zz4=",
            "data:image/jpeg;base64,aW52YWxpZA==", "data:image/jpeg;base64,/9gq/9k=\n")) {
            assertFails { ScriptPageView.parse(view(source)) }
        }
        assertFails { ScriptPageView.parse(view(width = 641)) }
        assertFails { ScriptPageView.parse(view(extra = true)) }
        assertFails { ScriptPageView.parse(view("data:image/jpeg;base64," + "A".repeat(90_000))) }
    }
}
