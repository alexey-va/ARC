package ru.arc.jobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
})
