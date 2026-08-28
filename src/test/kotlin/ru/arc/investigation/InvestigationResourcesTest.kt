package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class InvestigationResourcesTest : StringSpec({
    "bundled investigation defaults are disabled and resource-pack neutral" {
        val module = requireNotNull(javaClass.classLoader.getResource("modules/investigations.yml")).readText()
        val gui = requireNotNull(javaClass.classLoader.getResource("guis/investigations.yml")).readText()

        module shouldContain "enabled: false"
        gui shouldContain "material: WRITABLE_BOOK"
        gui shouldContain "material: CHEST"
        gui shouldContain "next-step: { material: COMPASS }"
        gui shouldContain "evidence: { material: RECOVERY_COMPASS }"
        gui shouldContain "choose-verdict: { material: TARGET }"
        gui shouldContain "return-to-foma: { material: EMERALD }"
        gui shouldContain "case-file: { material: BOOK }"
        gui shouldContain "testimony: { material: PAPER }"
        gui shouldContain "theory-five: { material: LIME_DYE }"
        gui shouldNotContain "customModelData"
        gui shouldNotContain "arc:"
        gui shouldNotContain "HOPPER"
        gui shouldNotContain "confirmation:"
        gui shouldNotContain "confirm:"
        gui shouldNotContain "cancel:"
        gui shouldNotContain "close:"
    }

    "authored investigations stay away from auction and registry jargon" {
        val catalog = requireNotNull(javaClass.classLoader.getResource("modules/investigation-cases.yml")).readText()
        val words = catalog.lowercase().split(Regex("[^\\p{L}]+"))

        words.toSet().intersect(
            setOf("лот", "лоты", "лотов", "торги", "аукцион", "реестр", "ведомость", "страховой", "полис"),
        ) shouldBe emptySet()
    }
})
