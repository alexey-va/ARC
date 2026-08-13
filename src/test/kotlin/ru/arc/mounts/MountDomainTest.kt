package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import net.luckperms.api.node.types.PermissionNode

class MountDomainTest : StringSpec({
    "catalog preserves the highest unlocked permission level" {
        val mount = testMount()
        val subject =
            MountPermissionSubject(java.util.UUID.randomUUID(), "Rider") { permission ->
                permission in setOf(mount.levelPermission(1), mount.levelPermission(3), mount.glowPermission)
            }
        val ownership = TestOwnership()

        ownership.profile(subject, mount) shouldBe MountProfile(level = 3, glowOwned = true, glowDisabled = false)
    }

    "disabled glow permission wins after glow was purchased" {
        val mount = testMount()
        val subject =
            MountPermissionSubject(java.util.UUID.randomUUID(), "Rider") { permission ->
                permission == mount.levelPermission(1) ||
                    permission == mount.glowPermission ||
                    permission == mount.glowDisabledPermission
            }

        TestOwnership().profile(subject, mount).glowEnabled shouldBe false
    }

    "a wildcard is not treated as the direct glow-disabled setting" {
        val disabled = testMount().glowDisabledPermission

        hasDirectPositivePermission(listOf(permissionNode("*")), disabled) shouldBe false
        hasDirectPositivePermission(listOf(permissionNode(disabled)), disabled) shouldBe true
    }

    "planar input is normalized so diagonal movement is not faster" {
        val straight = MountMotion.planarDirection(0f, MountInputState(forward = true))
        val diagonal = MountMotion.planarDirection(0f, MountInputState(forward = true, right = true))

        straight.horizontalLength shouldBe (1.0 plusOrMinus 1.0e-9)
        diagonal.horizontalLength shouldBe (1.0 plusOrMinus 1.0e-9)
        straight.z.shouldBeExactly(1.0)
    }

    "yaw interpolation takes the shortest path across 360 degrees" {
        MountMotion.smoothYaw(350f, 10f, 0.5) shouldBe 360f
    }

    "airborne controls use space to ascend and shift to descend" {
        val planar = MountMotion.planarDirection(0f, MountInputState(forward = true))
        val ascending = airborne(MountInputState(forward = true, jump = true), planar)
        val descending = airborne(MountInputState(forward = true, sneak = true), planar)

        (ascending.y > 0.0) shouldBe true
        (descending.y < 0.0) shouldBe true
        (ascending.length <= 1.0 + 1.0e-9) shouldBe true
        (descending.length <= 1.0 + 1.0e-9) shouldBe true
    }

    "flight pitch adds vertical intent while preserving the speed cap" {
        val input = MountInputState(forward = true)
        val planar = MountMotion.planarDirection(0f, input)
        val climbing = airborne(input, planar, pitch = -45f)

        (climbing.y > 0.0) shouldBe true
        (climbing.length <= 1.0 + 1.0e-9) shouldBe true
    }

    "double sneak requires two rising edges inside the window" {
        val gesture = DoubleSneakGesture(450)

        gesture.update(true, 1_000) shouldBe SneakGestureResult.PRESSED
        gesture.update(true, 1_100) shouldBe SneakGestureResult.NONE
        gesture.update(false, 1_150) shouldBe SneakGestureResult.NONE
        gesture.update(true, 1_400) shouldBe SneakGestureResult.DOUBLE_PRESSED
    }

    "mount definitions reject invalid prices and speeds" {
        shouldThrow<IllegalArgumentException> {
            testMount().copy(speeds = listOf(0.0))
        }
        shouldThrow<IllegalArgumentException> {
            testMount().copy(prices = listOf(-1.0))
        }
    }

    "legacy glow ownership remains readable and respects the new disabled node" {
        val mount = testMount()
        val delegate = TestOwnership()
        val ownership = LegacyAwareMountOwnership(delegate) { mapOf("rider" to setOf("bee")) }
        val enabled = MountPermissionSubject(java.util.UUID.randomUUID(), "Rider") { false }
        val disabled = MountPermissionSubject(java.util.UUID.randomUUID(), "Rider") {
            it == mount.glowDisabledPermission
        }

        ownership.profile(enabled, mount) shouldBe MountProfile(0, glowOwned = true, glowDisabled = false)
        ownership.profile(disabled, mount) shouldBe MountProfile(0, glowOwned = true, glowDisabled = true)
    }
})

private fun airborne(input: MountInputState, planar: MotionVector, pitch: Float = 0f) =
    MountMotion.airborneTarget(
        pitchDegrees = pitch,
        input = input,
        planar = planar,
        maximumSpeed = 1.0,
        verticalSpeedRatio = 0.75,
        maximumVerticalSpeed = 0.5,
        pitchInfluence = 0.65,
    )

private fun permissionNode(permissionName: String): PermissionNode =
    mockk {
        every { permission } returns permissionName
        every { value } returns true
    }

private fun testMount() =
    MountDefinition(
        id = "bee",
        movement = MountMovement.FLYING,
        entityType = "BEE",
        iconMaterial = "BEE_SPAWN_EGG",
        displayName = "Пчела",
        speeds = listOf(0.4, 0.6, 0.9),
        prices = listOf(50_000.0, 100_000.0, 500_000.0),
        glowPrice = 10_000.0,
    )

private class TestOwnership : MountOwnership {
    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile {
        val level = (1..mount.maxLevel).filter { subject.hasPermission(mount.levelPermission(it)) }.maxOrNull() ?: 0
        val glow = subject.hasPermission(mount.glowPermission)
        return MountProfile(level, glow, subject.hasPermission(mount.glowDisabledPermission))
    }

    override fun grantLevel(playerId: java.util.UUID, mount: MountDefinition, level: Int) =
        java.util.concurrent.CompletableFuture.completedFuture<Void>(null)

    override fun grantGlow(playerId: java.util.UUID, mount: MountDefinition) =
        java.util.concurrent.CompletableFuture.completedFuture<Void>(null)

    override fun setGlowEnabled(playerId: java.util.UUID, mount: MountDefinition, enabled: Boolean) =
        java.util.concurrent.CompletableFuture.completedFuture<Void>(null)

    override fun resolveUniqueId(playerName: String) =
        java.util.concurrent.CompletableFuture.completedFuture<java.util.UUID?>(null)
}
