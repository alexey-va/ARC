package ru.arc.mounts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.luckperms.api.node.types.PermissionNode
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountDomainTest : StringSpec({
    "catalog preserves the highest unlocked permission level and selected skin" {
        val mount = testMount()
        val subject =
            MountPermissionSubject(UUID.randomUUID(), "Rider") { permission ->
                permission in
                    setOf(
                        mount.levelPermission(1),
                        mount.levelPermission(3),
                        mount.glowPermission,
                        mount.skinPermission("baby"),
                        mount.activeSkinPermission("baby"),
                    )
            }

        TestOwnership().profile(subject, mount) shouldBe
            MountProfile(
                level = 3,
                glowOwned = true,
                glowDisabled = false,
                ownedSkinIds = setOf("baby"),
                activeSkinId = "baby",
            )
    }

    "all mount ownership nodes use the ARC mounts namespace" {
        val mount = testMount()

        mount.levelPermission(2) shouldBe "arc.mounts.bee.2"
        mount.glowPermission shouldBe "arc.mounts.bee.glow"
        mount.glowDisabledPermission shouldBe "arc.mounts.bee.glow.disabled"
        mount.skinPermission("baby") shouldBe "arc.mounts.bee.skin.baby"
        mount.activeSkinPermission("baby") shouldBe "arc.mounts.bee.skin.active.baby"
    }

    "disabled glow permission wins after glow was purchased" {
        val mount = testMount()
        val subject =
            MountPermissionSubject(UUID.randomUUID(), "Rider") { permission ->
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

    "high jump ability scales the walking jump velocity" {
        val abilities =
            MountAbilities(
                highJump = MountHighJumpAbility(displayName = "Высокий прыжок", multiplier = 1.8),
            )

        walkingJumpVelocity(baseVelocity = 0.5, abilities = abilities) shouldBe (0.9 plusOrMinus 1.0e-9)
        walkingJumpVelocity(baseVelocity = 0.5, MountAbilities()) shouldBeExactly 0.5
    }

    "high jump ability rejects unsafe multipliers" {
        shouldThrow<IllegalArgumentException> {
            MountHighJumpAbility(displayName = "Слишком высоко", multiplier = 3.1)
        }
        shouldThrow<IllegalArgumentException> {
            testMount().copy(
                abilities =
                    MountAbilities(
                        MountHighJumpAbility(displayName = "Высокий прыжок", multiplier = 1.8),
                    ),
            )
        }
    }

    "mount definitions reject invalid level prices and speeds" {
        shouldThrow<IllegalArgumentException> {
            testMount().copy(levels = listOf(MountLevelDefinition(speed = 0.0, price = 1.0)))
        }
        shouldThrow<IllegalArgumentException> {
            testMount().copy(levels = listOf(MountLevelDefinition(speed = 1.0, price = -1.0)))
        }
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

internal fun testMount() =
    MountDefinition(
        id = "bee",
        movement = MountMovement.FLYING,
        entityType = "BEE",
        iconMaterial = "BEE_SPAWN_EGG",
        displayName = "Пчела",
        description = listOf("Тестовый маунт"),
        acquisition = "Тест",
        rarity = MountRarity.COMMON,
        levels =
            listOf(
                MountLevelDefinition(0.4, 50_000.0),
                MountLevelDefinition(0.6, 100_000.0),
                MountLevelDefinition(0.9, 5_000_000.0, 1.28, 1.12),
            ),
        glowPrice = 10_000.0,
        skins =
            listOf(
                MountSkinDefinition(
                    id = "baby",
                    displayName = "Малыш",
                    iconMaterial = "BEE_SPAWN_EGG",
                    price = 25_000.0,
                    appearance = MountAppearance(baby = true),
                ),
            ),
    )

private class TestOwnership : MountOwnership {
    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile {
        val level = (1..mount.maxLevel).filter { subject.hasPermission(mount.levelPermission(it)) }.maxOrNull() ?: 0
        val ownedSkins = mount.skins.filter { subject.hasPermission(mount.skinPermission(it.id)) }.mapTo(hashSetOf()) { it.id }
        val active = mount.skins.firstOrNull { it.id in ownedSkins && subject.hasPermission(mount.activeSkinPermission(it.id)) }?.id
        return MountProfile(
            level,
            subject.hasPermission(mount.glowPermission),
            subject.hasPermission(mount.glowDisabledPermission),
            ownedSkins,
            active ?: MountDefinition.DEFAULT_SKIN_ID,
        )
    }

    override fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int) = CompletableFuture.completedFuture<Void>(null)
    override fun revokeLevel(playerId: UUID, mount: MountDefinition, level: Int) = CompletableFuture.completedFuture<Void>(null)
    override fun grantGlow(playerId: UUID, mount: MountDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun revokeGlow(playerId: UUID, mount: MountDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean) = CompletableFuture.completedFuture<Void>(null)
    override fun grantSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun revokeSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition) = CompletableFuture.completedFuture<Void>(null)
    override fun setActiveSkin(playerId: UUID, mount: MountDefinition, skinId: String) = CompletableFuture.completedFuture<Void>(null)
    override fun hasDirectPermission(playerId: UUID, permission: String) = CompletableFuture.completedFuture(false)
    override fun resolveUniqueId(playerName: String) = CompletableFuture.completedFuture<UUID?>(null)
}
