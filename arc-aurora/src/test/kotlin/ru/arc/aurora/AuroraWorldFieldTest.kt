package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import java.util.Random
import java.util.UUID

class AuroraWorldFieldTest : FreeSpec({
    val base = AuroraConfig.parse(YamlConfiguration().apply {
        set("profiles.base.sky-scene", true)
    }).profiles.single()
    val field = AuroraSkyFieldSettings(cellSize = 16.0, viewDistance = 192.0)
    val world = UUID(17L, 29L)

    "default sky weights preserve the existing world cell assignment" {
        val profiles = List(29) { base.copy(id = "profile-$it") }
        val candidates = AuroraWorldField.candidates(world, profiles, 0.0, 0.0, 0L, field)
        candidates.isNotEmpty() shouldBe true
        for (candidate in candidates) {
            val seed = world.mostSignificantBits xor world.leastSignificantBits xor
                (candidate.cell.x.toLong() * 341873128712L) xor (candidate.cell.z.toLong() * 132897987541L)
            candidate.profile shouldBe profiles[Random(seed).nextInt(profiles.size)]
        }
    }

    "weighted lanterns are more frequent while profile identity remains world anchored across movement and epochs" {
        val profiles = List(28) { base.copy(id = "profile-$it") } + base.copy(id = "lantern", skyWeight = 8)
        val first = AuroraWorldField.candidates(world, profiles, 0.0, 0.0, 0L, field)
        val fraction = first.count { it.profile.id == "lantern" }.toDouble() / first.size
        (fraction in 0.16..0.29) shouldBe true // Eight of 36 shares, with a fixed spatial sample.
        val byCell = first.associateBy { it.cell }
        for (age in listOf(0L, 1400L)) {
            val moved = AuroraWorldField.candidates(world, profiles, 30.0, 10.0, age, field)
            val common = moved.filter { it.cell in byCell }
            common.isNotEmpty() shouldBe true
            common.all { it.profile === byCell.getValue(it.cell).profile } shouldBe true
        }
    }
})
