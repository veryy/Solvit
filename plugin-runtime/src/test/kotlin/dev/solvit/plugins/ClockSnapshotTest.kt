package dev.solvit.plugins

import java.time.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ClockSnapshotTest {
    @Test fun shanghaiAndUtcDescribeSameInstantWithCorrectWeekday() {
        val value=clockSnapshot(Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"),ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10-11T00:00:00Z",value.getValue("utc").jsonPrimitive.content)
        assertEquals("2026-10-11T08:00+08:00",value.getValue("localTime").jsonPrimitive.content)
        assertEquals("+08:00",value.getValue("utcOffset").jsonPrimitive.content)
        assertEquals(-480,value.getValue("timezoneOffsetMinutes").jsonPrimitive.int)
        assertEquals(7,value.getValue("weekday").jsonPrimitive.int)
        assertTrue(value.getValue("displayLocal").jsonPrimitive.content.contains("08:00:00（星期日）"))
        assertEquals(Instant.parse(value.getValue("utc").jsonPrimitive.content),OffsetDateTime.parse(value.getValue("localTime").jsonPrimitive.content).toInstant())
    }
    @Test fun eachSnapshotReadsFreshTimeAndRespectsOtherTimezones() {
        val instant=Instant.parse("2026-10-11T00:00:00Z")
        val first=clockSnapshot(Clock.fixed(instant,ZoneId.of("America/New_York")))
        val next=clockSnapshot(Clock.fixed(instant.plusSeconds(1),ZoneId.of("America/New_York")))
        assertEquals("-04:00",first.getValue("utcOffset").jsonPrimitive.content)
        assertEquals(240,first.getValue("timezoneOffsetMinutes").jsonPrimitive.int)
        assertNotEquals(first.getValue("utc"),next.getValue("utc"))
    }
}
