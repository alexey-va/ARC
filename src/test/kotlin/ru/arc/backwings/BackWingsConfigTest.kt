package ru.arc.backwings

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files

class BackWingsConfigTest : FreeSpec({
    "merge adds all three poses, preserves operator values and is idempotent" {
        val root = Files.createTempDirectory("arc-back-wings-config-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve(BackWingsConfig.RESOURCE), """
            models:
              dreadknight:
                scale: 0.8
            operator-note: retained
        """.trimIndent())
        val source = Config(root, BackWingsConfig.RESOURCE)
        source.mergeMissingFromBundled(BackWingsConfig.RESOURCE) shouldBe true
        val settings = BackWingsConfig(source).snapshot { error(it) }
        settings.styles.keys shouldBe BackWing.entries.toSet()
        settings.styles.getValue(BackWing.DREADKNIGHT).scale shouldBe 0.8f
        settings.styles.getValue(BackWing.GALAXY) shouldBe BackWingsSettings.DEFAULT_STYLES.getValue(BackWing.GALAXY)
        source.string("operator-note", "") shouldBe "retained"
        source.mergeMissingFromBundled(BackWingsConfig.RESOURCE) shouldBe false
    }

    "invalid visual tuning falls back for that model without disabling the other wings" {
        val root = Files.createTempDirectory("arc-back-wings-invalid-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve(BackWingsConfig.RESOURCE), """
            models:
              dreadknight:
                scale: -1
              galaxy:
                local-anchor: { x: 0.2, y: 1.2, z: -0.3 }
        """.trimIndent())
        val warnings = mutableListOf<String>()
        val settings = BackWingsConfig(Config(root, BackWingsConfig.RESOURCE)).snapshot(warnings::add)
        warnings.size shouldBe 1
        settings.styles.getValue(BackWing.DREADKNIGHT) shouldBe BackWingsSettings.DEFAULT_STYLES.getValue(BackWing.DREADKNIGHT)
        settings.styles.getValue(BackWing.GALAXY).anchor shouldBe WingVector(0.2f, 1.2f, -0.3f)
        BackWing.fromItemId("another:wings") shouldBe null
        BackWing.fromItemId("DREADKNIGHT:dreadknight_wings") shouldBe null
    }
})
