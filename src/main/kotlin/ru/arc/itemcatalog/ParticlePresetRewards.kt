package ru.arc.itemcatalog

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.node.types.PermissionNode
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/** Grants only the fixed, configured PlayerParticles cosmetic entitlements. */
internal class ParticlePresetRewards(
    private val runtime: Runtime = BukkitRuntime(),
) {
    internal interface Runtime {
        fun providersReady(): Boolean
        fun hasVerifiedPreset(id: String): Boolean
        fun hasPermission(player: Player, permission: String): Boolean
        fun addPermission(playerId: UUID, permission: String): CompletableFuture<DataMutateResult>
    }

    fun ready(id: String): Boolean =
        id in ParticlePresetEntitlements.IDS &&
            runCatching { runtime.providersReady() && runtime.hasVerifiedPreset(id) }.getOrDefault(false)

    /** Returns a player-facing rejection, or null when the certificate is usable. */
    fun canRedeem(player: Player, id: String): String? {
        if (!ready(id)) return UNAVAILABLE
        return if (runtime.hasPermission(player, ParticlePresetEntitlements.permission(id))) DUPLICATE else null
    }

    /** LP's modifyUser future completes after its durable save; no Bukkit access occurs in completion callbacks. */
    fun redeem(player: Player, id: String): CompletableFuture<PhysicalRewardOutcome> {
        val rejection = canRedeem(player, id)
        if (rejection != null) return CompletableFuture.completedFuture(PhysicalRewardOutcome.Rejected(rejection))

        val playerId = player.uniqueId
        val permission = ParticlePresetEntitlements.permission(id)
        return try {
            runtime.addPermission(playerId, permission).handle { result, failure ->
                when {
                    failure != null -> PhysicalRewardOutcome.Uncertain(UNCERTAIN)
                    result == DataMutateResult.SUCCESS -> PhysicalRewardOutcome.Applied
                    result == DataMutateResult.FAIL_ALREADY_HAS -> PhysicalRewardOutcome.Rejected(DUPLICATE)
                    else -> PhysicalRewardOutcome.Uncertain(UNCERTAIN)
                }
            }
        } catch (_: Throwable) {
            // modifyUser may have begun a save before throwing; keep the voucher claim recoverable.
            CompletableFuture.completedFuture(PhysicalRewardOutcome.Uncertain(UNCERTAIN))
        }
    }

    private class BukkitRuntime : Runtime {
        private companion object {
            const val PLAYER_PARTICLES = "PlayerParticles"
            const val LUCK_PERMS = "LuckPerms"
            const val PLAYER_PARTICLES_VERSION = "8.13"
            const val PRESET_MANAGER_CLASS = "dev.esophose.playerparticles.manager.ParticleGroupPresetManager"
        }

        private data class ActivePresets(val manager: Any, val groups: Map<String, Any>)

        private data class ContractSnapshot(
            val manager: Any,
            val groups: Map<String, Any>,
            val eligibleIds: Set<String>,
        )

        private val snapshotLock = Any()
        @Volatile private var contractSnapshot: ContractSnapshot? = null

        override fun providersReady(): Boolean {
            val plugins = Bukkit.getPluginManager()
            return plugins.isPluginEnabled(PLAYER_PARTICLES) && plugins.isPluginEnabled(LUCK_PERMS)
        }

        override fun hasVerifiedPreset(id: String): Boolean {
            val active = readActivePresets() ?: return false
            val snapshot = synchronized(snapshotLock) {
                val previous = contractSnapshot
                if (previous == null || !sameGeneration(previous, active)) {
                    ContractSnapshot(active.manager, active.groups, loadVerifiedConfiguredPresetIds()).also {
                        contractSnapshot = it
                    }
                } else {
                    previous
                }
            }
            return id in snapshot.eligibleIds && id in snapshot.groups
        }

        override fun hasPermission(player: Player, permission: String): Boolean = player.hasPermission(permission)

        override fun addPermission(playerId: UUID, permission: String): CompletableFuture<DataMutateResult> {
            val result = AtomicReference<DataMutateResult?>()
            return LuckPermsProvider.get().userManager.modifyUser(playerId) { user ->
                result.set(user.data().add(PermissionNode.builder(permission).build()))
            }.thenApply { result.get() ?: DataMutateResult.FAIL }
        }

        private fun loadVerifiedConfiguredPresetIds(): Set<String> {
            val plugin = Bukkit.getPluginManager().getPlugin(PLAYER_PARTICLES) ?: return emptySet()
            if (!plugin.isEnabled || plugin.description.version != PLAYER_PARTICLES_VERSION) return emptySet()

            val pluginConfigFile = plugin.dataFolder.resolve("config.yml")
            val presetConfigFile = plugin.dataFolder.resolve("preset_groups.yml")
            if (!pluginConfigFile.isFile || !presetConfigFile.isFile) return emptySet()

            val pluginConfig = YamlConfiguration.loadConfiguration(pluginConfigFile)
            if (pluginConfig.get("gui-enabled") != true ||
                pluginConfig.get("gui-require-permission") != false ||
                pluginConfig.get("gui-require-effects-and-styles") != false ||
                pluginConfig.get("preset-groups-allow-overlapping") != false
            ) return emptySet()

            val config = YamlConfiguration.loadConfiguration(presetConfigFile)
            return ParticlePresetEntitlements.IDS.filterTo(linkedSetOf()) { id ->
                val expectedPermission = ParticlePresetEntitlements.permission(id)
                config.getKeys(false).any { pageId ->
                    val preset = config.getConfigurationSection(pageId)
                        ?.getConfigurationSection("presets")
                        ?.getConfigurationSection(id)
                    preset != null && preset.get("permission") == expectedPermission &&
                        preset.get("allow-permission-override") == true
                }
            }
        }

        /** PlayerParticles exposes this public lookup but has no public permission/override accessor. */
        private fun readActivePresets(): ActivePresets? = runCatching {
            val plugin = Bukkit.getPluginManager().getPlugin(PLAYER_PARTICLES) ?: return null
            if (!plugin.isEnabled || plugin.description.version != PLAYER_PARTICLES_VERSION) return null
            val loader = plugin.javaClass.classLoader
            val managerType = Class.forName(PRESET_MANAGER_CLASS, false, loader)
            val manager = plugin.javaClass.getMethod("getManager", Class::class.java).invoke(plugin, managerType)
                ?: return null
            val getPresetGroup = managerType.getMethod("getPresetGroup", String::class.java)
            val groups = ParticlePresetEntitlements.IDS.mapNotNull { id ->
                getPresetGroup.invoke(manager, id)?.let { id to it }
            }.toMap()
            ActivePresets(manager, groups)
        }.getOrNull()

        private fun sameGeneration(previous: ContractSnapshot, current: ActivePresets): Boolean =
            previous.manager === current.manager &&
                previous.groups.keys == current.groups.keys &&
                previous.groups.all { (id, preset) -> current.groups[id] === preset }
    }

    private companion object {
        val UNAVAILABLE = RewardCatalogMessages.DEFAULT.unavailable
        const val DUPLICATE = "<gold>Эта косметика уже открыта. Сертификат можно передать другому игроку."
        const val UNCERTAIN = "<#ffcb70>Выдача сохранена для проверки администрацией."
    }
}
