package ru.arc.survival

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.SoundCategory
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.entity.Wither
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Event
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.raid.RaidSpawnWaveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.util.UUID

internal data class SurvivalGameplaySettings(
    val enabled: Boolean,
    val witherWorld: String,
    val witherAgeLimitTicks: Int,
    val witherSweepPeriodTicks: Long,
    val spawnerChanceMessage: String,
) {
    companion object {
        private const val DEFAULT_SPAWNER_MESSAGE = "⛏ Спавнер выпадет с шансом <chance>% с шелковым касанием"

        fun load(source: Config): SurvivalGameplaySettings {
            val world = source.string("wither-cleanup.world", "survival").trim()
            require(world.isNotEmpty()) { "survival-gameplay.wither-cleanup.world must not be blank" }
            val message = source.string("spawner.chance-message", DEFAULT_SPAWNER_MESSAGE).trim()
            require(message.isNotEmpty()) { "survival-gameplay.spawner.chance-message must not be blank" }
            return SurvivalGameplaySettings(
                enabled = source.bool("enabled", false),
                witherWorld = world,
                witherAgeLimitTicks = source.integer("wither-cleanup.age-limit-ticks", 144_000).coerceAtLeast(1),
                witherSweepPeriodTicks = source.long("wither-cleanup.period-ticks", 1_200L).coerceAtLeast(1L),
                spawnerChanceMessage = message,
            )
        }
    }
}

enum class SoulbindUnbindResult {
    DISABLED,
    DENIED,
    NO_ITEM,
    NOT_SOULBOUND,
    UNBOUND,
}

/** Thin adapter over EliteMobs' existing item PDC; unrelated native metadata is retained. */
internal object SurvivalSoulbind {
    const val UNBIND_PERMISSION = "denizencommand.remove-soulbind"
    private val soulbindKey = NamespacedKey("elitemobs", "soulbind")
    private val unbindScrollKey = NamespacedKey("elitemobs", "unbind_scroll.yml")
    private val plainText = PlainTextComponentSerializer.plainText()

    fun unbindHeldItem(player: Player): SoulbindUnbindResult {
        if (!player.hasPermission(UNBIND_PERMISSION)) return SoulbindUnbindResult.DENIED
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) return SoulbindUnbindResult.NO_ITEM
        if (!removeSoulbind(item, "Привязан")) return SoulbindUnbindResult.NOT_SOULBOUND
        player.inventory.setItemInMainHand(item)
        return SoulbindUnbindResult.UNBOUND
    }

    fun unbindScroll(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        val data = meta.persistentDataContainer
        if (!data.has(soulbindKey) || !data.has(unbindScrollKey)) return false
        data.remove(soulbindKey)
        meta.lore(removeFirstLoreContaining(meta.lore(), "asd"))
        item.itemMeta = meta
        return true
    }

    internal fun hasSoulbind(item: ItemStack): Boolean =
        item.itemMeta?.persistentDataContainer?.has(soulbindKey) == true

    private fun removeSoulbind(item: ItemStack, loreFragment: String): Boolean {
        val meta = item.itemMeta ?: return false
        val data = meta.persistentDataContainer
        if (!data.has(soulbindKey)) return false
        data.remove(soulbindKey)
        meta.lore(removeFirstLoreContaining(meta.lore(), loreFragment))
        item.itemMeta = meta
        return true
    }

    private fun removeFirstLoreContaining(lore: List<Component>?, fragment: String): List<Component>? {
        if (lore == null) return null
        val index = lore.indexOfFirst { plainText.serialize(it).contains(fragment, ignoreCase = true) }
        return if (index < 0) lore else lore.toMutableList().also { it.removeAt(index) }
    }
}

/** Survival gameplay events retired from Denizen. Registered only by the owning Survival profile. */
internal class SurvivalGameplayListener(
    private val settings: SurvivalGameplaySettings,
    private val chancePercent: (Player) -> String? = ::resolveSpawnerChance,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : Listener, AutoCloseable {
    private val cakeKey = NamespacedKey("arc", "throwable_cake_projectile")
    private val cakeProjectiles = linkedMapOf<UUID, FallingBlock>()
    private var started = false

    fun start() {
        check(!started) { "Survival gameplay listener already started" }
        started = true
        tasks.runTimer(settings.witherSweepPeriodTicks, settings.witherSweepPeriodTicks, ::removeOldWithers)
    }

    fun isAvailable(): Boolean = started

    fun unbindHeldItem(player: Player): SoulbindUnbindResult =
        if (!started) SoulbindUnbindResult.DISABLED else SurvivalSoulbind.unbindHeldItem(player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun glowRaidWave(event: RaidSpawnWaveEvent) {
        event.raiders.forEach { it.isGlowing = true }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun messageSpawnerChance(event: BlockBreakEvent) {
        if (event.isCancelled || event.block.type != Material.SPAWNER) return
        val chance = chancePercent(event.player)?.takeIf(CHANCE_VALUE::matches) ?: return
        event.player.sendMessage(TextUtil.mm(settings.spawnerChanceMessage, Placeholder.unparsed("chance", chance)))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun preventWitherNaming(event: PlayerInteractEntityEvent) {
        if (event.isCancelled || event.rightClicked !is Wither) return
        val hand = event.hand
        if (event.player.equipment.getItem(hand).type == Material.NAME_TAG) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun throwCake(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR || event.hand != EquipmentSlot.HAND) return
        // Air clicks begin with block-use DENY because there is no clicked block. Like
        // Denizen's bridge, only item-use DENY represents a protection veto here.
        if (event.useItemInHand() == Event.Result.DENY) return
        if (event.item?.type != Material.CAKE || event.player.inventory.itemInMainHand.type != Material.CAKE) return

        val player = event.player
        val eye = player.eyeLocation
        val forward = eye.direction.normalize()
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val left = Vector(kotlin.math.cos(yaw), 0.0, -kotlin.math.sin(yaw))
        val localUp = forward.clone().crossProduct(left).normalize()
        val origin = eye.clone().add(forward).subtract(localUp.multiply(0.5))
        val cake = player.world.spawnFallingBlock(origin, Material.CAKE.createBlockData())
        cake.dropItem = false
        cake.cancelDrop = true
        cake.setHurtEntities(false)
        cake.shouldAutoExpire(true)
        cake.persistentDataContainer.set(cakeKey, PersistentDataType.BYTE, 1)
        cake.velocity = forward.multiply(0.7)
        cakeProjectiles[cake.uniqueId] = cake
        tasks.runLater(CAKE_MAX_LIFETIME_TICKS) { removeCake(cake.uniqueId) }

        player.playSound(player.location, "minecraft:entity.snowball.throw", SoundCategory.PLAYERS, 1f, 0.6f)
        player.swingMainHand()
        if (player.gameMode != GameMode.CREATIVE) consumeOneCake(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun preventCakePlacement(event: EntityChangeBlockEvent) {
        val cake = event.entity as? FallingBlock ?: return
        if (!isCakeProjectile(cake)) return
        event.isCancelled = true
        removeCake(cake.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun unbindScrollOnPickup(event: EntityPickupItemEvent) {
        if (event.isCancelled || event.entity !is Player) return
        val stack = event.item.itemStack
        if (SurvivalSoulbind.unbindScroll(stack)) event.item.itemStack = stack
    }

    private fun removeOldWithers() {
        val world = Bukkit.getWorld(settings.witherWorld) ?: return
        if (world.name != settings.witherWorld) return
        world.getEntitiesByClass(Wither::class.java)
            .filter { it.ticksLived > settings.witherAgeLimitTicks }
            .forEach(Wither::remove)
    }

    private fun isCakeProjectile(entity: FallingBlock): Boolean =
        entity.persistentDataContainer.has(cakeKey, PersistentDataType.BYTE)

    private fun removeCake(id: UUID) {
        cakeProjectiles.remove(id)?.takeUnless { it.isDead }?.remove()
    }

    private fun consumeOneCake(player: Player) {
        val held = player.inventory.itemInMainHand
        if (held.type != Material.CAKE) return
        if (held.amount <= 1) player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        else held.amount -= 1
    }

    override fun close() {
        if (!started) {
            tasks.close()
            return
        }
        started = false
        tasks.close()
        cakeProjectiles.values.forEach { if (!it.isDead) it.remove() }
        cakeProjectiles.clear()
    }

    companion object {
        private const val CAKE_MAX_LIFETIME_TICKS = 100L
        private val CHANCE_VALUE = Regex("(?:\\d+(?:\\.\\d*)?|\\.\\d+)")

        private fun resolveSpawnerChance(player: Player): String? {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI")?.isEnabled != true) return null
            return runCatching {
                me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                    player,
                    "%cmi_user_maxperm_cmi.dropspawner.basedropchance_10%",
                )
            }.getOrNull()?.takeIf(CHANCE_VALUE::matches)
        }
    }
}

object SurvivalGameplayModule : PluginModule {
    override val name = "SurvivalGameplay"
    override val priority = 94

    private var runtime: SurvivalGameplayListener? = null
    val available: Boolean get() = runtime?.isAvailable() == true

    override fun init() {
        shutdown()
        val config = ConfigManager.ofModule(ARC.instance.dataPath, CONFIG_RESOURCE)
        val settings = runCatching { SurvivalGameplaySettings.load(config) }
            .onFailure { warn("Survival gameplay configuration rejected: {}", it.message ?: it.javaClass.simpleName) }
            .getOrNull() ?: return
        if (!settings.enabled) {
            info("Survival gameplay module disabled by configuration")
            return
        }

        val listener = SurvivalGameplayListener(settings)
        try {
            Bukkit.getPluginManager().registerEvents(listener, ARC.instance)
            listener.start()
            runtime = listener
            info("Survival gameplay module initialized")
        } catch (failure: Exception) {
            org.bukkit.event.HandlerList.unregisterAll(listener)
            listener.close()
            warn("Survival gameplay module could not start: {}", failure.message ?: failure.javaClass.simpleName)
        }
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        runtime?.let {
            org.bukkit.event.HandlerList.unregisterAll(it)
            it.close()
        }
        runtime = null
    }

    fun unbindHeldItem(player: Player): SoulbindUnbindResult =
        runtime?.unbindHeldItem(player) ?: SoulbindUnbindResult.DISABLED

    private const val CONFIG_RESOURCE = "survival-gameplay.yml"
}
