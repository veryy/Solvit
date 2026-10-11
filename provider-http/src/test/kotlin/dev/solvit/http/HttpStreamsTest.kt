package dev.solvit.http

import dev.solvit.core.ModelException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.*

class HttpStreamsTest {
    @Test fun parsesMultilineCrLfFramesAndStopsWithoutConsumingLaterEvents() = runTest {
        val source = Buffer().writeUtf8(": keepalive\r\nevent: text\r\ndata: one\r\ndata: two\r\n\r\ndata: later\n\n")
        val events = mutableListOf<Pair<String,String>>()
        readSse(source) { name,data -> events += name to data; false }
        assertEquals(listOf("text" to "one\ntwo"),events)
        assertEquals("data: later\n\n",source.readUtf8())
    }
    @Test fun rejectsOversizedLineEvenWhenItIsAnUnknownField() = runTest {
        val source = Buffer().writeUtf8("unknown:" + "x".repeat(2*1024*1024) + "\n")
        assertFailsWith<ModelException> { readSse(source) { _,_ -> true } }
    }
}
