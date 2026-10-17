package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule
import com.magmaguy.elitemobs.advancedcombat.challenges.ClassChallengeInstance
import com.magmaguy.elitemobs.advancedcombat.content.BuiltInClassContent
import com.magmaguy.elitemobs.economy.EconomyHandler
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.entity.Player

class DungeonClassTrialsTest : FreeSpec({
    "trial quote uses current native eligibility and price and admission keeps the displayed quote" {
        mockkStatic(AdvancedCombatModule::class, ClassChallengeInstance::class, EconomyHandler::class)
        try {
            val player = mockk<Player>()
            val module = mockk<AdvancedCombatModule>()
            val form = BuiltInClassContent.catalog().require("occultist")
            every { AdvancedCombatModule.isInitialized() } returns true
            every { AdvancedCombatModule.get() } returns module
            every { module.catalog() } returns BuiltInClassContent.catalog()
            every { module.canChallenge(player, form.id()) } returns false
            NativeDungeonClassTrials.quote(player, form.id()) shouldBe null
            verify(exactly = 0) { ClassChallengeInstance.fee(any()) }

            every { module.canChallenge(player, form.id()) } returns true
            every { ClassChallengeInstance.fee(form) } returns 123.75
            every { EconomyHandler.formatCurrency(123.75) } returns "123,75"
            val quote = NativeDungeonClassTrials.quote(player, form.id())!!
            quote shouldBe DungeonClassTrialQuote(form.id(), 123.75, "123,75", form.band().skillUnlockLevel())
            // Native admission owns the changed-fee rejection; the UI must never silently re-quote a click.
            every { ClassChallengeInstance.fee(form) } returns 500.0
            every { ClassChallengeInstance.admit(player, form.id(), 123.75) } returns Unit
            NativeDungeonClassTrials.admit(player, quote)
            verify(exactly = 1) { ClassChallengeInstance.admit(player, form.id(), 123.75) }
            verify(exactly = 1) { ClassChallengeInstance.fee(form) }
        } finally { unmockkStatic(AdvancedCombatModule::class, ClassChallengeInstance::class, EconomyHandler::class) }
    }
})
