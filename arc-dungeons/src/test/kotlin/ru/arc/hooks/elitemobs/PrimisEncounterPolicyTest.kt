package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PrimisEncounterPolicyTest : StringSpec({
    "an outside player cannot initiate or join an admitted encounter through damage" {
        for (active in listOf(false, true)) primisDamageAdmission(PrimisDamageOrigin.OUTSIDE_PLAYER,
            admitted = active, ready = true, passageClear = true, hasInsideParticipant = true) shouldBe PrimisAdmissionDecision.ENTER_ROOM
    }
    "unknown environmental or lingering damage cannot initiate but continues inside an admitted fight" {
        primisDamageAdmission(PrimisDamageOrigin.UNATTRIBUTED, false, true, true, true) shouldBe PrimisAdmissionDecision.UNKNOWN_INITIATOR
        primisDamageAdmission(PrimisDamageOrigin.UNATTRIBUTED, true, true, true, true) shouldBe PrimisAdmissionDecision.ALLOW
        primisDamageAdmission(PrimisDamageOrigin.UNATTRIBUTED, true, true, true, false) shouldBe PrimisAdmissionDecision.NO_PARTICIPANT
    }
    "an inside attacker starts only when closing passages are clear" {
        primisDamageAdmission(PrimisDamageOrigin.INSIDE_PLAYER, false, true, true, true) shouldBe PrimisAdmissionDecision.ALLOW
        primisDamageAdmission(PrimisDamageOrigin.INSIDE_PLAYER, false, true, false, true) shouldBe PrimisAdmissionDecision.PASSAGE_OCCUPIED
        primisDamageAdmission(PrimisDamageOrigin.INSIDE_PLAYER, true, true, false, true) shouldBe PrimisAdmissionDecision.ALLOW
    }
    "unverified chunks or an incomplete native reset deny all new damage" {
        for (origin in PrimisDamageOrigin.entries) for (active in listOf(false, true))
            primisDamageAdmission(origin, active, false, true, true) shouldBe PrimisAdmissionDecision.NOT_READY
    }
    "empty rooms get twenty ticks of grace and returning participants cancel the pending reset" {
        val policy = PrimisEncounterPolicy()
        policy.damage(PrimisDamageOrigin.INSIDE_PLAYER, true, true, true) shouldBe PrimisAdmissionDecision.ALLOW
        policy.confirmAdmission()
        policy.observe(10, false) shouldBe false
        policy.observe(20, true) shouldBe false
        policy.observe(30, false) shouldBe false
        policy.observe(40, false) shouldBe false
        policy.observe(50, false) shouldBe true
        policy.state shouldBe PrimisEncounterState.RESETTING
    }
    "native reset acknowledgement waits for the first body and complete passage baseline" {
        val policy = PrimisEncounterPolicy()
        policy.beginReset()
        policy.damage(PrimisDamageOrigin.INSIDE_PLAYER, true, true, true) shouldBe PrimisAdmissionDecision.NOT_READY
        policy.acknowledgeReset(false, true) shouldBe false
        policy.acknowledgeReset(true, false) shouldBe false
        policy.state shouldBe PrimisEncounterState.RESETTING
        policy.acknowledgeReset(true, true) shouldBe true
        policy.state shouldBe PrimisEncounterState.IDLE
    }
    "reload adopts an active native phase only with real inside participants" {
        for (inside in listOf(false, true)) {
            val policy = PrimisEncounterPolicy()
            policy.adopt(activePhase = true, hasInsideParticipant = inside)
            policy.state shouldBe if (inside) PrimisEncounterState.ADMITTED else PrimisEncounterState.RESETTING
        }
        val idle = PrimisEncounterPolicy()
        idle.adopt(activePhase = false, hasInsideParticipant = true)
        idle.state shouldBe PrimisEncounterState.IDLE
    }
    "a later cancelled first hit cannot leave admission for unrelated damage" {
        val policy = PrimisEncounterPolicy()
        policy.damage(PrimisDamageOrigin.INSIDE_PLAYER, true, true, true, tick = 10) shouldBe PrimisAdmissionDecision.ALLOW
        policy.state shouldBe PrimisEncounterState.PROVISIONAL
        policy.damage(PrimisDamageOrigin.UNATTRIBUTED, true, true, true, tick = 11) shouldBe PrimisAdmissionDecision.UNKNOWN_INITIATOR
        policy.observe(20, true) shouldBe false
        policy.observe(30, true) shouldBe true
        policy.state shouldBe PrimisEncounterState.RESETTING
    }
})
