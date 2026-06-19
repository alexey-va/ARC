package ru.arc.util

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class LoggingQuietSourcesTest : FreeSpec({

    "matchesQuietSource" - {
        val sources = setOf("ru.arc.sync", "ru.arc.repository")

        "should match exact package prefix" {
            Logging.matchesQuietSource("ru.arc.sync.base.SyncRepo", sources) shouldBe true
            Logging.matchesQuietSource("ru.arc.repository.CachedRepository", sources) shouldBe true
        }

        "should not match unrelated packages" {
            Logging.matchesQuietSource("ru.arc.farm.FarmManager", sources) shouldBe false
            Logging.matchesQuietSource("ru.arc.ops.OpsHttpHandlers", sources) shouldBe false
        }

        "should not match prefix that is only a substring" {
            Logging.matchesQuietSource("ru.arc.synced.Other", setOf("ru.arc.sync")) shouldBe false
        }

        "should treat empty sources as no filter" {
            Logging.matchesQuietSource("ru.arc.sync.EmSync", emptySet()) shouldBe false
        }

        "should match lambda classes under quiet package" {
            Logging.matchesQuietSource(
                "ru.arc.sync.base.SyncRepo\$\$Lambda\$0x12345678",
                sources,
            ) shouldBe true
        }
    }
})
