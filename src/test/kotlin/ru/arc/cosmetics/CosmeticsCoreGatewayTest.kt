package ru.arc.cosmetics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.backwings.BackWing
import org.bukkit.entity.Player
import java.nio.file.Files

class CosmeticsCoreGatewayTest : FreeSpec({
    "public catalog keeps IDs categories and Russian names while ignoring disabled definitions" {
        val root = Files.createTempDirectory("cosmetics-catalog-")
        try {
            Files.createDirectories(root.resolve("cosmetics/pack"))
            Files.writeString(root.resolve("config.yml"), """
                cosmetics:
                  max_equippable: {body: 3, balloon: 2}
                  worlds: [rc_*, exact]
            """.trimIndent())
            Files.writeString(root.resolve("cosmetics/pack/hats.yml"), """
                cosmetics:
                  chef_hat:
                    display_name: '&fКолпак повара'
                    type: HAT
                  disabled:
                    enabled: false
                    type: UNKNOWN
            """.trimIndent())
            val catalog = CosmeticCatalog.load(root)
            catalog.entries shouldBe listOf(CosmeticEntry("chef_hat", "Колпак повара", CosmeticCategory.HAT))
            catalog.limits[CosmeticCategory.BODY] shouldBe 3
            catalog.permitsWorld("rc_spawn") shouldBe true
            catalog.permitsWorld("exact") shouldBe true
            catalog.permitsWorld("other") shouldBe false
            Files.writeString(root.resolve("cosmetics/pack/duplicate.yml"), """
                cosmetics:
                  chef_hat: {display_name: Duplicate, type: HAT}
            """.trimIndent())
            shouldThrow<IllegalArgumentException> { CosmeticCatalog.load(root) }
        } finally { root.toFile().deleteRecursively() }
    }

    "one permitted action uses the exact command and proves the complete post-state" {
        val f = CosmeticsFixture()
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.APPLIED
        f.commands shouldBe listOf("cosmetics Viewer add chef_hat")
        f.gateway.snapshot(f.player).equipped(CosmeticCategory.HAT).map { it.entry } shouldBe listOf(f.hat)
        f.gateway.change(f.player, f.hat, false) shouldBe CosmeticChange.APPLIED
        f.commands.last() shouldBe "cosmetics Viewer remove chef_hat"
        f.gateway.snapshot(f.player).equipped(CosmeticCategory.HAT) shouldBe emptyList()
    }

    "permission loss fresh item ownership world restriction and category limit reject before dispatch" {
        val f = CosmeticsFixture()
        f.denied += "cosmeticscore.user.cosmetics.wear.${f.hat.id}"
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.NOT_OWNED
        f.denied.clear()
        f.world = "blocked"
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.WORLD
        f.world = "allowed"
        f.wearing += f.otherHat.id
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.LIMIT
        f.hasWingItem = false
        f.gateway.change(f.player, f.wing, true) shouldBe CosmeticChange.NOT_OWNED
        f.commands shouldBe emptyList()
    }

    "an already equipped item can be removed after permission loss or leaving permitted worlds" {
        val f = CosmeticsFixture()
        f.wearing += f.hat.id
        f.denied += "cosmeticscore.user.cosmetics.wear.${f.hat.id}"
        f.denied += "cosmeticscore.user.cosmetics.seeingui.${f.hat.id}"
        f.world = "blocked"
        f.gateway.change(f.player, f.hat, false) shouldBe CosmeticChange.APPLIED
        f.wearing shouldBe emptySet()
    }

    "unresolved PAPI unknown active cosmetics and absent menu permission fail closed" {
        val f = CosmeticsFixture()
        f.unresolved = true
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.UNAVAILABLE
        f.unresolved = false
        f.unknownBody = true
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.UNAVAILABLE
        f.unknownBody = false
        f.denied += CosmeticsCoreGateway.MENU_PERMISSION
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.UNAVAILABLE
        f.commands shouldBe emptyList()
    }

    "changed target state never turns an old equip click into removal" {
        val f = CosmeticsFixture()
        f.wearing += f.hat.id
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.CHANGED
        f.commands shouldBe emptyList()
        f.wearing shouldBe setOf(f.hat.id)
    }

    "successful dispatch without state change is not reported as applied" {
        val f = CosmeticsFixture()
        f.applyCommand = false
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.UNCONFIRMED
        f.commands.size shouldBe 1
        f.gateway.snapshot(f.player).equipped(CosmeticCategory.HAT) shouldBe emptyList()
    }

    "unrelated provider changes are visible and never hidden by a false success claim" {
        val f = CosmeticsFixture()
        f.extraChange = { f.wearing += f.wing.id }
        f.gateway.change(f.player, f.hat, true) shouldBe CosmeticChange.UNCONFIRMED
        f.wearing shouldBe setOf(f.hat.id, f.wing.id)
    }

    "switching item-backed wings frees their slot and preserves the unrelated backpack and inventory" {
        val f = CosmeticsFixture(bodyLimit = 2)
        f.wearing += setOf(f.dread.id, f.backpack.id)
        f.gateway.change(f.player, f.wing, true) shouldBe CosmeticChange.APPLIED
        f.wearing shouldBe setOf(f.wing.id, f.backpack.id)
        f.commands shouldBe listOf("cosmetics Viewer remove ${f.dread.id}", "cosmetics Viewer add ${f.wing.id}")
        // The mutation layer receives only a read-only entitlement predicate, never transfers an item.
        verify(exactly = 0) { f.player.inventory }
    }

    "an unconfirmed old-wing removal stops replacement before equipping another wing" {
        val f = CosmeticsFixture()
        f.wearing += setOf(f.dread.id, f.backpack.id)
        f.applyCommand = false
        f.gateway.change(f.player, f.wing, true) shouldBe CosmeticChange.UNCONFIRMED
        f.commands shouldBe listOf("cosmetics Viewer remove ${f.dread.id}")
        f.wearing shouldBe setOf(f.dread.id, f.backpack.id)
    }
})

private class CosmeticsFixture(bodyLimit: Int = 3) {
    val hat = CosmeticEntry("chef_hat", "Колпак повара", CosmeticCategory.HAT)
    val otherHat = CosmeticEntry("cowboy_hat", "Ковбойская шляпа", CosmeticCategory.HAT)
    val wing = CosmeticEntry(BackWing.GALAXY.cosmeticId, "Галактические Крылья", CosmeticCategory.BODY)
    val dread = CosmeticEntry(BackWing.DREADKNIGHT.cosmeticId, "Крылья Рыцаря Ужаса", CosmeticCategory.BODY)
    val backpack = CosmeticEntry("backpack", "Рюкзак", CosmeticCategory.BODY)
    val entries = listOf(hat, otherHat, wing, dread, backpack)
    val wearing = mutableSetOf<String>()
    val denied = mutableSetOf<String>()
    val commands = mutableListOf<String>()
    var world = "allowed"
    var hasWingItem = true
    var unresolved = false
    var unknownBody = false
    var applyCommand = true
    var extraChange: () -> Unit = {}
    val player = mockk<Player> {
        every { name } returns "Viewer"
        every { isOnline } returns true
        every { isDead } returns false
        every { hasPermission(any<String>()) } answers { firstArg<String>() !in denied }
        every { this@mockk.world.name } answers { this@CosmeticsFixture.world }
    }
    val gateway = CosmeticsCoreGateway(
        CosmeticCatalog(entries, mapOf(CosmeticCategory.HAT to 1, CosmeticCategory.BODY to bodyLimit, CosmeticCategory.BALLOON to 2), listOf("allowed")),
        { true },
        { _, token ->
            if (unresolved) token else {
                val category = CosmeticCategory.entries.firstOrNull { token == "%cosmetics_has_equipped_category_${it.key}%" }
                if (category == null) wearing.contains(token.removePrefix("%cosmetics_has_equipped_").removeSuffix("%")).toString()
                else (entries.any { it.category == category && it.id in wearing } || category == CosmeticCategory.BODY && unknownBody).toString()
            }
        },
        { command ->
            commands += command
            if (applyCommand) {
                val parts = command.split(' ')
                if (parts[2] == "add") wearing += parts[3] else wearing -= parts[3]
            }
            extraChange()
            true
        },
        { _, _ -> hasWingItem },
    )
}
