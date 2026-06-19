package ru.arc.board

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import org.bukkit.boss.BarColor
import ru.arc.KotestTestBase
import java.util.UUID

class BoardEntryDataTest : KotestTestBase({

    fun makeEntry(
        playerUuid: UUID = UUID.randomUUID(),
        playerName: String = "TestPlayer"
    ) = BoardEntryData(
        entryUuid = UUID.randomUUID(),
        playerUuid = playerUuid,
        playerName = playerName,
        type = BoardEntryType.SELL,
        text = "some item",
        title = "Short title",
        icon = ItemIcon.of(Material.DIAMOND, 0)
    )

    describe("rating") {

        it("should add positive rating") {
            val entry = makeEntry()

            entry.rate("Alice", 1)

            entry.positiveRatings shouldBe setOf("Alice")
            entry.negativeRatings.isEmpty() shouldBe true
        }

        it("should add negative rating") {
            val entry = makeEntry()

            entry.rate("Bob", -1)

            entry.negativeRatings shouldBe setOf("Bob")
            entry.positiveRatings.isEmpty() shouldBe true
        }

        it("should switch from negative to positive rating") {
            val entry = makeEntry()
            entry.rate("Alice", -1)

            entry.rate("Alice", 1)

            entry.positiveRatings shouldBe setOf("Alice")
            entry.negativeRatings.isEmpty() shouldBe true
        }

        it("should switch from positive to negative rating") {
            val entry = makeEntry()
            entry.rate("Alice", 1)

            entry.rate("Alice", -1)

            entry.negativeRatings shouldBe setOf("Alice")
            entry.positiveRatings.isEmpty() shouldBe true
        }

        it("should support multiple raters") {
            val entry = makeEntry()
            entry.rate("Alice", 1)
            entry.rate("Bob", -1)
            entry.rate("Charlie", 1)

            entry.positiveRatings.size shouldBe 2
            entry.negativeRatings.size shouldBe 1
        }
    }

    describe("hasRated") {

        it("should return 1 when player gave positive rating") {
            val entry = makeEntry()
            entry.rate("Alice", 1)
            val player = server.addPlayer("Alice")

            entry.hasRated(player) shouldBe 1
        }

        it("should return -1 when player gave negative rating") {
            val entry = makeEntry()
            entry.rate("Bob", -1)
            val player = server.addPlayer("Bob")

            entry.hasRated(player) shouldBe -1
        }

        it("should return 0 when player has not rated") {
            val entry = makeEntry()
            val player = server.addPlayer("Charlie")

            entry.hasRated(player) shouldBe 0
        }
    }

    describe("reporting") {

        it("should mark player as having reported") {
            val entry = makeEntry()

            entry.report("Moderator")

            entry.reports shouldBe setOf("Moderator")
        }

        it("should support multiple reporters") {
            val entry = makeEntry()
            entry.report("Mod1")
            entry.report("Mod2")

            entry.reports.size shouldBe 2
        }

        it("should return false when player has not reported") {
            val entry = makeEntry()
            val player = server.addPlayer("Innocent")

            entry.hasReported(player) shouldBe false
        }

        it("should return true when player has reported") {
            val entry = makeEntry()
            entry.report("Reporter")
            val player = server.addPlayer("Reporter")

            entry.hasReported(player) shouldBe true
        }
    }

    describe("canEdit") {

        it("should allow owner to edit") {
            val owner = server.addPlayer("Owner")
            val entry = makeEntry(playerUuid = owner.uniqueId)

            entry.canEdit(owner) shouldBe true
        }

        it("should allow admin with permission to edit") {
            val entry = makeEntry(playerUuid = UUID.randomUUID())
            val admin = server.addPlayer("Admin")
            admin.addAttachment(plugin, "arc.board.admin", true)

            entry.canEdit(admin) shouldBe true
        }

        it("should deny non-owner without admin permission") {
            val entry = makeEntry(playerUuid = UUID.randomUUID())
            val other = server.addPlayer("OtherPlayer")

            entry.canEdit(other) shouldBe false
        }
    }

    describe("canRate") {

        it("should allow non-owner to rate") {
            val entry = makeEntry(playerUuid = UUID.randomUUID())
            val other = server.addPlayer("OtherPlayer")

            entry.canRate(other) shouldBe true
        }

        it("should deny owner from rating their own entry") {
            val owner = server.addPlayer("Owner")
            val entry = makeEntry(playerUuid = owner.uniqueId)

            entry.canRate(owner) shouldBe false
        }

        it("should allow owner to rate own entry with arc.rate-own permission") {
            val owner = server.addPlayer("Owner")
            val entry = makeEntry(playerUuid = owner.uniqueId)
            owner.addAttachment(plugin, "arc.rate-own", true)

            entry.canRate(owner) shouldBe true
        }
    }

    describe("mutable field changes") {

        it("changeText should update text") {
            val entry = makeEntry()
            entry.changeText("new text")

            entry.text shouldBe "new text"
        }

        it("changeText should not change when same value") {
            val entry = makeEntry()
            entry.changeText("same")
            entry.changeText("same")

            entry.text shouldBe "same"
        }

        it("changeTitle should update title") {
            val entry = makeEntry()
            entry.changeTitle("new title")

            entry.title shouldBe "new title"
        }

        it("changeIcon should update icon") {
            val entry = makeEntry()
            val newIcon = ItemIcon.of(Material.EMERALD, 0)

            entry.changeIcon(newIcon)

            entry.icon shouldBe newIcon
        }

        it("changeColor should update color") {
            val entry = makeEntry()

            entry.changeColor(BarColor.BLUE)

            entry.color shouldBe BarColor.BLUE
        }

        it("changeType should update type") {
            val entry = makeEntry()

            entry.changeType(BoardEntryType.BUY)

            entry.type shouldBe BoardEntryType.BUY
        }

        it("changeLastShown should update timestamp") {
            val entry = makeEntry()
            val now = System.currentTimeMillis()

            entry.changeLastShown(now)

            entry.lastShown shouldBe now
        }
    }

    describe("merge") {

        it("should merge mutable fields from an independent entry") {
            val sharedUuid = UUID.randomUUID()
            val sharedPlayerUuid = UUID.randomUUID()

            val original = BoardEntryData(
                entryUuid = sharedUuid,
                playerUuid = sharedPlayerUuid,
                playerName = "Original",
                type = BoardEntryType.SELL,
                text = "old text",
                title = "old title",
                icon = ItemIcon.of(Material.DIAMOND, 0),
                color = BarColor.YELLOW
            )
            original.rate("Alice", 1)

            // Build a fresh independent update entry
            val update = BoardEntryData(
                entryUuid = sharedUuid,
                playerUuid = sharedPlayerUuid,
                playerName = "Original",
                type = BoardEntryType.BUY,
                text = "updated text",
                title = "updated title",
                icon = ItemIcon.of(Material.EMERALD, 0),
                color = BarColor.GREEN
            )
            update.rate("Bob", -1)
            update.report("Reporter")

            original.merge(update)

            original.text shouldBe "updated text"
            original.title shouldBe "updated title"
            original.color shouldBe BarColor.GREEN
            original.type shouldBe BoardEntryType.BUY
            original.negativeRatings shouldBe setOf("Bob")
            original.positiveRatings.isEmpty() shouldBe true
            original.reports shouldBe setOf("Reporter")
        }
    }

    describe("id") {

        it("should return entry UUID as string") {
            val uuid = UUID.randomUUID()
            val entry = makeEntry().copy(entryUuid = uuid)

            entry.id() shouldBe uuid.toString()
        }
    }

    describe("BoardEntryType") {

        it("should have all four types") {
            BoardEntryType.entries.size shouldBe 4
        }

        it("should have correct icons") {
            BoardEntryType.BUY.icon shouldBe Material.GOLD_INGOT
            BoardEntryType.SELL.icon shouldBe Material.CHEST
            BoardEntryType.LOOKING_FOR.icon shouldBe Material.PLAYER_HEAD
            BoardEntryType.INFO.icon shouldBe Material.FLOWER_BANNER_PATTERN
        }
    }
})
