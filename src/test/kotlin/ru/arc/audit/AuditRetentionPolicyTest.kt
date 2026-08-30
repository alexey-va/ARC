package ru.arc.audit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate

class AuditRetentionPolicyTest : StringSpec({
    "jobs compaction includes only complete UTC days older than the raw window" {
        val now = Instant.parse("2026-08-30T12:00:00Z")
        val policy = AuditRetentionPolicy(retentionDays = 30, jobsRawRetentionDays = 7)

        val boundaries = policy.boundaries(now)

        boundaries.jobsCompactBeforeDay shouldBe LocalDate.parse("2026-08-23")
        boundaries.deleteBeforeEpochMs shouldBe Instant.parse("2026-07-31T00:00:00Z").toEpochMilli()
        policy.canCompact(LocalDate.parse("2026-08-22"), now) shouldBe true
        policy.canCompact(LocalDate.parse("2026-08-23"), now) shouldBe false
    }

    "jobs raw retention cannot exceed general retention" {
        val policy = AuditRetentionPolicy(retentionDays = 14, jobsRawRetentionDays = 30)

        policy.jobsRawRetentionDays shouldBe 14
        policy.boundaries(Instant.parse("2026-08-30T00:00:00Z"))
            .jobsCompactBeforeDay shouldBe LocalDate.parse("2026-08-16")
    }
})
