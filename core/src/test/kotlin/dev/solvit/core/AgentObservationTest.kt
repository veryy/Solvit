package dev.solvit.core

import kotlin.test.*

class AgentObservationTest {
    @Test fun textLimitsReportTruncationAndPreserveWholeUnicodePairs() {
        assertEquals(AgentText("a", true), AgentText.of("a😀b", 2))
        assertEquals(AgentText("a😀", true), AgentText.of("a😀b", 3))
        assertEquals(AgentText("a😀b", false), AgentText.of("a😀b", 4))
        assertEquals(AgentText("", false), AgentText.of("", 1))
    }
}
