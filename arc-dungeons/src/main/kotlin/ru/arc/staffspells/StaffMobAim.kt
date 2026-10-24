package ru.arc.staffspells

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.sqrt

private const val MAX_MOB_AIM_TICKS = 80

/** Horizontal lead for one fixed launch heading, capped at three blocks and the supplied total lead horizon. */
internal fun staffMobPredictedAim(
    spell: StaffSpell,
    muzzle: Location,
    bodyPoint: Location,
    movement: Vector,
    range: Double,
    maxLeadTicks: Int = 12,
    launchDelayTicks: Int = 4,
): Location {
    val aim = bodyPoint.clone()
    if (spell != StaffSpell.EMBER && spell != StaffSpell.ICICLE && spell != StaffSpell.LANCE) return aim
    if (muzzle.world != bodyPoint.world || !range.isFinite() || range <= 0.0 || maxLeadTicks <= 0) return aim
    val reach = range.coerceAtMost(32.0)
    val offset = bodyPoint.toVector().subtract(muzzle.toVector())
    val distanceSquared = offset.lengthSquared()
    val stepSquared = movement.lengthSquared()
    if (!distanceSquared.isFinite() || distanceSquared > reach * reach ||
        !stepSquared.isFinite() || stepSquared > 1.0) return aim
    val horizontalStep = sqrt(movement.x * movement.x + movement.z * movement.z)
    if (horizontalStep == 0.0) return aim
    val leadLimit = minOf(maxLeadTicks.coerceAtMost(MAX_MOB_AIM_TICKS).toDouble(), 3.0 / horizontalStep)
    val delay = launchDelayTicks.coerceIn(0, MAX_MOB_AIM_TICKS)
    var travelled = 0.0
    for (age in 1..MAX_MOB_AIM_TICKS) {
        travelled = minOf(reach, when (spell) {
            StaffSpell.EMBER -> travelled + staffEmberStep(age, .5)
            StaffSpell.ICICLE -> travelled + minOf(1.2, staffIcicleStep(age))
            StaffSpell.LANCE -> if (age < StaffSpellDisplayEffects.FRAME_TICKS + 4) 0.0
                else staffLanceFront(age - StaffSpellDisplayEffects.FRAME_TICKS, reach)
            else -> return aim
        })
        val leadTicks = minOf(leadLimit, (age + delay).toDouble())
        val leadX = movement.x * leadTicks
        val leadZ = movement.z * leadTicks
        val dx = offset.x + leadX
        val dz = offset.z + leadZ
        if (dx * dx + offset.y * offset.y + dz * dz <= travelled * travelled)
            return aim.add(leadX, 0.0, leadZ)
        if (travelled >= reach) break
    }
    return aim
}
