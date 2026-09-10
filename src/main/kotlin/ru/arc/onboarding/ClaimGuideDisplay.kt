package ru.arc.onboarding

import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay

/** Keep the complete anchor until Shift is released; teleport/world events reset the session. */
internal fun claimGuideAnchor(previous: Location?, eye: Location, sneaking: Boolean): Location =
    if (sneaking && previous != null && previous.world == eye.world) previous.clone() else eye.clone()

/** Stop the client interpolation too, not just server-side movement. */
internal fun freezeClaimGuideDisplay(display: TextDisplay?) {
    if (display == null || !display.isValid) return
    display.teleportDuration = 0
    display.teleport(display.location)
}

/** FIXED displays must never inherit the viewer's yaw or pitch. */
internal fun claimGuideBorderOrigin(eye: Location): Location =
    eye.clone().apply { y = claimGuideBorderY(eye.y); yaw = 0f; pitch = 0f }

internal fun claimGuideTeleportDuration(from: Location, to: Location): Int =
    if (from.world != to.world || from.distanceSquared(to) > 64.0) 0 else 2

internal fun followClaimGuideDisplay(display: Display?, to: Location) {
    if (display == null || !display.isValid || display.world != to.world) return
    val from = display.location
    if (from.distanceSquared(to) > 0.0001) {
        display.teleportDuration = claimGuideTeleportDuration(from, to)
        display.teleport(to)
    }
}
