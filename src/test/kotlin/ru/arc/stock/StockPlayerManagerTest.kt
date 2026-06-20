package ru.arc.stock

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bukkit.Material
import ru.arc.audit.AuditManager
import ru.arc.audit.AuditService
import ru.arc.configs.StockConfig
import ru.arc.repository.CachedRepository
import ru.arc.repository.InMemoryStorage
import ru.arc.repository.InMemorySyncService
import ru.arc.repository.RepoConfig
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class StockPlayerManagerTest : FreeSpec({

    fun makePlayerRepo(): CachedRepository<StockPlayer> {
        val storage = InMemoryStorage<StockPlayer>()
        val sync = InMemorySyncService<StockPlayer>()
        val config = RepoConfig.builder<StockPlayer>("players-test")
            .saveInterval(10.seconds)
            .build()
        return CachedRepository(config = config, storage = storage, syncService = sync)
    }

    fun makeStockRepo(): CachedRepository<Stock> {
        val storage = InMemoryStorage<Stock>()
        val sync = InMemorySyncService<Stock>()
        val config = RepoConfig.builder<Stock>("stocks-test")
            .saveInterval(10.seconds)
            .build()
        return CachedRepository(config = config, storage = storage, syncService = sync)
    }

    fun stock(symbol: String = "AAPL", price: Double = 100.0) = Stock(
        symbol = symbol,
        price = price,
        type = Stock.Type.STOCK,
    )

    fun player(balance: Double = 10_000.0) = StockPlayer(
        playerName = "TestPlayer",
        playerUuid = UUID.randomUUID(),
        balance = balance,
    )

    beforeEach {
        StockPlayerManager.playerRepo = makePlayerRepo()
        StockMarket.stockRepo = makeStockRepo()
        StockConfig.commission = 0.01
        StockConfig.leveragePower = 0.5
        StockConfig.defaultStockMaxAmount = 30
        StockConfig.iconMaterials = mutableListOf(Material.PAPER)
        AuditManager.init(mockk<AuditService>(relaxed = true))
    }

    afterEach {
        runTest {
            StockPlayerManager.playerRepo.shutdown()
            StockMarket.stockRepo.shutdown()
        }
    }

    "economyCheck()" - {
        "returns success when player has enough balance" {
            val sp = player(10_000.0)
            val s = stock(price = 100.0)

            val result = StockPlayerManager.economyCheck(sp, s, amount = 10.0, leverage = 1)

            result.success.shouldBeTrue()
            result.totalPrice shouldBeGreaterThan 0.0
        }

        "returns failure when player has insufficient balance" {
            val sp = player(balance = 1.0)
            val s = stock(price = 100.0)

            val result = StockPlayerManager.economyCheck(sp, s, amount = 100.0, leverage = 1)

            result.success.shouldBeFalse()
            result.lack shouldBeGreaterThan 0.0
        }
    }

    "cost()" - {
        "equals price * amount" {
            val s = stock(price = 250.0)
            StockPlayerManager.cost(s, 4.0) shouldBe 1000.0
        }
    }

    "commission()" - {
        "is cost times commission rate" {
            val s = stock(price = 100.0)
            // cost(s, 10.0) = 100.0 * 10.0 = 1000.0; commission = 1000.0 * 0.01 = 10.0
            val commission = StockPlayerManager.commission(s, amount = 10.0, leverage = 1)
            commission shouldBe 10.0
        }

        "is higher for leverage > 100" {
            val s = stock(price = 100.0)
            val base = StockPlayerManager.commission(s, amount = 10.0, leverage = 1)
            val leveraged = StockPlayerManager.commission(s, amount = 10.0, leverage = 200)

            leveraged shouldBeGreaterThan base
        }
    }

    "buyStock()" - {
        "deducts cost from player balance" {
            runTest {
                val sp = player()
                val s = stock(price = 100.0)
                StockMarket.stockRepo.save(s)
                StockPlayerManager.playerRepo.save(sp)
                val initialBalance = sp.getBalance()

                StockPlayerManager.buyStock(sp, s, amount = 1.0, leverage = 1, lowerBound = 10000.0, upperBound = 10000.0)

                sp.getBalance() shouldBeLessThan initialBalance
            }
        }

        "adds BUY position to player" {
            runTest {
                val sp = player()
                val s = stock(price = 100.0)
                StockMarket.stockRepo.save(s)
                StockPlayerManager.playerRepo.save(sp)

                StockPlayerManager.buyStock(sp, s, amount = 2.0, leverage = 1, lowerBound = 10000.0, upperBound = 10000.0)

                val positions = sp.positions("AAPL")
                positions.shouldNotBeNull()
                positions.size shouldBe 1
                positions[0].amount shouldBe 2.0
                positions[0].type shouldBe Position.Type.BOUGHT
            }
        }

        "does nothing when player has insufficient balance" {
            runTest {
                val sp = player(balance = 0.0)
                val s = stock(price = 100.0)
                StockMarket.stockRepo.save(s)
                StockPlayerManager.playerRepo.save(sp)

                StockPlayerManager.buyStock(sp, s, amount = 100.0, leverage = 1, lowerBound = 0.0, upperBound = 0.0)

                sp.positions() shouldBe emptyList()
            }
        }
    }

    "shortStock()" - {
        "adds SHORT position to player" {
            runTest {
                val sp = player()
                val s = stock(price = 100.0)
                StockMarket.stockRepo.save(s)
                StockPlayerManager.playerRepo.save(sp)

                StockPlayerManager.shortStock(sp, s, amount = 1.0, leverage = 1, lowerBound = 10000.0, upperBound = 10000.0)

                val positions = sp.positions("AAPL")
                positions.shouldNotBeNull()
                positions[0].type shouldBe Position.Type.SHORTED
            }
        }

        "deducts cost from player balance" {
            runTest {
                val sp = player()
                val s = stock(price = 100.0)
                StockMarket.stockRepo.save(s)
                StockPlayerManager.playerRepo.save(sp)
                val before = sp.getBalance()

                StockPlayerManager.shortStock(sp, s, amount = 1.0, leverage = 1, lowerBound = 10000.0, upperBound = 10000.0)

                sp.getBalance() shouldBeLessThan before
            }
        }
    }

    "StockPlayer.remove()" - {
        "removes position by uuid" {
            val sp = player()
            val position = Position(
                symbol = "AAPL",
                startPrice = 100.0,
                leverage = 1.0,
                upperBoundMargin = 0.0,
                lowerBoundMargin = 0.0,
                commission = 1.0,
                timestamp = 0L,
                positionUuid = UUID.randomUUID(),
                type = Position.Type.BOUGHT,
                amount = 1.0,
                iconMaterial = Material.PAPER,
                receivedDividend = 0.0,
            )
            sp.addPosition(position)

            val removed = sp.remove("AAPL", position.positionUuid)

            removed shouldBe position
            sp.positions("AAPL").shouldBeNull()
        }

        "returns null when position uuid does not match" {
            val sp = player()
            val position = Position(
                symbol = "AAPL",
                startPrice = 100.0,
                leverage = 1.0,
                upperBoundMargin = 0.0,
                lowerBoundMargin = 0.0,
                commission = 1.0,
                timestamp = 0L,
                positionUuid = UUID.randomUUID(),
                type = Position.Type.BOUGHT,
                amount = 1.0,
                iconMaterial = Material.PAPER,
                receivedDividend = 0.0,
            )
            sp.addPosition(position)

            val removed = sp.remove("AAPL", UUID.randomUUID())

            removed.shouldBeNull()
            sp.positions("AAPL")!!.size shouldBe 1
        }

        "removes position list entry when last position removed" {
            val sp = player()
            val uuid = UUID.randomUUID()
            sp.addPosition(
                Position(
                    symbol = "GOOG",
                    startPrice = 100.0,
                    leverage = 1.0,
                    upperBoundMargin = 0.0,
                    lowerBoundMargin = 0.0,
                    commission = 1.0,
                    timestamp = 0L,
                    positionUuid = uuid,
                    type = Position.Type.BOUGHT,
                    amount = 1.0,
                    iconMaterial = Material.PAPER,
                    receivedDividend = 0.0,
                )
            )

            sp.remove("GOOG", uuid)

            sp.positions("GOOG").shouldBeNull()
        }
    }

    "getNow()" - {
        "returns null when player not in cache" {
            StockPlayerManager.getNow(UUID.randomUUID()).shouldBeNull()
        }

        "returns player when in cache" {
            runTest {
                val sp = player()
                StockPlayerManager.playerRepo.save(sp)

                StockPlayerManager.getNow(sp.playerUuid).shouldNotBeNull()
            }
        }
    }
})
