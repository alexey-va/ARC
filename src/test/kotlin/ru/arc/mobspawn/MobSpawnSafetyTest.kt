package ru.arc.mobspawn

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import org.bukkit.entity.EntityType
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.TestTaskScheduler

class MobSpawnSafetyTest :
    FreeSpec({
        afterEach {
            MobSpawnManager.cancel()
        }

        "natural spawn accepts solid ground with two passable blocks above" {
            SpawnLocationPolicy(
                groundSolid = true,
                feetPassable = true,
                headPassable = true,
                withinRadius = true,
                lightAllowed = true,
                visibleToPlayer = false,
                claimed = false,
            ).isValid().shouldBeTrue()
        }

        "natural spawn rejects claimed or obstructed candidate" {
            val valid =
                SpawnLocationPolicy(
                    groundSolid = true,
                    feetPassable = true,
                    headPassable = true,
                    withinRadius = true,
                    lightAllowed = true,
                    visibleToPlayer = false,
                    claimed = false,
                )

            valid.copy(claimed = true).isValid().shouldBeFalse()
            valid.copy(feetPassable = false).isValid().shouldBeFalse()
            valid.copy(headPassable = false).isValid().shouldBeFalse()
        }

        "spawn attempt limit is bounded and rejects unusable inputs" {
            calculateSpawnAttemptLimit(amount = 2, tryMultiplier = 30).shouldBeExactly(60)
            calculateSpawnAttemptLimit(amount = -1, tryMultiplier = 30).shouldBeExactly(0)
            calculateSpawnAttemptLimit(amount = Int.MAX_VALUE, tryMultiplier = Int.MAX_VALUE)
                .shouldBeExactly(MAX_SPAWN_ATTEMPTS)
        }

        "manager does not publish service when scheduling fails" {
            val scheduler =
                object : TaskScheduler by TestTaskScheduler() {
                    override fun runTimer(
                        delayTicks: Long,
                        periodTicks: Long,
                        task: Runnable,
                    ): ScheduledTask = error("scheduler unavailable")
                }
            val service = service(scheduler)

            shouldThrow<IllegalStateException> {
                MobSpawnManager.init(service)
            }

            MobSpawnManager.getService().shouldBeNull()
            MobSpawnManager.isRunning().shouldBeFalse()
        }

        "running state follows an externally cancelled scheduled task" {
            val scheduler = TestTaskScheduler()
            val service = service(scheduler)
            service.start()

            service.isRunning().shouldBeTrue()
            scheduler.cancelAll()

            service.isRunning().shouldBeFalse()
        }
    }) {
    companion object {
        private const val TEST_WORLD = "test"

        private fun service(scheduler: TaskScheduler): MobSpawnService =
            MobSpawnService(
                config =
                    TestMobSpawnConfig(
                        worlds = setOf(TEST_WORLD),
                        mobWeights = mapOf(EntityType.ZOMBIE to 1),
                    ),
                scheduler = scheduler,
                worldProvider = object : WorldProvider {
                    override fun getWorlds() = emptyList<org.bukkit.World>()
                },
                claimChecker = object : ClaimChecker {
                    override fun isClaimed(location: org.bukkit.Location) = false
                },
                entitySpawner = object : EntitySpawner {
                    override fun spawn(
                        location: org.bukkit.Location,
                        entityType: EntityType,
                    ) = Unit

                    override fun spawnViaCmi(
                        player: org.bukkit.entity.Player,
                        entityType: EntityType,
                        amount: Int,
                        spread: Int,
                    ) = Unit
                },
            )
    }
}
