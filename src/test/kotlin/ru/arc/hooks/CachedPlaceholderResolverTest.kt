package ru.arc.hooks

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CachedPlaceholderResolverTest :
    DescribeSpec({
        fun player(id: String): OfflinePlayer =
            mockk {
                every { uniqueId } returns UUID.fromString(id)
            }

        val firstPlayer = player("00000000-0000-0000-0000-000000000001")
        val sameFirstPlayer = player("00000000-0000-0000-0000-000000000001")
        val secondPlayer = player("00000000-0000-0000-0000-000000000002")

        describe("cache semantics") {
            it("caches a value inside the TTL and recomputes at the boundary") {
                var now = 0L
                var calls = 0
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, _ -> "value-${++calls}" },
                    ticker = { now },
                )

                resolver.resolve(firstPlayer, "cache_5_demo_value") shouldBe "value-1"
                now = 4_999_999_999L
                resolver.resolve(firstPlayer, "cache_5_demo_value") shouldBe "value-1"
                now = 5_000_000_000L
                resolver.resolve(firstPlayer, "cache_5_demo_value") shouldBe "value-2"
                calls shouldBe 2
            }

            it("isolates players, shares entries for the same UUID, and separates server context") {
                var calls = 0
                val resolver = CachedPlaceholderResolver(
                    delegate = { player, _ ->
                        "${player?.uniqueId ?: "server"}-${++calls}"
                    },
                )

                resolver.resolve(firstPlayer, "cache_30_demo_value") shouldBe
                    resolver.resolve(sameFirstPlayer, "cache_30_demo_value")
                resolver.resolve(secondPlayer, "cache_30_demo_value") shouldBe
                    "00000000-0000-0000-0000-000000000002-2"
                resolver.resolve(null, "cache_30_demo_value") shouldBe "server-3"
                resolver.resolve(null, "cache_30_demo_value") shouldBe "server-3"
                calls shouldBe 3
            }

            it("uses TTL as part of the key and preserves exact inner parameters") {
                val seen = mutableListOf<String>()
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, token ->
                        seen += token
                        "value-${seen.size}"
                    },
                )

                resolver.resolve(firstPlayer, "cache_5_formatter_number_{CMI_PlayTime}") shouldBe "value-1"
                resolver.resolve(firstPlayer, "cache_30_formatter_number_{CMI_PlayTime}") shouldBe "value-2"
                seen.shouldContainExactly(
                    "%formatter_number_{CMI_PlayTime}%",
                    "%formatter_number_{CMI_PlayTime}%",
                )
            }

            it("caches an empty result") {
                var calls = 0
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, _ ->
                        calls++
                        ""
                    },
                )

                resolver.resolve(firstPlayer, "cache_30_demo_empty") shouldBe ""
                resolver.resolve(firstPlayer, "cache_30_demo_empty") shouldBe ""
                calls shouldBe 1
            }

            it("supports a PlaceholderAPI expansion without parameters") {
                var calls = 0
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, token ->
                        calls++
                        "resolved:$token"
                    },
                )

                resolver.resolve(firstPlayer, "cache_30_demo") shouldBe "resolved:%demo%"
                resolver.resolve(firstPlayer, "cache_30_demo") shouldBe "resolved:%demo%"
                calls shouldBe 1
            }

            it("does not cache unresolved or oversized results") {
                var unresolvedCalls = 0
                val unresolved = CachedPlaceholderResolver(
                    delegate = { _, token ->
                        unresolvedCalls++
                        token
                    },
                )
                unresolved.resolve(firstPlayer, "cache_30_missing_value") shouldBe "%missing_value%"
                unresolved.resolve(firstPlayer, "cache_30_missing_value") shouldBe "%missing_value%"
                unresolvedCalls shouldBe 2

                var oversizedCalls = 0
                val oversized = CachedPlaceholderResolver(
                    delegate = { _, _ ->
                        oversizedCalls++
                        "long"
                    },
                    maxValueLength = 3,
                )
                oversized.resolve(firstPlayer, "cache_30_demo_value") shouldBe "long"
                oversized.resolve(firstPlayer, "cache_30_demo_value") shouldBe "long"
                oversizedCalls shouldBe 2
            }

            it("keeps a bounded access-order LRU") {
                val calls = mutableMapOf<String, Int>()
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, token ->
                        val count = calls.getOrDefault(token, 0) + 1
                        calls[token] = count
                        "$token-$count"
                    },
                    capacity = 2,
                )

                resolver.resolve(firstPlayer, "cache_30_demo_a")
                resolver.resolve(firstPlayer, "cache_30_demo_b")
                resolver.resolve(firstPlayer, "cache_30_demo_a")
                resolver.resolve(firstPlayer, "cache_30_demo_c")
                resolver.size() shouldBe 2
                resolver.resolve(firstPlayer, "cache_30_demo_b") shouldBe "%demo_b%-2"
                resolver.size().shouldBeLessThanOrEqual(2)
            }
        }

        describe("validation and recursion safety") {
            it("rejects malformed, unsafe, relational, and directly nested requests") {
                var calls = 0
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, _ ->
                        calls++
                        "unexpected"
                    },
                )
                val overlong = "x".repeat(CachedPlaceholderResolver.MAX_INNER_LENGTH + 1)
                val invalid = listOf(
                    "other_30_demo_value",
                    "cache_0_demo_value",
                    "cache_301_demo_value",
                    "cache_-1_demo_value",
                    "cache_1.5_demo_value",
                    "cache_30",
                    "cache_30_%demo_value",
                    "cache_30_demo value",
                    "cache_30_demo\nvalue",
                    "cache_30_arc_cache_30_demo_value",
                    "cache_30_REL_demo_value",
                    "cache_30_${overlong}_value",
                    "cache_0001_demo_value",
                )

                invalid.forEach { resolver.resolve(firstPlayer, it).shouldBeNull() }
                calls shouldBe 0
            }

            it("blocks indirect cache re-entry and removes the guard afterwards") {
                lateinit var resolver: CachedPlaceholderResolver
                var nested: String? = "not-called"
                var calls = 0
                resolver = CachedPlaceholderResolver(
                    delegate = { player, _ ->
                        calls++
                        nested = resolver.resolve(player, "cache_30_other_value")
                        "outer"
                    },
                )

                resolver.resolve(firstPlayer, "cache_30_demo_value") shouldBe "outer"
                nested.shouldBeNull()
                resolver.clear()
                resolver.resolve(firstPlayer, "cache_30_demo_value") shouldBe "outer"
                calls shouldBe 2
            }

            it("propagates delegate failures and clears the re-entry guard") {
                var fail = true
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, _ ->
                        if (fail) error("boom")
                        "recovered"
                    },
                )

                shouldThrow<IllegalStateException> {
                    resolver.resolve(firstPlayer, "cache_30_demo_value")
                }
                fail = false
                resolver.resolve(firstPlayer, "cache_30_demo_value") shouldBe "recovered"
            }
        }

        describe("invalidation and concurrency") {
            it("serializes timestamp sampling with cache mutations") {
                val firstTickerEntered = CountDownLatch(1)
                val releaseTicker = CountDownLatch(1)
                val overlappingTicker = CountDownLatch(1)
                val activeTickerCalls = AtomicInteger()
                val clock = AtomicLong()
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, token -> token.removeSurrounding("%") },
                    ticker = {
                        if (activeTickerCalls.incrementAndGet() > 1) {
                            overlappingTicker.countDown()
                        }
                        firstTickerEntered.countDown()
                        releaseTicker.await(5, TimeUnit.SECONDS)
                        val now = clock.incrementAndGet()
                        activeTickerCalls.decrementAndGet()
                        now
                    },
                )
                val executor = Executors.newFixedThreadPool(2)
                val first = executor.submit<String?> {
                    resolver.resolve(firstPlayer, "cache_30_demo_a")
                }
                firstTickerEntered.await(5, TimeUnit.SECONDS) shouldBe true
                val second = executor.submit<String?> {
                    resolver.resolve(secondPlayer, "cache_30_demo_b")
                }

                val overlapped = overlappingTicker.await(250, TimeUnit.MILLISECONDS)
                releaseTicker.countDown()
                first.get(5, TimeUnit.SECONDS) shouldBe "demo_a"
                second.get(5, TimeUnit.SECONDS) shouldBe "demo_b"
                executor.shutdownNow()
                overlapped shouldBe false
            }

            it("clears all entries when the monotonic clock moves backwards") {
                var now = 100L
                val calls = mutableMapOf<String, Int>()
                val resolver = CachedPlaceholderResolver(
                    delegate = { _, token ->
                        calls[token] = calls.getOrDefault(token, 0) + 1
                        "$token-${calls[token]}"
                    },
                    ticker = { now },
                )

                resolver.resolve(firstPlayer, "cache_30_demo_a")
                now = 110L
                resolver.resolve(firstPlayer, "cache_30_demo_b")
                now = 90L
                resolver.resolve(firstPlayer, "cache_30_demo_a") shouldBe "%demo_a%-2"
                resolver.resolve(firstPlayer, "cache_30_demo_b") shouldBe "%demo_b%-2"
            }

            it("does not repopulate an entry cleared during an in-flight miss") {
                var calls = 0
                lateinit var resolver: CachedPlaceholderResolver
                resolver = CachedPlaceholderResolver(
                    delegate = { _, _ ->
                        calls++
                        if (calls == 1) resolver.clear()
                        "value-$calls"
                    },
                )

                resolver.resolve(firstPlayer, "cache_30_demo_value") shouldBe "value-1"
                resolver.size() shouldBe 0
                resolver.resolve(firstPlayer, "cache_30_demo_value") shouldBe "value-2"
                calls shouldBe 2
            }

            it("remains bounded and player-isolated under concurrent misses") {
                val threads = 8
                val start = CountDownLatch(1)
                val done = CountDownLatch(threads)
                val failures = ConcurrentLinkedQueue<Throwable>()
                val calls = AtomicInteger()
                val resolver = CachedPlaceholderResolver(
                    delegate = { player, token ->
                        calls.incrementAndGet()
                        "${player?.uniqueId}:$token"
                    },
                    capacity = 8,
                )
                val executor = Executors.newFixedThreadPool(threads)
                repeat(threads) { index ->
                    executor.execute {
                        try {
                            start.await()
                            repeat(100) { iteration ->
                                val target = if ((index + iteration) % 2 == 0) firstPlayer else secondPlayer
                                val expected = "${target.uniqueId}:%demo_${iteration % 8}%"
                                resolver.resolve(target, "cache_30_demo_${iteration % 8}") shouldBe expected
                            }
                        } catch (failure: Throwable) {
                            failures += failure
                        } finally {
                            done.countDown()
                        }
                    }
                }

                start.countDown()
                done.await(10, TimeUnit.SECONDS) shouldBe true
                executor.shutdownNow()
                failures.toList() shouldBe emptyList()
                resolver.size().shouldBeLessThanOrEqual(8)
                calls.get() shouldBeLessThanOrEqual threads * 100
            }
        }
    })
