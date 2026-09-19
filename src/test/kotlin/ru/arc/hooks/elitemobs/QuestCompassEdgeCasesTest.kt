package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.boss.BossBar as NativeBossBar
import java.util.UUID

class QuestCompassEdgeCasesTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()
    val emptyNative = "-".repeat(63)

    fun text(yaw: Float, native: String = emptyNative): String =
        plain.serialize(QuestCompassRenderer.render(yaw, native))

    fun player(online: Boolean = true): Player = mockk(relaxed = true) {
        every { isOnline } returns online
        every { location } returns Location(null, 0.0, 0.0, 0.0)
    }

    fun native(player: Player): NativeBossBar = mockk(relaxed = true) {
        every { isVisible } returns true
        every { title } returns emptyNative
        every { players } returns listOf(player)
    }

    "empty tracking map performs no player lookup or presentation" {
        val player = player()
        var lookups = 0
        val compass = QuestCompass({ emptyMap() }, {
            lookups++
            player
        })

        compass.refresh()

        lookups shouldBe 0
        verify(exactly = 0) { player.showBossBar(any()) }
        verify(exactly = 0) { player.hideBossBar(any()) }
        compass.close()
    }

    "offline player is removed without hiding or exposing a native bar" {
        val id = UUID.randomUUID()
        val player = player(online = false)
        val native = native(player)
        val compass = QuestCompass({ mapOf(id to native) }, { player })

        compass.refresh()

        verify(exactly = 0) { player.showBossBar(any()) }
        verify(exactly = 0) { player.hideBossBar(any()) }
        verify(exactly = 0) { native.isVisible = false }
        compass.close()
    }

    "lookup failure restores fallback without mutating an undiscovered native bar" {
        val warnings = mutableListOf<String>()
        val compass = QuestCompass({ error("tracker lookup failed") }, warn = warnings::add)

        compass.refresh()
        compass.refresh()

        warnings.size shouldBe 1
    }

    "same native string still follows player yaw" {
        val south = text(0f)
        val west = text(90f)

        south shouldNotBe west
        south[37] shouldBe 'S'
        west[37] shouldBe 'W'
        south.length shouldBe west.length
    }

    "blank loading and no-destination native fallbacks stay compact" {
        text(0f, "") shouldBe "< Поиск цели… >"
        text(0f, "Waiting for the dungeon to start") shouldBe "< Ожидание старта данжа >"
        text(0f, "[EM] No quest destination found!") shouldBe "< Цель пока не найдена >"
    }

    "different-world fallback preserves the useful world identifier" {
        val rendered = text(0f, "[EM] Go to world dungeon_instance_123!")

        rendered.contains("dungeon_instance_123") shouldBe true
    }

    "behind or out-of-view target follows native no-marker contract" {
        val rendered = text(0f, emptyNative)

        rendered.contains('◇') shouldBe false
        rendered.contains('☠') shouldBe false
        rendered.contains('⚔') shouldBe false
        rendered.length shouldBe QuestCompassRenderer.WIDTH + 2
    }
})
