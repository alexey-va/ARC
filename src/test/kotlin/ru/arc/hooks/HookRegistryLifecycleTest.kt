package ru.arc.hooks

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly

class HookRegistryLifecycleTest :
    FreeSpec({
        afterTest {
            HookRegistry.emHook = null
            HookRegistry.auctionHook = null
            HookRegistry.jobsEnabled = false
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
    })
