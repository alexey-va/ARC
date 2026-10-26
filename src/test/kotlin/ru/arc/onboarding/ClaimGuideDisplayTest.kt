package ru.arc.onboarding

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.bukkit.World
import org.bukkit.entity.BlockDisplay

class ClaimGuideDisplayTest : StringSpec({
    val world = mockk<World>()
    "only a positive finite quote gets a price row with a white coin" {
        val template = MiniMessage.miniMessage().deserialize(OnboardingConfig.CLAIM_TEXT.getValue("cost"))
        for (cost in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            claimGuideCostText(template, cost) shouldBe null
        }
        val plain = PlainTextComponentSerializer.plainText()
        val paid = requireNotNull(claimGuideCostText(template, 1250.5))
        plain.serialize(paid) shouldBe "Стоимость: 1250.5 💰"
        fun textParts(component: Component): List<TextComponent> =
            listOfNotNull(component as? TextComponent) + component.children().flatMap(::textParts)
        textParts(paid).single { it.content() == "💰" }.color() shouldBe NamedTextColor.WHITE
        plain.serialize(requireNotNull(claimGuideCostText(template, 0.001))) shouldBe "Стоимость: 0.001 💰"
    }

    "aiming at the action panel changes only glow and restores it without removing contours" {
        var glowing = true
        val display = mockk<BlockDisplay>(relaxed = true)
        every { display.isValid } returns true
        every { display.isGlowing } answers { glowing }
        every { display.isGlowing = any() } answers { glowing = firstArg() }
        setClaimGuideGlow(listOf(display), false)
        glowing shouldBe false
        setClaimGuideGlow(listOf(display), false)
        verify(exactly = 1) { display.isGlowing = false }
        setClaimGuideGlow(listOf(display), true)
        glowing shouldBe true
        verify(exactly = 1) { display.isGlowing = true }
        verify(exactly = 0) { display.remove() }
    }
    "fixed borders discard every camera rotation without moving their world position" {
        for (yaw in listOf(-170f, 30f, 90f)) for (pitch in listOf(-80f, 15f, 80f)) {
            val eye = Location(world, -10.5, 70.62, 32.5, yaw, pitch)
            claimGuideBorderOrigin(eye) shouldBe Location(world, -10.5, 69.62, 32.5, 0f, 0f)
            eye.yaw shouldBe yaw
            eye.pitch shouldBe pitch
        }
    }
    "sneaking freezes the complete pose through walking crouching and turning until released" {
        val previous = Location(world, 0.0, 70.0, 0.0, 25f, 10f)
        val walking = Location(world, 1.0, 70.0, 0.0, 70f, 30f)
        claimGuideAnchor(previous, walking, true) shouldBe previous
        claimGuideAnchor(previous, walking.clone().subtract(0.0, 0.35, 0.0), true) shouldBe previous
        claimGuideAnchor(previous, walking, false) shouldBe walking
        val teleported = walking.clone().add(100.0, 0.0, 0.0)
        claimGuideAnchor(previous, teleported, true) shouldBe previous
        claimGuideAnchor(null, teleported, true) shouldBe teleported
        val otherWorld = teleported.clone().apply { this.world = mockk<World>() }
        claimGuideAnchor(previous, otherWorld, true) shouldBe otherWorld
        claimGuideTeleportDuration(previous, otherWorld) shouldBe 0
    }
    "lagging holograms snap while nearby movement keeps short interpolation" {
        val from = Location(world, 0.0, 70.0, 0.0)
        claimGuideTeleportDuration(from, from.clone().add(0.2, 0.0, 0.0)) shouldBe 2
        claimGuideTeleportDuration(from, from.clone().add(2.0, 0.0, 0.0)) shouldBe 2
        claimGuideTeleportDuration(from, from.clone().add(8.0, 0.0, 0.0)) shouldBe 2
        claimGuideTeleportDuration(from, from.clone().add(8.1, 0.0, 0.0)) shouldBe 0
        claimGuideTeleportDuration(from, from.clone().add(0.0, 100.0, 0.0)) shouldBe 0
    }
    "view controls expose stable snapping radius colors and wide bounded offsets" {
        ClaimGuideView().gridOffset shouldBe 0.0
        ClaimGuideView().snapBlocks shouldBe 1
        ClaimGuideView().gridRadius shouldBe 2
        ClaimGuideView().showPosts shouldBe false
        ClaimGuideView().adjust(-100, 100) shouldBe ClaimGuideView(gridSteps = -12, labelSteps = 20)
        ClaimGuideView(gridSteps = -12, labelSteps = 20).adjust(1, -1) shouldBe
            ClaimGuideView(gridSteps = -11, labelSteps = 19)
        ClaimGuideView().adjust(0, 0, radius = 100).gridRadius shouldBe 5
        ClaimGuideView().adjust(0, 0, cycleSnap = true).snapBlocks shouldBe 2
        ClaimGuideView(snapBlocks = 3).adjust(0, 0, cycleSnap = true).snapBlocks shouldBe 0
        ClaimGuideView().adjust(0, 0, cycleColor = true).gridColor shouldBe ClaimGuideGridColor.WHITE
        ClaimGuideView().adjust(0, 0, togglePosts = true).showPosts shouldBe true
    }
})
