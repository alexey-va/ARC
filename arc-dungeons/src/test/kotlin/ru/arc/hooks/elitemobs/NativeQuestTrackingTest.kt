package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.quests.QuestTracking
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.zip.ZipFile

class NativeQuestTrackingTest : FreeSpec({
    "the exact supported runtime exposes its native map through the common adapter" {
        val version = eliteMobsTestRuntimeVersion()
        PassiveClassCompatibility.requireSupportedVersion(version)
        val field = QuestTracking::class.java.getDeclaredField("playerTrackingQuests").apply { isAccessible = true }
        val native = field.get(null)
        (NativeQuestTracking.current() === native) shouldBe true
        NativeQuestTracking.current().javaClass.simpleName shouldBe when (version) {
            "10.9.8" -> "HashMap"
            "10.9.10" -> "ConcurrentHashMap"
            else -> error("Unsupported test runtime: $version")
        }
    }
})

internal fun eliteMobsTestRuntimeVersion(): String =
    ZipFile(File(QuestTracking::class.java.protectionDomain.codeSource.location.toURI())).use { jar ->
        jar.getInputStream(jar.getEntry("plugin.yml")).bufferedReader().useLines { lines ->
            lines.first { it.startsWith("version:") }.substringAfter(':').trim().trim('\'', '"')
        }
    }
