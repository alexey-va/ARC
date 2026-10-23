package ru.arc.hooks.elitemobs

import com.google.gson.JsonParser
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.paper.testing.MockBukkitTestRuntime

class PrimisEncounterProfilesTest : StringSpec({
    "verified live pack controls prepare only thirteen four hundred seventy-four and nineteen changed closure cells" {
        MockBukkitTestRuntime.open().use {
            val configs = primisControlFixtures()
            val prepared = preparePrimisEncounters(config = configs::get)
            prepared.map { it.failure } shouldBe listOf(null, null, null)
            prepared.map { it.passages.size } shouldBe listOf(13, 474, 19)
            prepared.all { it.ready } shouldBe true
            prepared.first().passages.any { it.x == 1104 && it.y == 37 && it.z == 573 } shouldBe true
            // Ice Mage's separate 55 quest-wall states are not this guard's passage footprint.
            prepared.first().passages.all { it.baseline.material.isAir } shouldBe true
            prepared.last().passages.all { it.baseline.material.isAir } shouldBe true
        }
    }
    "an equal-count moved pack disables just its own profile instead of changing admission geometry" {
        MockBukkitTestRuntime.open().use {
            val configs = primisControlFixtures()
            val ice = configs.getValue("primis_ice_mage_p2.yml")
            val moved = ice.onSpawnBlockStates.map { "0.5,0.5,0.5/" + it.substringAfter('/') }
            every { ice.onSpawnBlockStates } returns moved
            val prepared = preparePrimisEncounters(config = configs::get)
            prepared.map { it.ready } shouldBe listOf(false, true, true)
            prepared.first().failure shouldBe "block controls differ from the verified pack"
        }
    }
    "authored doors cage and native leash define admission independently from changed block bounds" {
        val profiles = primisEncounterProfiles()
        profiles[0].room.contains(1095.5, 37.0, 573.5) shouldBe true
        profiles[0].room.contains(1105.32241682432, 37.0, 573.8900094016826) shouldBe false
        profiles[0].room.contains(1104.0, 37.0, 573.5) shouldBe false
        profiles[1].room.contains(491.5, 55.5, 368.5) shouldBe true
        profiles[1].room.contains(497.0, 55.5, 368.5) shouldBe false
        profiles[2].room.contains(1264.5, 50.5, 574.0) shouldBe true
        profiles[2].room.contains(1264.5, 50.5, 573.9) shouldBe false
        profiles[2].room.contains(1300.0, 50.5, 600.5) shouldBe false
        profiles.first().matchesSpawn(profiles.first().spawn + ":123456") shouldBe true
        profiles.first().matchesSpawn(profiles.first().spawn + ",garbage") shouldBe false
        profiles.first().matchesSpawn(profiles.first().spawn.replace("1095.5", "1096.5")) shouldBe false
    }
})

private fun primisControlFixtures(): Map<String, CustomBossesConfigFields> = buildMap {
    val stream = requireNotNull(PrimisEncounterProfilesTest::class.java.getResourceAsStream("/primis-encounter-block-controls.json"))
    val records = stream.bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
    for (record in records) {
        val row = record.asJsonObject
        val baseFile = row["baseFile"].asString; val phaseFile = row["phaseFile"].asString
        for ((file, statesKey) in listOf(baseFile to "baseStates", phaseFile to "phaseStates")) {
            put(file, mockk {
                every { filename } returns file
                every { phases } returns if (file == baseFile) listOf("$phaseFile:.9999") else emptyList()
                every { phaseSpawnLocation } returns null
                every { onSpawnBlockStates } returns row[statesKey].asJsonArray.map { it.asString }
            })
        }
    }
}
