package ru.arc.treasurechests

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class HuntAutoStartStoreTest : DescribeSpec({
    val directories = mutableListOf<Path>()
    fun directory(): Path = Files.createTempDirectory("hunt-auto-start-test").also(directories::add)
    afterEach { directories.forEach { it.toFile().deleteRecursively() }; directories.clear() }

    it("defers filesystem access and imports a deadline only on first initialization") {
        val root = directory().resolve("arc")
        val store = FileHuntAutoStartStore(root)
        Files.exists(root) shouldBe false
        store.loadOrInitialize(1_792_276_200_965L) shouldBe 1_792_276_200_965L
        FileHuntAutoStartStore(root).loadOrInitialize(1_800_000_000_000L) shouldBe 1_792_276_200_965L
    }

    it("persists reservations across restart and never shortens the deadline") {
        val root = directory()
        val store = FileHuntAutoStartStore(root)
        store.loadOrInitialize(100L) shouldBe 100L
        store.reserve(200L) shouldBe 200L
        store.reserve(150L) shouldBe 200L
        FileHuntAutoStartStore(root).loadOrInitialize(300L) shouldBe 200L
    }

    it("rejects corrupt persisted state instead of resetting its cooldown") {
        val root = directory()
        val file = root.resolve("data/hunt-auto-start/spawn.json")
        Files.createDirectories(file.parent)
        for (contents in listOf("{}", "{\"version\":1}", "{\"version\":2,\"nextAttemptAt\":100}", "broken")) {
            Files.writeString(file, contents)
            shouldThrowAny { FileHuntAutoStartStore(root).loadOrInitialize(100L) }
            Files.readString(file) shouldBe contents
        }
    }

    it("does not start a new reservation if its initialized state disappeared") {
        val root = directory()
        val store = FileHuntAutoStartStore(root)
        store.loadOrInitialize(100L)
        Files.delete(root.resolve("data/hunt-auto-start/spawn.json"))
        shouldThrowAny { store.reserve(200L) }
        Files.exists(root.resolve("data/hunt-auto-start/spawn.json")) shouldBe false
    }
})
