package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.challenges.ClassChallengeInstance
import com.magmaguy.elitemobs.economy.EconomyHandler
import org.bukkit.entity.Player

internal data class DungeonClassTrialQuote(val formId: String, val fee: Double, val feeText: String, val level: Int)

/** Presentation port only: eligibility, quoted-price validation, charging and unlocking stay native. */
internal interface DungeonClassTrials {
    fun quote(player: Player, formId: String): DungeonClassTrialQuote?
    fun admit(player: Player, quote: DungeonClassTrialQuote)
}

internal object NativeDungeonClassTrials : DungeonClassTrials {
    override fun quote(player: Player, formId: String): DungeonClassTrialQuote? {
        if (!AdvancedCombatModule.isInitialized()) return null
        val module = AdvancedCombatModule.get()
        if (!module.canChallenge(player, formId)) return null
        val form = module.catalog().require(formId)
        val fee = ClassChallengeInstance.fee(form)
        return DungeonClassTrialQuote(formId, fee, EconomyHandler.formatCurrency(fee), form.band().skillUnlockLevel())
    }

    override fun admit(player: Player, quote: DungeonClassTrialQuote) =
        ClassChallengeInstance.admit(player, quote.formId, quote.fee)
}
