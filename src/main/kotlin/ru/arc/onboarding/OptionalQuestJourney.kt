package ru.arc.onboarding

import ru.ruscrafting.onboarding.api.CasinoIntroService
import java.util.Collections
import java.util.UUID

internal enum class OptionalQuestKind(val id: String) {
    ADVENTURE("adventure"), CASINO("casino"), FARM("farm");

    val steps: Set<OptionalQuestStep> get() = OptionalQuestStep.entries.filterTo(linkedSetOf()) { it.kind == this }

    companion object {
        fun fromId(id: String): OptionalQuestKind = entries.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown optional quest kind")
    }
}

internal enum class OptionalQuestStep(val kind: OptionalQuestKind) {
    GUILD_VISITED(OptionalQuestKind.ADVENTURE),
    PRIMIS_VISITED(OptionalQuestKind.ADVENTURE),
    PRIMIS_INTRO_COMPLETE(OptionalQuestKind.ADVENTURE),
    CASINO_GROUND_VISITED(OptionalQuestKind.CASINO),
    CASINO_MIDDLE_VISITED(OptionalQuestKind.CASINO),
    CASINO_UPPER_VISITED(OptionalQuestKind.CASINO),
    CASINO_SLOTS_COMPLETE(OptionalQuestKind.CASINO),
    CASINO_BLACKJACK_COMPLETE(OptionalQuestKind.CASINO),
    CASINO_PLINKO_COMPLETE(OptionalQuestKind.CASINO),
    FARM_VISITED(OptionalQuestKind.FARM),
    FARM_SHIFT_COMPLETE(OptionalQuestKind.FARM),
}

internal fun CasinoIntroService.Game.questStep(): OptionalQuestStep = when (this) {
    CasinoIntroService.Game.SLOTS -> OptionalQuestStep.CASINO_SLOTS_COMPLETE
    CasinoIntroService.Game.BLACKJACK -> OptionalQuestStep.CASINO_BLACKJACK_COMPLETE
    CasinoIntroService.Game.PLINKO -> OptionalQuestStep.CASINO_PLINKO_COMPLETE
}

internal data class OptionalQuestProgress private constructor(
    val kind: OptionalQuestKind,
    val runId: String,
    val steps: Set<OptionalQuestStep>,
) {
    val completed: Boolean get() = steps.containsAll(kind.steps)

    fun observe(step: OptionalQuestStep): OptionalQuestProgress {
        require(step.kind == kind) { "Optional quest step belongs to another quest" }
        if (completed || step in steps) return this
        val additions = when (step) {
            // A genuine terminal action proves arrival even if a world-change callback was lost.
            OptionalQuestStep.PRIMIS_INTRO_COMPLETE -> setOf(OptionalQuestStep.PRIMIS_VISITED, step)
            OptionalQuestStep.FARM_SHIFT_COMPLETE -> setOf(OptionalQuestStep.FARM_VISITED, step)
            else -> setOf(step)
        }
        return create(kind, runId, steps + additions)
    }

    companion object {
        fun accepted(kind: OptionalQuestKind): OptionalQuestProgress = create(kind, UUID.randomUUID().toString(), emptySet())

        fun create(kind: OptionalQuestKind, runId: String, steps: Set<OptionalQuestStep>): OptionalQuestProgress {
            require(UUID.fromString(runId).toString() == runId) { "Invalid optional quest identity" }
            require(steps.all { it.kind == kind }) { "Optional quest contains a foreign step" }
            require(OptionalQuestStep.PRIMIS_INTRO_COMPLETE !in steps || OptionalQuestStep.PRIMIS_VISITED in steps)
            require(OptionalQuestStep.FARM_SHIFT_COMPLETE !in steps || OptionalQuestStep.FARM_VISITED in steps)
            return OptionalQuestProgress(kind, runId, Collections.unmodifiableSet(LinkedHashSet(steps)))
        }
    }
}

internal enum class OptionalQuestAction { GUILD, PRIMIS, CASINO, FARM, TRIAL_SLOTS, TRIAL_BLACKJACK, TRIAL_PLINKO }

internal data class OptionalQuestView(
    val kind: OptionalQuestKind,
    val accepted: Boolean,
    val completed: Boolean,
    val steps: Set<OptionalQuestStep>,
    val trialStates: Map<CasinoIntroService.Game, CasinoIntroService.State> = emptyMap(),
)
