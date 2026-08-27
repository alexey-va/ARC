package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class InvestigationResourcesTest : StringSpec({
    "bundled investigation defaults are disabled and resource-pack neutral" {
        val module = requireNotNull(javaClass.classLoader.getResource("modules/investigations.yml")).readText()
        val gui = requireNotNull(javaClass.classLoader.getResource("guis/investigations.yml")).readText()

        module shouldContain "enabled: false"
        gui shouldContain "material: WRITABLE_BOOK"
        gui shouldContain "material: SPYGLASS"
        gui shouldContain "material: CHEST"
        gui shouldNotContain "customModelData"
        gui shouldNotContain "arc:"
        gui shouldNotContain "HOPPER"
        gui shouldNotContain "confirmation:"
        gui shouldNotContain "back:"
        gui shouldNotContain "confirm:"
        gui shouldNotContain "cancel:"
    }
})
