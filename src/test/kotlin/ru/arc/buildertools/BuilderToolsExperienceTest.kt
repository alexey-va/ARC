package ru.arc.buildertools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuilderToolsExperienceTest : FunSpec({
    test("operation progress is shown immediately, periodically, and on completion") {
        BuilderProgressCadence.shouldRender(1, completed = false) shouldBe true
        BuilderProgressCadence.shouldRender(2, completed = false) shouldBe false
        BuilderProgressCadence.shouldRender(9, completed = false) shouldBe false
        BuilderProgressCadence.shouldRender(10, completed = false) shouldBe true
        BuilderProgressCadence.shouldRender(11, completed = true) shouldBe true
    }

    test("operation progress rejects impossible batch numbers") {
        shouldThrow<IllegalArgumentException> {
            BuilderProgressCadence.shouldRender(0, completed = false)
        }
    }
})
