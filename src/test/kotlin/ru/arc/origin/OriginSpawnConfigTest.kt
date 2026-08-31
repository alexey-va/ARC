package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import java.nio.file.Files

class OriginSpawnConfigTest :
    FreeSpec({
        afterTest { ConfigManager.clear() }

        "bundled Origin mask plans exactly 759 chunks from the center outward" {
            val directory = Files.createTempDirectory("arc-origin-config")
            try {
                val config = OriginSpawnConfig.load(directory)
                val plan = OriginChunkPlanner.plan(config.chunkRegion)

                config.enabled shouldBe false
                plan.size shouldBe 759
                plan.first() shouldBe OriginChunkKey(0, -1)
                plan.toSet().size shouldBe 759
                plan.contains(OriginChunkKey(-16, -12)) shouldBe true
                plan.contains(OriginChunkKey(16, 10)) shouldBe true
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "merge-forward preserves operator values and is idempotent" {
            val directory = Files.createTempDirectory("arc-origin-config-migration")
            try {
                val modules = Files.createDirectories(directory.resolve("modules"))
                val file = modules.resolve("origin-spawn.yml")
                Files.writeString(
                    file,
                    """
                    enabled: false
                    world: operator_origin
                    chunks:
                      max-in-flight: 3
                    """.trimIndent() + "\n",
                )

                val first = OriginSpawnConfig.load(directory)
                first.worldName shouldBe "operator_origin"
                first.chunkRegion.maxInFlight shouldBe 3
                first.pedestals.size shouldBe 6
                val afterFirst = Files.readString(file)

                ConfigManager.ofModule(directory, "origin-spawn.yml")
                    .mergeMissingFromBundled("modules/origin-spawn.yml") shouldBe false
                Files.readString(file) shouldBe afterFirst
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "listing values are inserted as literal components rather than MiniMessage" {
            val directory = Files.createTempDirectory("arc-origin-text")
            try {
                val config = OriginSpawnConfig.load(directory)
                val rendered = config.listingText("<red>Меч", "<bold>Игрок", "<click:run_command:/op>10")

                PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe
                    "<red>Меч\nПродавец: <bold>Игрок\nЦена: <click:run_command:/op>10"
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    })
