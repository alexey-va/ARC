package ru.arc.hooks

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldHaveSize
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.KotestTestBase
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.hooks.citizens.CitizensListener

class ExternalHookListenerOwnershipTest :
    KotestTestBase({
        describe("external hook listener ownership") {
            it("HookRegistry close unregisters the Citizens listener") {
                val registry = ARC.hookRegistry ?: error("Hooks module was not initialized")
                val existing =
                    HandlerList
                        .getRegisteredListeners(plugin)
                        .map { it.listener }
                        .toSet()
                val hook = CitizensHook()
                hook.registerListeners()
                HookRegistry.citizensHook = hook
                val owned =
                    HandlerList
                        .getRegisteredListeners(plugin)
                        .map { it.listener }
                        .filterIsInstance<CitizensListener>()
                        .filterNot(existing::contains)
                owned.shouldHaveSize(1)

                registry.close()

                HandlerList
                    .getRegisteredListeners(plugin)
                    .any { it.listener === owned.single() }
                    .shouldBeFalse()
            }

            it("CitizensHook registration and close are idempotent") {
                val existing =
                    HandlerList
                        .getRegisteredListeners(plugin)
                        .map { it.listener }
                        .toSet()
                val hook = CitizensHook()

                hook.registerListeners()
                hook.registerListeners()

                val owned =
                    HandlerList
                        .getRegisteredListeners(plugin)
                        .map { it.listener }
                        .filterIsInstance<CitizensListener>()
                        .filterNot(existing::contains)
                owned.shouldHaveSize(1)

                hook.close()
                hook.close()

                HandlerList
                    .getRegisteredListeners(plugin)
                    .any { it.listener === owned.single() }
                    .shouldBeFalse()
                shouldThrow<IllegalStateException> {
                    hook.registerListeners()
                }
            }
        }
    })
