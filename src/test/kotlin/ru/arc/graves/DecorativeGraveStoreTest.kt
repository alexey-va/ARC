package ru.arc.graves

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DecorativeGraveStoreTest : FunSpec({
    test("decorative creation replacement and removal survive independent store instances") {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "decorative-graves-")
        try {
            val original = decorativeRecord()
            DecorativeGraveStore(root).load() shouldBe emptyList()
            DecorativeGraveStore(root).save(listOf(original))
            DecorativeGraveStore(root).load() shouldBe listOf(original)
            val changed = original.copy(revision = 1, epitaph = "Другие слова", signature = "Автор", yaw = 270f)
            DecorativeGraveStore(root).save(listOf(changed))
            DecorativeGraveStore(root).load() shouldBe listOf(changed)
            DecorativeGraveStore(root).save(emptyList())
            DecorativeGraveStore(root).load() shouldBe emptyList()
        } finally { root.toFile().deleteRecursively() }
    }

    test("bounded catalog rejects malformed records without replacing committed scenery") {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "decorative-graves-validation-")
        try {
            val store = DecorativeGraveStore(root)
            val original = decorativeRecord()
            store.save(listOf(original))
            for (invalid in listOf(original.copy(epitaph = "x".repeat(81)), original.copy(signature = "x".repeat(18)),
                original.copy(yaw = 45f), original.copy(world = ""), original.copy(revision = -1))) {
                shouldThrowAny { store.save(listOf(invalid)) }
                store.load() shouldBe listOf(original)
            }
            shouldThrowAny { store.save(listOf(original, original)) }
            val path = root.resolve("data/decorative-graves.json")
            val valid = Files.readString(path)
            for ((field, invalid) in listOf("version" to "null", "version" to "2", "x" to "null", "x" to "4294967296",
                "x" to "1.9", "yaw" to "null", "createdAt" to "null", "revision" to "null", "epitaph" to "true")) {
                val tree = com.google.gson.JsonParser.parseString(valid).asJsonObject
                val owner = if (field == "version") tree else tree.getAsJsonArray("records").first().asJsonObject
                owner.add(field, com.google.gson.JsonParser.parseString(invalid))
                Files.writeString(path, tree.toString())
                shouldThrowAny { DecorativeGraveStore(root).load() }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    test("decorative projection contains no recovery contents and keeps its explicit cosmetics") {
        val record = decorativeRecord().copy(style = GraveStyle.ENGRAVED_TIMBER, signature = "")
        val view = record.view("survival")
        view.decorative shouldBe true
        view.experience shouldBe 0
        view.items shouldBe emptyList()
        view.ownerName shouldBe ""
        view.expiresAt shouldBe -1
        view.cosmeticYaw shouldBe record.yaw
        view.appearance shouldBe GraveAppearanceChoice("timber", true, record.epitaph)
    }
})

internal fun decorativeRecord() = DecorativeGraveRecord(UUID(1, 1), UUID(2, 2), UUID(3, 3), "world", 0, 64, 0,
    10, 0, GraveStyle.SLATE, "Мои слова", "", 0f)
