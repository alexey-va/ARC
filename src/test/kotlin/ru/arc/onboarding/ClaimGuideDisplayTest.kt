package ru.arc.onboarding

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import io.mockk.mockk
import org.bukkit.World

class ClaimGuideDisplayTest : StringSpec({
    val world = mockk<World>()
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
})
