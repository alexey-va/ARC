package ru.arc.jobs

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.arc.util.Common
import java.util.UUID

class BoostDataEntityRegressionTest : FreeSpec({
    fun activeBoost(
        id: String,
        boost: Double = 0.5,
    ) = JobsBoostData(
        boost = boost,
        id = id,
        expires = System.currentTimeMillis() + 60_000,
    )

    "expired boosts are not reported as present" {
        val entity =
            BoostDataEntity(
                boosts =
                    setOf(
                        JobsBoostData(
                            id = "expired",
                            expires = System.currentTimeMillis() - 1,
                        ),
                    ),
            )

        entity.hasBoostWithId("expired") shouldBe false
    }

    "merging an entity with itself preserves its boosts" {
        val entity = BoostDataEntity(boosts = setOf(activeBoost("kept")))

        entity.merge(entity)

        entity.boosts.shouldHaveSize(1)
        entity.hasBoostWithId("kept") shouldBe true
    }

    "concurrent additions keep a boost id unique" {
        val entity = BoostDataEntity()
        val start = CompletableDeferred<Unit>()

        val results =
            coroutineScope {
                List(128) { index ->
                    async(Dispatchers.Default) {
                        start.await()
                        entity.addBoost(
                            activeBoost(
                                id = "shared-id",
                                boost = index.toDouble(),
                            ).copy(boostUuid = UUID.randomUUID()),
                        )
                    }
                }.also { start.complete(Unit) }.awaitAll()
            }

        results.count { it } shouldBe 1
        entity.boosts.shouldHaveSize(1)
    }

    "exact-id rollback preserves neighboring boost ids" {
        val entity =
            BoostDataEntity(
                boosts =
                    setOf(
                        activeBoost("sale_all_money"),
                        activeBoost("sale_miner_exp"),
                        activeBoost("sale_special"),
                        activeBoost("unrelated"),
                    ),
            )

        entity.removeBoostIds(setOf("sale_miner_exp")) shouldBe 1

        entity.boosts
            .map { it.id }
            .shouldContainExactlyInAnyOrder("sale_all_money", "sale_special", "unrelated")
    }

    "Common Gson round trip preserves all and specific job targets" {
        val original =
            BoostDataEntity(
                player = UUID.randomUUID(),
                boosts =
                    setOf(
                        activeBoost("all").copy(jobTarget = JobTarget.All),
                        activeBoost("specific").copy(jobTarget = JobTarget.Specific("Miner")),
                    ),
            )

        val json = Common.gson.toJson(original)
        json shouldContain """"jobTarget":"all""""
        json shouldContain """"jobTarget":"Miner""""

        val restored = Common.gson.fromJson(json, BoostDataEntity::class.java)

        restored.boosts
            .associate { it.id to it.jobTarget } shouldBe
            mapOf(
                "all" to JobTarget.All,
                "specific" to JobTarget.Specific("Miner"),
            )
    }

    "Common Gson reads legacy object-shaped job targets" {
        val original =
            BoostDataEntity(
                player = UUID.randomUUID(),
                boosts =
                    setOf(
                        activeBoost("all").copy(jobTarget = JobTarget.All),
                        activeBoost("specific").copy(jobTarget = JobTarget.Specific("Miner")),
                    ),
            )
        val legacyJson = Gson().toJson(original)

        val restored = Common.gson.fromJson(legacyJson, BoostDataEntity::class.java)

        restored.boosts
            .associate { it.id to it.jobTarget } shouldBe
            mapOf(
                "all" to JobTarget.All,
                "specific" to JobTarget.Specific("Miner"),
            )
    }
})
