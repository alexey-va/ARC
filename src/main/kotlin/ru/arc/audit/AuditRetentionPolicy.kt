package ru.arc.audit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class AuditRetentionBoundaries(
    val jobsCompactBeforeDay: LocalDate,
    val deleteBeforeEpochMs: Long,
)

class AuditRetentionPolicy(
    retentionDays: Int,
    jobsRawRetentionDays: Int,
) {
    val retentionDays: Int = retentionDays.coerceIn(7, 365)
    val jobsRawRetentionDays: Int = jobsRawRetentionDays.coerceIn(1, this.retentionDays)

    fun boundaries(now: Instant): AuditRetentionBoundaries {
        val currentDay = now.atZone(ZoneOffset.UTC).toLocalDate()
        return AuditRetentionBoundaries(
            jobsCompactBeforeDay = currentDay.minusDays(jobsRawRetentionDays.toLong()),
            deleteBeforeEpochMs =
                currentDay.minusDays(retentionDays.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
    }

    fun canCompact(day: LocalDate, now: Instant): Boolean = day.isBefore(boundaries(now).jobsCompactBeforeDay)
}
