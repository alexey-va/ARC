package ru.arc.misc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.util.Common

class CustomJoinMessageTest : FreeSpec({
    "old Redis records retain selections and acquire an empty personal library" {
        val data = Common.gson.fromJson(
            """{"player":"Viewer","joinMessages":["old"],"leaveMessages":[],"timestamp":1}""",
            JoinMessagesData::class.java,
        )
        data.selectedMessages(true) shouldBe setOf("old")
        data.customMessages(true) shouldBe emptySet()
        data.addCustomMessage("  принёс уют  ", true) shouldBe true
        data.customMessages(true) shouldBe setOf("принёс уют")
        data.selectedMessages(true) shouldBe setOf("old", "%player_name% принёс уют")
        val decoded = Common.gson.fromJson(Common.gson.toJson(data), JoinMessagesData::class.java)
        decoded.customMessages(true) shouldBe setOf("принёс уют")
        decoded.customMessages(false) shouldBe emptySet()
    }

    "disable keeps a saved phrase and deletion removes its active key only from its kind" {
        val data = JoinMessagesData("Viewer")
        data.addCustomMessage("вернулся", true)
        data.addCustomMessage("вернулся", false)
        data.updateMessage(CustomJoinMessage.selectionKey("вернулся"), true, false)
        data.customMessages(true) shouldBe setOf("вернулся")
        data.selectedMessages(true) shouldBe emptySet()
        data.deleteCustomMessage("вернулся", false) shouldBe true
        data.customMessages(false) shouldBe emptySet()
        data.selectedMessages(false) shouldBe emptySet()
        data.customMessages(true) shouldBe setOf("вернулся")
    }

    "custom library is bounded and duplicate creation does not consume another slot" {
        val data = JoinMessagesData("Viewer")
        repeat(CustomJoinMessage.MAX_SAVED) { data.addCustomMessage("фраза $it", true) }
        data.addCustomMessage("фраза 0", true) shouldBe false
        data.updateMessage(CustomJoinMessage.selectionKey("фраза 0"), true, false)
        data.addCustomMessage("фраза 0", true) shouldBe true
        data.selectedMessages(true).contains(CustomJoinMessage.selectionKey("фраза 0")) shouldBe true
        shouldThrow<IllegalArgumentException> { data.addCustomMessage("ещё одна", true) }
        data.deleteCustomMessage("фраза 0", true)
        data.addCustomMessage("ещё одна", true) shouldBe true
        data.customMessages(true).size shouldBe CustomJoinMessage.MAX_SAVED
    }

    "validation blocks markup control characters and overlong input on the server" {
        listOf("", " ", "а".repeat(121), "<red>текст", "&aтекст", "§aтекст", "%player_name%", "#ff0000",
            "текст\\", "\nтекст", "текст\n", "две\rстроки", "текст\u200B", "текст\u202E", "текст\u0000").forEach {
            shouldThrow<IllegalArgumentException> { CustomJoinMessage.normalize(it) }
        }
        CustomJoinMessage.normalize("а".repeat(120)).length shouldBe 120
        CustomJoinMessage.normalize("  принёс чай!  ") shouldBe "принёс чай!"
    }

    "merge retains disabled saved phrases and prevents expiry while any are saved" {
        val data = JoinMessagesData("Viewer", timestamp = 0)
        val other = JoinMessagesData("Viewer")
        other.addCustomMessage("вернулся", true)
        other.updateMessage(CustomJoinMessage.selectionKey("вернулся"), true, false)
        data.merge(other)
        data.customMessages(true) shouldBe setOf("вернулся")
        data.shouldRemove() shouldBe false
        data.deleteCustomMessage("вернулся", true)
        data.shouldRemove() shouldBe true
    }
})
