package ru.arc.onboarding

import org.bukkit.Location
import org.bukkit.entity.TextDisplay

/** Freeze aiming direction, not the player's world position. Large moves release the aim lock. */
internal fun claimGuideAnchor(previous: Location?, eye: Location, sneaking: Boolean): Location =
    eye.clone().apply {
        if (sneaking && previous != null && previous.world == eye.world && previous.distanceSquared(eye) <= 4.0) {
            yaw = previous.yaw
            pitch = previous.pitch
        }
    }

/** FIXED displays must never inherit the viewer's yaw or pitch. */
internal fun claimGuideBorderOrigin(eye: Location): Location =
    eye.clone().apply { y = claimGuideBorderY(eye.y); yaw = 0f; pitch = 0f }

internal fun claimGuideTeleportDuration(from: Location, to: Location): Int =
    if (from.world != to.world || from.distanceSquared(to) > 1.0) 0 else 2

internal fun followClaimGuideDisplay(display: TextDisplay?, to: Location) {
    if (display == null || !display.isValid || display.world != to.world) return
    val from = display.location
    display.teleportDuration = claimGuideTeleportDuration(from, to)
    if (from.distanceSquared(to) > 0.0001) display.teleport(to)
}
