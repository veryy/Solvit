package dev.solvit.plugins

import kotlinx.serialization.json.*
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/** All representations derive from one instant, so UTC and local cannot drift apart. */
internal fun clockSnapshot(clock: Clock = Clock.system(TimeZone.getDefault().toZoneId())): JsonObject {
    val local = ZonedDateTime.now(clock)
    val utc = local.toInstant()
    val display = local.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss（EEEE）",Locale.SIMPLIFIED_CHINESE))
    return buildJsonObject {
        put("utc",utc.toString()); put("localTime",local.toOffsetDateTime().toString())
        put("timezone",local.zone.id); put("timezoneOffsetMinutes",-local.offset.totalSeconds/60)
        put("utcOffset",local.offset.id); put("weekday",local.dayOfWeek.value)
        put("displayLocal",display+" · "+local.zone.id+" · UTC"+local.offset.id)
        put("displayUtc",utc.toString()+" · UTC；与上述本地时间是同一个时刻")
    }
}
