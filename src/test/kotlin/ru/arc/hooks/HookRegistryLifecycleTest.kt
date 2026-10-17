package ru.arc.hooks

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.hooks.packetevents.PacketEventsHook
import java.util.concurrent.CompletableFuture

class HookRegistryLifecycleTest :
    FreeSpec({
        afterTest {
            HookRegistry.auctionHook = null
            HookRegistry.jobsEnabled = false
            HookRegistry.packetEventsHook = null
        }

        "close stops the Jobs hook when it was enabled" {
            var shutdowns = 0
            HookRegistry.jobsEnabled = true
            val registry = HookRegistry(stopJobs = { shutdowns++ })

            registry.close()

            shutdowns shouldBeExactly 1
            HookRegistry.jobsEnabled.shouldBeFalse()
            registry.isClosed.shouldBeTrue()
        }

        "close is idempotent" {
            var shutdowns = 0
            HookRegistry.jobsEnabled = true
            val registry = HookRegistry(stopJobs = { shutdowns++ })

            registry.close()
            registry.close()

            shutdowns shouldBeExactly 1
        }

        "close does not touch the Jobs hook when it was not enabled" {
            var shutdowns = 0

            HookRegistry(stopJobs = { shutdowns++ }).close()

            shutdowns shouldBeExactly 0
        }

        "close clears lifecycle state when Jobs shutdown fails" {
            HookRegistry.jobsEnabled = true
            val registry = HookRegistry(stopJobs = { error("shutdown failed") })

            shouldThrow<IllegalStateException> {
                registry.close()
            }

            HookRegistry.jobsEnabled.shouldBeFalse()
            registry.isClosed.shouldBeTrue()
            registry.close()
        }

        "a closed registry cannot be initialized again" {
            val registry = HookRegistry()
            registry.close()

            shouldThrow<IllegalStateException> {
                registry.setupHooks()
            }
        }

        "retirement detaches globals immediately and waits for the exact captured packet owner" {
            val actual = CompletableFuture<Void>()
            val packets = mockk<PacketEventsHook> { every { closeAsync() } returns actual.copy() }
            HookRegistry.packetEventsHook = packets
            val registry = HookRegistry()
            val cancelled = registry.closeAsync()
            HookRegistry.packetEventsHook shouldBe null
            registry.isClosed.shouldBeTrue()
            cancelled.cancel(false).shouldBeTrue()
            val observation = registry.closeAsync()
            observation.isDone.shouldBeFalse()
            val replacement = mockk<PacketEventsHook>(relaxed = true)
            HookRegistry.packetEventsHook = replacement
            actual.complete(null)
            observation.join() shouldBe null
            verify(exactly = 1) { packets.closeAsync() }
            verify(exactly = 0) { replacement.closeAsync() }
        }
    })
