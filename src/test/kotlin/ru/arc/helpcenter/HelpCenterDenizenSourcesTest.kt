package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HelpCenterDenizenSourcesTest : StringSpec({
    "legacy help reads exactly the data used by the drop script and observes changed values" {
        val values = mutableMapOf(
            "monster.base-chance" to "0.02", "monster.max-chance" to "0.05", "monster.health-unit" to "20",
            "monster.enderman-multiplier" to "0.2", "monster.spawner-multiplier" to "0.15",
            "monster.enderman-world" to "world_the_end", "monster.ratelimit" to "1s",
            "wither-amount" to "5", "dragon-amount" to "5",
        )
        denizenSourceFacts(values::get).first().values["base"] shouldBe "2"
        denizenSourceFacts(values::get).last().values["dragon"] shouldBe "5"
        values["monster.base-chance"] = "0.03"
        values["dragon-amount"] = "7"
        denizenSourceFacts(values::get).first().values["base"] shouldBe "3"
        denizenSourceFacts(values::get).last().values["dragon"] shouldBe "7"
    }
})
