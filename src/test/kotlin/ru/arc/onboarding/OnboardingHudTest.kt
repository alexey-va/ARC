package ru.arc.onboarding

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.menu.DialogTextLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class OnboardingHudTest : FreeSpec({
    val firstRtp = setOf(OnboardingMilestone.FIRST_RTP)
    val home = firstRtp + OnboardingMilestone.HOME_CREATED
    val foothold = home + setOf(OnboardingMilestone.LAND_CLAIMED, OnboardingMilestone.FOOTHOLD_COMPLETE)
    fun view(milestones: Set<OnboardingMilestone> = emptySet()) = OnboardingGuideView(
        OnboardingQuestView("run", true, milestones), OnboardingPlaytime.LIMIT_TICKS,
    )
    fun plain(text: Component) = PlainTextComponentSerializer.plainText().serialize(text)

    "the current goal and actual inventory choose one concrete instruction that fits the screen" {
        data class Case(val id: String, val guide: OnboardingGuideView, val claim: Boolean, val book: Boolean, val expected: String)
        val cases = listOf(
            Case("travel", view(), false, false, "Выберите мир у Проводника"),
            Case("home", view(firstRtp), false, false, "/cmi sethome — сохранить точку дома"),
            Case("move-home", view(home + OnboardingMilestone.LAND_CLAIMED), false, false, "/cmi sethome — перенести точку в свой приват"),
            Case("claim-kit", view(home), false, false, "/kit start — получить блок привата"),
            Case("claim", view(home), true, false, "ПКМ блоком привата по земле — защитить участок"),
            Case("build-kit", view(foothold), false, false, "/kit start — получить книгу дома"),
            Case("build-book", view(foothold), false, true, "ПКМ книгой строительства по земле"),
            Case("build-preview-no-book", view(foothold + OnboardingMilestone.BUILD_BOOK_OPENED), false, false, "/kit start — получить книгу дома"),
            Case("build-preview-book", view(foothold + OnboardingMilestone.BUILD_BOOK_OPENED), false, true, "ПКМ книгой строительства по земле"),
            Case("building", view(foothold + OnboardingMilestone.AUTOBUILD_STARTED), false, false, "Дом строится — дождитесь завершения"),
        )
        val serializer = LegacyComponentSerializer.builder().character('&').hexColors().build()
        val export = cases.associate { case ->
            val title = requireNotNull(onboardingHudTitle(case.guide, case.claim, case.book))
            plain(title) shouldBe case.expected
            val width = plain(title).sumOf(DialogTextLayout::glyphWidth)
            (width <= 300) shouldBe true
            val styled = serializer.serialize(title)
            styled shouldContain "&#77ee94"
            styled shouldContain "&#e8dfd2"
            case.id to mapOf("title" to styled, "width" to width, "color" to "green", "progress" to 1)
        }
        onboardingHudTitle(view().copy(quest = view().quest.copy(accepted = false))) shouldBe null
        onboardingHudTitle(view(OnboardingMilestone.entries.toSet())) shouldBe null
        onboardingHudTitle(view().copy(remainingTicks = 0)) shouldBe null
        val path = Path.of("build/reports/onboarding-hud/content.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(export))
    }

    "one owned bar changes its title in place and is removed on every guide exit" {
        val player = hudPlayer()
        val shown = mutableListOf<BossBar>()
        every { player.showBossBar(capture(shown)) } returns Unit
        val hud = OnboardingHud()
        hud.refresh(player, null)
        hud.refresh(player, view().copy(quest = view().quest.copy(accepted = false)))
        shown.size shouldBe 0
        hud.refresh(player, view(firstRtp))
        hud.refresh(player, view(firstRtp))
        shown.size shouldBe 1
        hud.refresh(player, view(foothold + OnboardingMilestone.AUTOBUILD_STARTED))
        shown.size shouldBe 1
        plain(shown.single().name()) shouldBe "Дом строится — дождитесь завершения"
        hud.refresh(player, view(OnboardingMilestone.entries.toSet()))
        verify(exactly = 1) { player.hideBossBar(shown[0]) }
        hud.refresh(player, view(firstRtp))
        hud.refresh(player, view(firstRtp).copy(remainingTicks = 0))
        verify(exactly = 1) { player.hideBossBar(shown[1]) }
        hud.refresh(player, view(firstRtp))
        hud.clear(player.uniqueId)
        hud.clear(player.uniqueId)
        verify(exactly = 1) { player.hideBossBar(shown[2]) }
        hud.refresh(player, view(firstRtp))
        hud.close()
        hud.close()
        verify(exactly = 1) { player.hideBossBar(shown[3]) }
        verify(exactly = 0) { player.inventory }
    }

    "inventory hints use durable item markers and reject another player's claim block" {
        val player = hudPlayer()
        val shown = mutableListOf<BossBar>()
        every { player.showBossBar(capture(shown)) } returns Unit
        val ownBlock = hudItem(Material.EMERALD_ORE, claimOwner = player.uniqueId.toString(), claimRadius = 0)
        val foreignBlock = hudItem(Material.EMERALD_ORE, claimOwner = UUID.randomUUID().toString(), claimRadius = 0)
        val book = hudItem(Material.BOOK, bookId = "starter-home.schem")
        OnboardingHud().use { hud ->
            every { player.inventory.contents } returns arrayOf(foreignBlock)
            hud.refresh(player, view(home))
            plain(shown.single().name()) shouldBe "/kit start — получить блок привата"
            every { player.inventory.contents } returns arrayOf(ownBlock)
            hud.refresh(player, view(home))
            plain(shown.single().name()) shouldBe "ПКМ блоком привата по земле — защитить участок"
            every { player.inventory.contents } returns arrayOf(book)
            hud.refresh(player, view(foothold))
            plain(shown.single().name()) shouldBe "ПКМ книгой строительства по земле"
            every { player.inventory.contents } returns arrayOf(hudItem(Material.BOOK))
            hud.refresh(player, view(foothold))
            plain(shown.single().name()) shouldBe "/kit start — получить книгу дома"
        }
        isOnboardingBuildBook(hudItem(Material.STONE, bookId = "starter-home.schem")) shouldBe false
        isOnboardingBuildBook(hudItem(Material.BOOK, bookId = " ")) shouldBe false
        isOnboardingBuildBook(hudItem(Material.BOOK, bookId = "starter-home.schem", bookSchema = null)) shouldBe false
        isOnboardingBuildBook(hudItem(Material.BOOK, bookId = "starter-home.schem", bookSchema = 7)) shouldBe false
    }
})

private fun hudPlayer() = mockk<Player>(relaxed = true) {
    every { uniqueId } returns UUID.randomUUID()
    every { isOnline } returns true
    every { isDead } returns false
}

private fun hudItem(material: Material, bookId: String? = null, claimOwner: String? = null, claimRadius: Int? = null, bookSchema: Int? = 6): ItemStack {
    val data = mockk<PersistentDataContainer>()
    every { data.get(NamespacedKey("arc", "build_book_id"), PersistentDataType.STRING) } returns bookId
    every { data.get(NamespacedKey("arc", "build_book_schema"), PersistentDataType.INTEGER) } returns bookSchema
    every { data.get(NamespacedKey("lands", "type"), PersistentDataType.STRING) } returns if (claimRadius != null) "CLAIM_BLOCK" else null
    every { data.get(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER) } returns claimRadius
    every { data.get(NamespacedKey("lands", "o"), PersistentDataType.STRING) } returns claimOwner
    val meta = mockk<ItemMeta> { every { persistentDataContainer } returns data }
    return mockk {
        every { type } returns material
        every { hasItemMeta() } returns true
        every { itemMeta } returns meta
    }
}
