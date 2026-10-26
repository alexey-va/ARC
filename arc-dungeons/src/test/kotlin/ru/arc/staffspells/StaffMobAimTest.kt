package ru.arc.staffspells

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.abs

class StaffMobAimTest : FreeSpec({
    val projectiles = listOf(StaffSpell.EMBER, StaffSpell.ICICLE, StaffSpell.LANCE)
    val muzzle = Location(null, 0.0, 65.4, 0.0)

    "stationary targets and fixed ground marks keep their exact point in a fresh location" {
        val body = Location(null, .4, 65.7, 6.0, 70f, -12f)
        (projectiles + StaffSpell.MARK).forEach { spell ->
            val aim = staffMobPredictedAim(spell, muzzle, body, Vector(), 16.0)
            aim shouldBe body
            (aim !== body) shouldBe true
        }
        staffMobPredictedAim(StaffSpell.MARK, muzzle, body, Vector(.2, 0.0, .1), 16.0) shouldBe body
    }

    "a fixed predicted shot passes closer to a walking strafe than its stale point" {
        val body = Location(null, 0.0, 65.4, 6.0)
        val step = Vector(.1, 0.0, 0.0)
        projectiles.forEach { spell ->
            val aim = staffMobPredictedAim(spell, muzzle, body, step, 16.0)
            withClue(spell) {
                (aim.x > body.x) shouldBe true
                (staffMobStrafeMiss(spell, muzzle, aim, body, step, 16.0, 4) <
                    staffMobStrafeMiss(spell, muzzle, body, body, step, 16.0, 4)) shouldBe true
            }
        }
    }

    "arrival lead uses Ember acceleration Icicle pacing and the Lance collision delay" {
        val body = Location(null, 0.0, 65.4, 1.0)
        val step = Vector(.01, 0.0, 0.0)
        mapOf(StaffSpell.EMBER to 3, StaffSpell.ICICLE to 2, StaffSpell.LANCE to 6).forEach { (spell, flightTicks) ->
            listOf(0, 4).forEach { delay ->
                val aim = staffMobPredictedAim(spell, muzzle, body, step, 16.0, launchDelayTicks = delay)
                withClue("$spell with launch delay $delay") {
                    (abs(aim.x - .01 * (flightTicks + delay)) < 1e-9) shouldBe true
                }
            }
        }
    }

    "default aim reaches sprinting lateral targets beyond the old three block lead" {
        val body = Location(null, 0.0, 65.4, 12.0)
        val step = Vector(.28, 0.0, 0.0)
        val aim = staffMobPredictedAim(StaffSpell.EMBER, muzzle, body, step, 16.0)
        (aim.x > 3.0) shouldBe true
        (aim.x <= 5.0) shouldBe true
        (staffMobStrafeMiss(StaffSpell.EMBER, muzzle, aim, body, step, 16.0, 2) <
            staffMobStrafeMiss(StaffSpell.EMBER, muzzle, body, body, step, 16.0, 2)) shouldBe true
    }

    "lead caps both its total horizon and horizontal distance while preserving inputs" {
        val body = Location(null, 0.0, 65.4, 8.0)
        val step = Vector(.3, .2, .4)
        val originalMuzzle = muzzle.clone()
        val originalBody = body.clone()
        val originalStep = step.clone()
        projectiles.forEach { spell ->
            val short = staffMobPredictedAim(spell, muzzle, body, step, 32.0,
                maxLeadTicks = 2, launchDelayTicks = Int.MAX_VALUE)
            (abs(short.x - .6) < 1e-9) shouldBe true
            (abs(short.z - 8.8) < 1e-9) shouldBe true
            val capped = staffMobPredictedAim(spell, muzzle, body, step, 32.0,
                maxLeadTicks = Int.MAX_VALUE, launchDelayTicks = Int.MAX_VALUE)
            (abs(capped.toVector().distance(body.toVector()) - 5.0) < 1e-9) shouldBe true
            capped.y shouldBe body.y
            staffMobPredictedAim(spell, muzzle, body, step, 32.0, maxLeadTicks = 0) shouldBe body
            val longHorizon = staffMobPredictedAim(spell, muzzle, body, Vector(.01, 0.0, 0.0), 32.0,
                maxLeadTicks = Int.MAX_VALUE, launchDelayTicks = Int.MAX_VALUE)
            (abs(longHorizon.x - .8) < 1e-9) shouldBe true
        }
        muzzle shouldBe originalMuzzle
        body shouldBe originalBody
        step shouldBe originalStep
    }

    "teleports and nonfinite measurements receive no lead" {
        val body = Location(null, 0.0, 65.4, 8.0)
        val rejectedSteps = listOf(Vector(1.001, 0.0, 0.0), Vector(.1, 2.0, .1),
            Vector(Double.NaN, 0.0, 0.0), Vector(.1, Double.NaN, 0.0),
            Vector(0.0, 0.0, Double.POSITIVE_INFINITY), Vector(Double.MAX_VALUE, 0.0, 0.0))
        projectiles.forEach { spell ->
            rejectedSteps.forEach { step -> staffMobPredictedAim(spell, muzzle, body, step, 16.0) shouldBe body }
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0).forEach { range ->
                staffMobPredictedAim(spell, muzzle, body, Vector(.1, 0.0, 0.0), range) shouldBe body
            }
            val invalidMuzzle = muzzle.clone().apply { x = Double.NaN }
            staffMobPredictedAim(spell, invalidMuzzle, body, Vector(.1, 0.0, 0.0), 16.0) shouldBe body
        }
    }

    "range is capped at 32 blocks and an unreachable prediction falls back to the body point" {
        projectiles.forEach { spell ->
            val far = Location(null, 0.0, 65.4, 33.0)
            staffMobPredictedAim(spell, muzzle, far, Vector(.1, 0.0, 0.0), Double.MAX_VALUE) shouldBe far
            val edge = Location(null, 0.0, 65.4, 15.9)
            staffMobPredictedAim(spell, muzzle, edge, Vector(0.0, 0.0, .1), 16.0) shouldBe edge
        }
    }
})

/** A straight flight crossing the target's depth, with the target continuing its observed strafe. */
private fun staffMobStrafeMiss(spell: StaffSpell, muzzle: Location, aim: Location, body: Location,
    step: Vector, range: Double, launchDelay: Int): Double {
    val direction = aim.toVector().subtract(muzzle.toVector()).normalize()
    val crossingDistance = (body.z - muzzle.z) / direction.z
    var travelled = 0.0
    for (age in 1..80) {
        travelled = when (spell) {
            StaffSpell.EMBER -> travelled + staffEmberStep(age, .5)
            StaffSpell.ICICLE -> travelled + minOf(1.2, staffIcicleStep(age))
            StaffSpell.LANCE -> if (age < StaffSpellDisplayEffects.FRAME_TICKS + 4) 0.0
                else staffLanceFront(age - StaffSpellDisplayEffects.FRAME_TICKS, range)
            else -> error("Not a projectile: $spell")
        }
        if (travelled >= crossingDistance) {
            val shot = muzzle.toVector().add(direction.clone().multiply(crossingDistance))
            val target = body.toVector().add(step.clone().multiply((launchDelay + age).toDouble()))
            return shot.distance(target)
        }
    }
    error("Shot did not reach the target depth")
}
