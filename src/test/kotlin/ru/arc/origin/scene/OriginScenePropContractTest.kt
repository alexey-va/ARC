package ru.arc.origin.scene

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.floats.shouldBeExactly
import org.bukkit.Material

class OriginScenePropContractTest : StringSpec({
    "surface props rest on the anchor instead of straddling it" {
        val resolved = OriginScenePropContract.resolve(
            anchor = OriginScenePoint(78.5, 71.04, 67.5),
            origin = OriginScenePropOrigin.BOTTOM_CENTER,
            offset = OriginSceneVector(0.0, 0.0, 0.0),
            scale = OriginSceneVector(0.46, 0.11, 0.30),
        )

        resolved.x.shouldBeExactly(78.5)
        resolved.y.shouldBeExactly(71.04)
        resolved.z.shouldBeExactly(67.5)
        resolved.translationX.shouldBeExactly(-0.23f)
        resolved.translationY.shouldBeExactly(0.0f)
        resolved.translationZ.shouldBeExactly(-0.15f)
    }

    "block surfaces derive the support plane from the live block" {
        val surface = OriginScenePropSurface(
            near = OriginScenePoint(79.1, 70.65, 67.8),
            materials = setOf(Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL),
            searchRadius = 2,
            topOffset = 1.04,
        )

        val resolved = surface.pointFor(blockX = 78, blockY = 70, blockZ = 67)
        resolved.x.shouldBeExactly(78.5)
        resolved.y.shouldBeExactly(71.04)
        resolved.z.shouldBeExactly(67.5)
    }

    "work surfaces expose a lower look target than their display support plane" {
        val surface = OriginScenePropSurface(
            near = OriginScenePoint(79.1, 70.65, 67.8),
            materials = setOf(Material.ANVIL),
            searchRadius = 2,
            topOffset = 1.04,
            lookTargetOffsetY = -1.15,
        )

        val target = surface.lookTarget(OriginScenePoint(78.5, 71.04, 67.5))
        target.y.shouldBeExactly(69.89)
    }

    "rotated strokes keep their geometric center pinned to the configured offset" {
        val resolved = OriginScenePropContract.resolve(
            anchor = OriginScenePoint(78.5, 71.04, 67.5),
            origin = OriginScenePropOrigin.BOTTOM_CENTER,
            offset = OriginSceneVector(-0.2, 0.0, 0.0),
            scale = OriginSceneVector(0.1, 0.05, 0.2),
            rotationYDegrees = 90f,
        )

        resolved.x.shouldBeExactly(78.3)
        resolved.y.shouldBeExactly(71.04)
        resolved.z.shouldBeExactly(67.5)
        resolved.translationX.shouldBeExactly(-0.1f)
        resolved.translationY.shouldBeExactly(0.0f)
        resolved.translationZ.shouldBeExactly(0.05f)
    }

    "display lifecycle rejects removal before creation" {
        shouldThrow<IllegalArgumentException> {
            OriginScenePropContract.validateLifecycle(listOf(OriginSceneStep.RemoveDisplay("missing")))
        }
    }

    "display lifecycle rejects a prop left visible at the end" {
        shouldThrow<IllegalArgumentException> {
            OriginScenePropContract.validateLifecycle(
                listOf(
                    OriginSceneStep.BlockDisplay(
                        key = "unfinished",
                        surface = "surface",
                        anchor = null,
                        material = "IRON_BLOCK",
                        origin = OriginScenePropOrigin.BOTTOM_CENTER,
                        offset = OriginSceneVector.ZERO,
                        scale = OriginSceneVector(0.5, 0.1, 0.3),
                        rotationYDegrees = 0f,
                        interpolationTicks = 0,
                    ),
                ),
            )
        }
    }
})
