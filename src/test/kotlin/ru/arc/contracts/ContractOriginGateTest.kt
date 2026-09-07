package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.entity.Player

class ContractOriginGateTest : StringSpec({
    val player = mockk<Player>()
    val origin = mockk<World>()
    val survival = mockk<World>()

    every { player.isOnline } returns true
    every { player.world } returns origin
    every { origin.name } returns "rc_origin_spawn"
    every { survival.name } returns "classic_survival"

    "allows online players in Origin regardless of OP status" {
        ContractOriginGate.canSubmit(player) shouldBe true
    }

    "denies players outside Origin" {
        every { player.world } returns survival
        ContractOriginGate.canSubmit(player) shouldBe false
    }

    "denies offline players even if their last world was Origin" {
        every { player.world } returns origin
        every { player.isOnline } returns false
        ContractOriginGate.canSubmit(player) shouldBe false
    }
})
