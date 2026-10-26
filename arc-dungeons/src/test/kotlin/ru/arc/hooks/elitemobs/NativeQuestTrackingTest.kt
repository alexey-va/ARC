package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.quests.QuestTracking
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class NativeQuestTrackingTest : FreeSpec({
    "the running API exposes its own map through the common adapter" {
        val field = QuestTracking::class.java.getDeclaredField("playerTrackingQuests").apply { isAccessible = true }
        val native = field.get(null)
        (NativeQuestTracking.current() === native) shouldBe true
        val getter = QuestTracking::class.java.getMethod("getPlayerTrackingQuests")
        Map::class.java.isAssignableFrom(getter.returnType) shouldBe true
        getter.returnType.isInstance(NativeQuestTracking.current()) shouldBe true
    }
})
