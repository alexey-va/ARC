package ru.arc.board

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.boss.BarColor
import ru.arc.KotestTestBase
import ru.arc.configs.BoardConfig
import java.util.UUID

class BoardAnnounceLogicTest : KotestTestBase({

    fun makeEntry(
        title: String = "test",
        timestamp: Long = System.currentTimeMillis(),
        lastShown: Long = 0L,
    ) = BoardEntryData(
        entryUuid = UUID.randomUUID(),
        playerUuid = UUID.randomUUID(),
        playerName = "Player",
        type = BoardEntryType.INFO,
        text = "body",
        title = title,
        icon = ItemIcon.of(Material.PAPER, 0),
        color = BarColor.YELLOW,
        timestamp = timestamp,
        lastShown = lastShown,
    )

    describe("selectNextAnnounceEntry") {
        it("should skip expired entries") {
            val now = System.currentTimeMillis()
            val expiredTs = now - BoardConfig.secondsLifetime * 1000L - 1
            val fresh = makeEntry(title = "fresh", lastShown = 100)
            val stale = makeEntry(title = "stale", timestamp = expiredTs, lastShown = 0)

            selectNextAnnounceEntry(listOf(stale, fresh)) shouldBe fresh
        }

        it("should return null when all entries expired") {
            val now = System.currentTimeMillis()
            val expiredTs = now - BoardConfig.secondsLifetime * 1000L - 1
            val stale = makeEntry(timestamp = expiredTs)

            selectNextAnnounceEntry(listOf(stale)).shouldBeNull()
        }

        it("should pick oldest lastShown among live entries") {
            val a = makeEntry(title = "a", lastShown = 50)
            val b = makeEntry(title = "b", lastShown = 10)
            val c = makeEntry(title = "c", lastShown = 30)

            selectNextAnnounceEntry(listOf(a, b, c)) shouldBe b
        }
    }

    describe("isExpired") {
        it("should be false for fresh entry") {
            makeEntry().isExpired() shouldBe false
        }

        it("should be true when older than lifetime") {
            val ts = System.currentTimeMillis() - BoardConfig.secondsLifetime * 1000L - 1
            makeEntry(timestamp = ts).isExpired() shouldBe true
        }
    }
})
