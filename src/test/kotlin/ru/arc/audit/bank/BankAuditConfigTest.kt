package ru.arc.audit.bank

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class BankAuditConfigTest :
    StringSpec({
        "loads a normalized single collector and bounded sampling policy" {
            val dataPath = Files.createTempDirectory("bank-audit-config")
            Files.createDirectories(dataPath.resolve("modules"))
            Files.writeString(
                dataPath.resolve("modules/bank-audit.yml"),
                """
                enabled: true
                collector-server: SPAWN
                sample-interval-seconds: 1
                initial-delay-seconds: 999
                max-accounts: 999999
                top-accounts: 999
                recent-changes: 999
                minimum-change: -5
                expected-max-lag-seconds: 99999
                """.trimIndent(),
            )
            ConfigManager.clear()

            val config = BankAuditConfig.fromFile(dataPath)

            config.collectorServer shouldBe "spawn"
            config.isCollector("spawn") shouldBe true
            config.isCollector("survival") shouldBe false
            config.sampleIntervalSeconds shouldBe 60
            config.initialDelaySeconds shouldBe 300
            config.maxAccounts shouldBe 100_000
            config.topAccounts shouldBe 100
            config.recentChanges shouldBe 200
            config.minimumChange shouldBe 0.0
            config.expectedMaxLagSeconds shouldBe 3_600
            ConfigManager.clear()
        }

        "disabled config never elects a collector" {
            TestBankAuditConfig(enabled = false).isCollector("spawn") shouldBe false
        }
    })
