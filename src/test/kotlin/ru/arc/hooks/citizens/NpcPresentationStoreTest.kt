package ru.arc.hooks.citizens

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.util.UUID

class NpcPresentationStoreTest : StringSpec({
    "round-trips a Cyrillic formatted presentation through the real file store" {
        val root = Files.createTempDirectory("npc-presentation-store-")
        val id = UUID.fromString("8f0d8d48-5d73-4d87-8a65-0ad0e7fd1a01")
        val record =
            NpcPresentationRecord(
                npcId = 37,
                presentation = NpcPresentation(
                    name = "§6Кузнец <Натан>",
                    nameVisible = true,
                    lines = listOf("§eДобро пожаловать", "§7Выберите путь"),
                    lineHeight = 0.35,
                    viewRange = 24,
                    hasHologram = true,
                    speechBubbles = true,
                    sendTextToChat = false,
                ),
            )
        val store = FileNpcPresentationStore(root)

        store.save(mapOf(id to record))

        store.load() shouldBe mapOf(id to record)
        val json = Files.readString(root.resolve("data/npc-presentations.json"))
        json shouldContain "\"schemaVersion\""
        json shouldContain "\"$id\""
        json shouldContain "Кузнец"
        json shouldContain "\n"
    }

    "preserves a hidden name, explicit empty body, and optional flags" {
        val root = Files.createTempDirectory("npc-presentation-empty-")
        val id = UUID.randomUUID()
        val record =
            NpcPresentationRecord(
                npcId = 41,
                presentation = NpcPresentation(
                    name = "",
                    nameVisible = false,
                    lines = emptyList(),
                    speechBubbles = null,
                    sendTextToChat = null,
                ),
            )
        val store = FileNpcPresentationStore(root)

        store.save(mapOf(id to record))

        store.load()[id] shouldBe record
        val json = Files.readString(root.resolve("data/npc-presentations.json"))
        json shouldContain "\"name\": \"\""
        json shouldContain "\"nameVisible\": false"
        json shouldContain "\"lines\": []"
    }

    "preserves the exact legacy backup alongside the typed presentation" {
        val root = Files.createTempDirectory("npc-presentation-backup-")
        val id = UUID.randomUUID()
        val legacyBackup = "name=§6Старый Натан\nlines=§7Первая|§fВторая\nvisible=false"
        val record =
            NpcPresentationRecord(
                npcId = 52,
                presentation = NpcPresentation(
                    name = "Натан",
                    nameVisible = false,
                    lines = listOf("§7Первая", "§fВторая"),
                ),
                legacyBackup = legacyBackup,
            )
        val store = FileNpcPresentationStore(root)

        store.save(mapOf(id to record))

        store.load()[id]?.legacyBackup shouldBe legacyBackup
    }

    "allows historical records to reuse a Citizens id under different UUIDs" {
        val root = Files.createTempDirectory("npc-presentation-reused-id-")
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val firstRecord =
            NpcPresentationRecord(
                npcId = 7,
                presentation = NpcPresentation(name = "Старый NPC", nameVisible = true, lines = emptyList()),
            )
        val secondRecord =
            NpcPresentationRecord(
                npcId = 7,
                presentation = NpcPresentation(name = "Новый NPC", nameVisible = true, lines = emptyList()),
            )
        val store = FileNpcPresentationStore(root)

        store.save(mapOf(first to firstRecord, second to secondRecord))

        store.load() shouldBe mapOf(first to firstRecord, second to secondRecord)
    }

    "refuses malformed content without replacing it" {
        val root = Files.createTempDirectory("npc-presentation-malformed-")
        val path = root.resolve("data/npc-presentations.json")
        Files.createDirectories(requireNotNull(path.parent))
        val malformed = "{not-json"
        Files.writeString(path, malformed)

        shouldThrow<IllegalArgumentException> { FileNpcPresentationStore(root).load() }

        Files.readString(path) shouldBe malformed
    }

    "refuses an unknown schema without replacing it" {
        val root = Files.createTempDirectory("npc-presentation-schema-")
        val path = root.resolve("data/npc-presentations.json")
        Files.createDirectories(requireNotNull(path.parent))
        val unknownSchema = """
            {
              "schemaVersion": 99,
              "records": {}
            }
        """.trimIndent()
        Files.writeString(path, unknownSchema)

        shouldThrow<IllegalArgumentException> { FileNpcPresentationStore(root).load() }

        Files.readString(path) shouldBe unknownSchema
    }
})
