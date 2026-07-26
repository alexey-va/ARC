package ru.arc.hooks

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.KotestTestBase

class HookRegistryOwnershipTest :
    KotestTestBase({
        describe("HookRegistry listener ownership") {
            it("unregisters and clears every vanilla listener it owns") {
                val registry = ARC.hookRegistry ?: error("Hooks module was not initialized")
                val ownedListeners =
                    listOfNotNull(
                        registry.chatListener,
                        registry.commandListener,
                        registry.spawnerListener,
                        registry.blockListener,
                        registry.joinListener,
                        registry.pickupListener,
                        registry.respawnListener,
                    )
                ownedListeners.shouldNotBeEmpty()

                registry.close()

                registry.chatListener.shouldBeNull()
                registry.commandListener.shouldBeNull()
                registry.spawnerListener.shouldBeNull()
                registry.blockListener.shouldBeNull()
                registry.joinListener.shouldBeNull()
                registry.pickupListener.shouldBeNull()
                registry.respawnListener.shouldBeNull()
                ownedListeners.forEach { listener ->
                    HandlerList
                        .getRegisteredListeners(plugin)
                        .any { it.listener === listener }
                        .shouldBeFalse()
                }
            }
        }
    })
