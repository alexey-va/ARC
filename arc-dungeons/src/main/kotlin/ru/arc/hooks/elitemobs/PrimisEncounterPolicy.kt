package ru.arc.hooks.elitemobs

internal enum class PrimisDamageOrigin { INSIDE_PLAYER, OUTSIDE_PLAYER, UNATTRIBUTED, ONGOING_EFFECT }
internal enum class PrimisAdmissionDecision { ALLOW, ENTER_ROOM, UNKNOWN_INITIATOR, NO_PARTICIPANT, PASSAGE_OCCUPIED, NOT_READY }
internal enum class PrimisEncounterState { IDLE, PROVISIONAL, ADMITTED, RESETTING }

/** The native owner resolves damage attribution and geometry before this side-effect-free decision. */
internal fun primisDamageAdmission(
    origin: PrimisDamageOrigin,
    admitted: Boolean,
    ready: Boolean,
    passageClear: Boolean,
    hasInsideParticipant: Boolean,
): PrimisAdmissionDecision = when {
    !ready -> PrimisAdmissionDecision.NOT_READY
    origin == PrimisDamageOrigin.OUTSIDE_PLAYER -> PrimisAdmissionDecision.ENTER_ROOM
    origin == PrimisDamageOrigin.UNATTRIBUTED || origin == PrimisDamageOrigin.ONGOING_EFFECT ->
        if (!admitted) PrimisAdmissionDecision.UNKNOWN_INITIATOR
        else if (!hasInsideParticipant) PrimisAdmissionDecision.NO_PARTICIPANT else PrimisAdmissionDecision.ALLOW
    !admitted && !passageClear -> PrimisAdmissionDecision.PASSAGE_OCCUPIED
    else -> PrimisAdmissionDecision.ALLOW
}

/** Transient admission is reconstructed from the native phase and actual room occupants on reload. */
internal class PrimisEncounterPolicy {
    var state = PrimisEncounterState.IDLE
        private set
    private var emptySince: Long? = null
    private var provisionalUntil = 0L

    fun adopt(activePhase: Boolean, hasInsideParticipant: Boolean) {
        state = when {
            !activePhase -> PrimisEncounterState.IDLE
            hasInsideParticipant -> PrimisEncounterState.ADMITTED
            else -> PrimisEncounterState.RESETTING
        }
        emptySince = null
    }

    fun damage(origin: PrimisDamageOrigin, ready: Boolean, passageClear: Boolean,
        hasInsideParticipant: Boolean, tick: Long = 0): PrimisAdmissionDecision {
        val decision = primisDamageAdmission(origin, state == PrimisEncounterState.ADMITTED,
            ready && state != PrimisEncounterState.RESETTING, passageClear, hasInsideParticipant)
        if (decision == PrimisAdmissionDecision.ALLOW && origin == PrimisDamageOrigin.INSIDE_PLAYER) {
            if (state == PrimisEncounterState.IDLE) {
                state = PrimisEncounterState.PROVISIONAL
                provisionalUntil = tick + 20
            }
            emptySince = null
        }
        return decision
    }

    fun confirmAdmission() {
        if (state == PrimisEncounterState.PROVISIONAL) state = PrimisEncounterState.ADMITTED
    }

    fun observe(tick: Long, hasInsideParticipant: Boolean): Boolean {
        if (state == PrimisEncounterState.PROVISIONAL && tick >= provisionalUntil) beginReset()
        if (state == PrimisEncounterState.ADMITTED) {
            if (hasInsideParticipant) emptySince = null
            else {
                val since = emptySince
                if (since == null) emptySince = tick
                else if (tick - since >= 20) beginReset()
            }
        }
        return state == PrimisEncounterState.RESETTING
    }

    fun beginReset() {
        state = PrimisEncounterState.RESETTING
        emptySince = null
    }

    fun acknowledgeReset(nativeFirstPhaseReady: Boolean, passageBaselineReady: Boolean): Boolean {
        if (state != PrimisEncounterState.RESETTING || !nativeFirstPhaseReady || !passageBaselineReady) return false
        state = PrimisEncounterState.IDLE
        return true
    }
}
