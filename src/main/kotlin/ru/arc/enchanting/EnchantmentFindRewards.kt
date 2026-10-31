package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import ru.arc.ops.ItemPresets
import ru.ruscrafting.ecia.api.CrateLocationService
import java.util.concurrent.CompletableFuture

/** Prepares one physical reward without giving it to a player; only the find's later claim issues it. */
internal object EnchantmentFindRewards {
    fun resolve(kind: EnchantmentFindReward): CompletableFuture<ItemStack?> {
        check(Bukkit.isPrimaryThread())
        return try {
            when (kind) {
                EnchantmentFindReward.LOOTBOX -> CompletableFuture.completedFuture(
                    ItemPresets.resolveStacks("ae_lootbox", 1).getOrThrow().singleOrNull()
                        ?.takeIf { isEnchantmentLootbox(it) }?.clone(),
                )
                EnchantmentFindReward.KEY -> NativeKeys.resolve()
            }
        } catch (failure: Exception) {
            CompletableFuture.failedFuture(failure)
        } catch (failure: LinkageError) {
            CompletableFuture.failedFuture(failure)
        }
    }

    /** Kept separate so an absent optional crate API cannot disable AE lootboxes. */
    private object NativeKeys {
        fun resolve(): CompletableFuture<ItemStack?> {
            val owner = Bukkit.getPluginManager().getPlugin("ArcExcellentCrates")?.takeIf { it.isEnabled }
                ?: return CompletableFuture.completedFuture(null)
            val registration = Bukkit.getServicesManager().getRegistration(CrateLocationService::class.java)
                ?.takeIf { it.plugin === owner } ?: return CompletableFuture.completedFuture(null)
            return registration.provider.keyTemplateAsync("case_daily").thenApply { stack ->
                stack?.takeIf { !it.type.isAir && it.amount == 1 }?.clone()
            }
        }
    }
}
