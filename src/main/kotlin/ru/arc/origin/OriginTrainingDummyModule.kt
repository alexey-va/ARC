package ru.arc.origin

import com.denizenscript.denizen.objects.PlayerTag
import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.event.NPCSpawnEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.origin.scene.OriginAmbientScenesModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.showTitleMM
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class OriginTrainingDummyConfig(
    val world: String,
    val dummyNpcId: Int,
    val trainerNpcId: Int,
    val location: OriginTrainingDummyPoint,
    val strongCharge: Double,
    val comboTimeoutMillis: Long,
    val challengeHits: Int,
    val challengeDurationTicks: Long,
    val cooldownMillis: Long,
    val partialReward: Int,
    val fullReward: Int,
) {
    companion object {
        fun load(): OriginTrainingDummyConfig {
            val source = ConfigManager.ofModule(ARC.instance.dataPath, "origin-scenes.yml")
            source.mergeMissingFromBundled("modules/origin-scenes.yml")
            val root = "training-dummy"
            return OriginTrainingDummyConfig(
                world = source.string("world", "rc_origin_spawn"),
                dummyNpcId = source.integer("$root.dummy-npc-id", 364),
                trainerNpcId = source.integer("$root.trainer-npc-id", 355),
                location = OriginTrainingDummyPoint.parse(source.string("$root.location")),
                strongCharge = source.real("$root.strong-charge", 0.9).coerceIn(0.1, 1.0),
                comboTimeoutMillis = source.integer("$root.combo-timeout-seconds", 15).toLong().coerceIn(2L, 60L) * 1_000L,
                challengeHits = source.integer("$root.challenge-hits", 8).coerceIn(2, 20),
                challengeDurationTicks = source.integer("$root.challenge-duration-seconds", 18).toLong().coerceIn(5L, 60L) * 20L,
                cooldownMillis = source.integer("$root.cooldown-hours", 20).toLong().coerceIn(1L, 168L) * 3_600_000L,
                partialReward = source.integer("$root.partial-reward", 20).coerceIn(0, 10_000),
                fullReward = source.integer("$root.full-reward", 60).coerceIn(0, 10_000),
            )
        }
    }
}

internal data class OriginTrainingDummyPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    fun inWorld(world: org.bukkit.World) = Location(world, x, y, z, yaw, pitch)

    companion object {
        fun parse(raw: String): OriginTrainingDummyPoint {
            val values = raw.split(',').map(String::trim)
            require(values.size == 5) { "training-dummy.location must be x,y,z,yaw,pitch" }
            return OriginTrainingDummyPoint(
                values[0].toDouble(),
                values[1].toDouble(),
                values[2].toDouble(),
                values[3].toFloat(),
                values[4].toFloat(),
            )
        }
    }
}

internal data class OriginTrainingCombo(val count: Int, val lastStrongHitAt: Long)

internal fun nextTrainingCombo(previous: OriginTrainingCombo?, now: Long, timeoutMillis: Long, maximum: Int): OriginTrainingCombo {
    val previousCount = previous?.takeIf { now - it.lastStrongHitAt <= timeoutMillis }?.count ?: 0
    return OriginTrainingCombo((previousCount + 1).coerceAtMost(maximum), now)
}

internal class OriginTrainingComboCounter {
    private val combos = mutableMapOf<UUID, OriginTrainingCombo>()

    fun recordStrong(playerId: UUID, now: Long, timeoutMillis: Long, maximum: Int): OriginTrainingCombo =
        nextTrainingCombo(combos[playerId], now, timeoutMillis, maximum).also { combos[playerId] = it }

    fun reset(playerId: UUID) {
        combos.remove(playerId)
    }

    fun clear() {
        combos.clear()
    }
}

internal fun isTrainingWeapon(material: Material): Boolean =
    material.name.endsWith("_SWORD") ||
        material.name.endsWith("_AXE") ||
        material.name.endsWith("_SPEAR") ||
        material == Material.TRIDENT ||
        material == Material.MACE

internal fun trainingReward(hits: Int, target: Int, partialReward: Int, fullReward: Int): Int = when {
    hits >= target -> fullReward
    hits >= target - 2 -> partialReward
    else -> 0
}

private data class OriginTrainingAttack(val charge: Double, val capturedAt: Long)

private data class OriginTrainingChallenge(
    val token: UUID,
    var active: Boolean = false,
    var hits: Int = 0,
)

/** Kotlin owner of the forge mannequin, combo feedback and Bran's timed challenge. */
object OriginTrainingDummyModule : PluginModule, Listener {
    override val name = "OriginTrainingDummy"
    override val priority = 28

    private var config: OriginTrainingDummyConfig? = null
    private var tasks = LifecycleTaskScope()
    private val attacks = mutableMapOf<UUID, OriginTrainingAttack>()
    private val combos = OriginTrainingComboCounter()
    private val challenges = mutableMapOf<UUID, OriginTrainingChallenge>()
    private val lastDamageEventAt = mutableMapOf<UUID, Long>()
    private val cooldownKey by lazy { NamespacedKey(ARC.instance, "origin_forge_training_cooldown_until") }

    override fun init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            warn("ORIGIN_TRAINING_DUMMY phase=DISABLED reason=citizens-unavailable")
            return
        }
        config = OriginTrainingDummyConfig.load()
        tasks = LifecycleTaskScope()
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        tasks.runLater(5L) { anchorDummy() }
        info("ORIGIN_TRAINING_DUMMY phase=READY owner=arc-kotlin")
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        tasks.close()
        attacks.clear()
        combos.clear()
        challenges.clear()
        lastDamageEventAt.clear()
        config = null
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onTrainerClick(event: NPCRightClickEvent) {
        val settings = config ?: return
        if (event.npc.id != settings.trainerNpcId || event.clicker.world.name != settings.world) return
        event.isCancelled = true
        event.setDelayedCancellation(true)
        startChallenge(event.clicker, settings)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onNpcSpawn(event: NPCSpawnEvent) {
        val settings = config ?: return
        if (event.npc.id == settings.dummyNpcId) tasks.runLater(1L) { anchorDummy() }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onPreAttack(event: PrePlayerAttackEntityEvent) {
        val settings = config ?: return
        if (!event.willAttack() || !isDummy(event.attacked.uniqueId, settings)) return
        attacks[event.player.uniqueId] = OriginTrainingAttack(
            event.player.attackCooldown.toDouble().coerceIn(0.0, 1.0),
            System.currentTimeMillis(),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDummyDamage(event: EntityDamageByEntityEvent) {
        val settings = config ?: return
        if (!isDummy(event.entity.uniqueId, settings)) return
        event.isCancelled = true
        val player = event.damager as? Player ?: return
        if (player.world.name != settings.world) return
        val now = System.currentTimeMillis()
        if (now - lastDamageEventAt.getOrDefault(player.uniqueId, 0L) < 75L) return
        lastDamageEventAt[player.uniqueId] = now
        val material = player.inventory.itemInMainHand.type
        OriginAmbientScenesModule.interruptActor(settings.trainerNpcId, "player-at-dummy")
        if (!isTrainingWeapon(material)) {
            player.sendActionBar(Component.text("Бран » Для тренировки возьми меч, топор, копьё, трезубец или булаву.", NamedTextColor.GRAY))
            return
        }
        val attack = attacks.remove(player.uniqueId)?.takeIf { now - it.capturedAt <= 250L }
        // Paper supplies the pre-reset attack strength synchronously. If another plugin
        // suppresses that event, fail open for one hit instead of trapping the combo at 1/8.
        val charge = attack?.charge ?: 1.0
        val damage = maxOf(event.finalDamage, event.damage).coerceAtLeast(0.0)
        anchorDummy()
        (event.entity as? LivingEntity)?.playHurtAnimation(0f)
        player.playSound(event.entity.location, Sound.ENTITY_ARMOR_STAND_HIT, SoundCategory.PLAYERS, 0.9f, 0.92f)
        if (charge < settings.strongCharge) {
            weakHit(player, settings, charge, damage)
        } else {
            strongHit(player, settings, now, damage, event.isCritical)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        attacks.remove(id)
        combos.reset(id)
        challenges.remove(id)
        lastDamageEventAt.remove(id)
    }

    private fun startChallenge(player: Player, settings: OriginTrainingDummyConfig) {
        if (challenges.containsKey(player.uniqueId)) {
            player.sendMessage(Component.text("Бран » Испытание уже идёт. Продолжай серию.", NamedTextColor.GRAY))
            return
        }
        val now = System.currentTimeMillis()
        val cooldownUntil = cooldownUntil(player, now)
        if (cooldownUntil > now) {
            val hours = ceil((cooldownUntil - now) / 3_600_000.0).toInt().coerceAtLeast(1)
            player.sendMessage(Component.text("Бран » Новое испытание будет доступно через $hours ч. Манекен для обычной тренировки свободен.", NamedTextColor.GRAY))
            return
        }
        if (!isTrainingWeapon(player.inventory.itemInMainHand.type)) {
            player.sendMessage(Component.text("Бран » Для испытания нужен меч, топор, копьё, трезубец или булава в основной руке.", NamedTextColor.GRAY))
            return
        }

        player.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, now + settings.cooldownMillis)
        combos.reset(player.uniqueId)
        val challenge = OriginTrainingChallenge(UUID.randomUUID())
        challenges[player.uniqueId] = challenge
        OriginAmbientScenesModule.interruptActor(settings.trainerNpcId, "player-training")
        trainer(settings)?.let { (it.entity as? LivingEntity)?.swingMainHand() }
        player.playSound(player.location, Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.8f, 1.25f)
        player.sendMessage(Component.text("Бран » Собери серию из ${settings.challengeHits} мощных ударов.", NamedTextColor.GRAY))
        showCountdown(player, 3, settings.challengeHits)
        tasks.runLater(20L) { continueCountdown(player.uniqueId, challenge.token, 2, settings) }
    }

    private fun continueCountdown(playerId: UUID, token: UUID, number: Int, settings: OriginTrainingDummyConfig) {
        val player = Bukkit.getPlayer(playerId)
        val challenge = challenges[playerId]
        if (player == null || challenge?.token != token || player.world.name != settings.world) {
            challenges.remove(playerId, challenge)
            return
        }
        if (number > 0) {
            showCountdown(player, number, settings.challengeHits)
            tasks.runLater(20L) { continueCountdown(playerId, token, number - 1, settings) }
            return
        }
        challenge.active = true
        challenge.hits = 0
        combos.reset(playerId)
        player.showTitleMM("<green><bold>Начали", "<gray>Серия из <yellow>${settings.challengeHits} мощных ударов", 0, 16, 3)
        tasks.runLater(settings.challengeDurationTicks) { finishChallenge(playerId, token, settings) }
    }

    private fun showCountdown(player: Player, number: Int, target: Int) {
        player.showTitleMM(
            "<gold><bold>Приготовься <yellow>$number",
            "<gray>Полный замах <dark_gray>• <gray>успей сделать <white>$target ударов",
            0,
            16,
            2,
        )
    }

    private fun weakHit(player: Player, settings: OriginTrainingDummyConfig, charge: Double, damage: Double) {
        combos.reset(player.uniqueId)
        challenges[player.uniqueId]?.takeIf { it.active }?.hits = 0
        val percent = (charge * 100).roundToInt()
        val title = if (challenges[player.uniqueId]?.active == true) "Серия сброшена" else "Слабый удар"
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 0.55f, 0.9f)
        player.spawnParticle(Particle.DAMAGE_INDICATOR, dummyEffectLocation(settings), 3, 0.12, 0.2, 0.12, 0.01)
        player.showTitleMM("<red><bold>$title", "<gray>Дождись полного замаха <dark_gray>• <white>$percent%", 0, 10, 4)
        player.sendActionBar(Component.text("${formatDamage(damage)} урона • заряд $percent% • серия 0/${settings.challengeHits}", NamedTextColor.RED))
    }

    private fun strongHit(player: Player, settings: OriginTrainingDummyConfig, now: Long, damage: Double, critical: Boolean) {
        val combo = combos.recordStrong(player.uniqueId, now, settings.comboTimeoutMillis, settings.challengeHits)
        val titles = listOf("", "Мощный удар", "Связка ×2", "Комбо ×3", "Серия ×4", "Серия ×5", "Ритм ×6", "Почти ×7", "Цепь замкнута")
        val colors = listOf("", "<yellow>", "<gold>", "<red>", "<light_purple>", "<light_purple>", "<aqua>", "<aqua>", "<green>")
        val color = if (critical) "<aqua>" else colors.getOrElse(combo.count) { "<green>" }
        val location = dummyEffectLocation(settings)
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.65f, 1.1f)
        player.spawnParticle(Particle.DAMAGE_INDICATOR, location, 3 + combo.count, 0.15, 0.25, 0.15, 0.01)
        player.spawnParticle(Particle.SWEEP_ATTACK, location.clone().add(0.0, -0.2, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
        player.spawnParticle(Particle.CRIT, location, 5 + combo.count * 2, 0.18, 0.28, 0.18, 0.05)
        player.showTitleMM(
            "$color<bold>${titles.getOrElse(combo.count) { "Цепь замкнута" }}",
            "<white>${formatDamage(damage)} урона <dark_gray>• <yellow>серия ${combo.count}/${settings.challengeHits}",
            0,
            8,
            4,
        )
        val challenge = challenges[player.uniqueId]?.takeIf { it.active }
        if (challenge == null) {
            player.sendActionBar(Component.text("Мощный удар • ${formatDamage(damage)} урона • серия ${combo.count}/${settings.challengeHits}", NamedTextColor.GREEN))
            return
        }
        challenge.hits = combo.count
        player.sendActionBar(Component.text("Испытание • ${combo.count}/${settings.challengeHits} • мощный удар • ${formatDamage(damage)} урона", NamedTextColor.GOLD))
        if (combo.count >= settings.challengeHits) finishChallenge(player.uniqueId, challenge.token, settings)
    }

    private fun finishChallenge(playerId: UUID, token: UUID, settings: OriginTrainingDummyConfig) {
        val challenge = challenges[playerId]?.takeIf { it.token == token && it.active } ?: return
        challenges.remove(playerId, challenge)
        combos.reset(playerId)
        val player = Bukkit.getPlayer(playerId) ?: return
        val reward = trainingReward(challenge.hits, settings.challengeHits, settings.partialReward, settings.fullReward)
        if (reward > 0 && !pay(player, reward, token)) {
            player.sendMessage(Component.text("Бран » Касса не подтвердила награду. Сообщи администрации.", NamedTextColor.RED))
            warn("ORIGIN_TRAINING_DUMMY phase=PAYOUT_FAILED player={} hits={} reward={}", player.name, challenge.hits, reward)
            return
        }
        when {
            challenge.hits >= settings.challengeHits -> {
                player.showTitleMM("<green>Испытание пройдено", "<yellow>${challenge.hits}/${settings.challengeHits} <gray>• награда <yellow>$reward <white>💰", 10, 60, 20)
                player.sendMessage(Component.text("Бран » Чистая серия. Держи $reward 💰.", NamedTextColor.GRAY))
            }
            reward > 0 -> {
                player.showTitleMM("<yellow>Почти", "<yellow>${challenge.hits}/${settings.challengeHits} <gray>• награда <yellow>$reward <white>💰", 10, 60, 20)
                player.sendMessage(Component.text("Бран » Ритм почти удержал. За попытку — $reward 💰.", NamedTextColor.GRAY))
            }
            else -> {
                player.showTitleMM("<red>Испытание не пройдено", "<yellow>${challenge.hits}/${settings.challengeHits} <gray>• серия оборвалась", 10, 60, 20)
                player.sendMessage(Component.text("Бран » Серия оборвалась. Потренируй ритм.", NamedTextColor.GRAY))
            }
        }
        info("ORIGIN_TRAINING_DUMMY phase=CHALLENGE_FINISHED player={} hits={} reward={}", player.name, challenge.hits, reward)
    }

    private fun pay(player: Player, amount: Int, challengeToken: UUID): Boolean {
        val currency = RedisEconomyAPI.getAPI()?.defaultCurrency ?: return false
        val response = runCatching {
            currency.depositPlayer(
                player.uniqueId,
                currency.currencyName,
                amount.toDouble(),
                "arc-forge-training:${player.uniqueId}:$challengeToken",
            )
        }.getOrNull()
        return response?.transactionSuccess() == true
    }

    private fun cooldownUntil(player: Player, now: Long): Long {
        val stored = player.persistentDataContainer.get(cooldownKey, PersistentDataType.LONG) ?: 0L
        val legacy = if (Bukkit.getPluginManager().isPluginEnabled("Denizen")) {
            runCatching {
                val tracker = PlayerTag(player).flagTracker
                if (tracker.hasFlag(LEGACY_COOLDOWN_FLAG)) tracker.getFlagExpirationTime(LEGACY_COOLDOWN_FLAG)?.millis() ?: 0L else 0L
            }.getOrDefault(0L)
        } else {
            0L
        }
        val result = maxOf(stored, legacy)
        if (result > now && result > stored) player.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, result)
        return result
    }

    private fun anchorDummy() {
        val settings = config ?: return
        val world = Bukkit.getWorld(settings.world) ?: return
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(settings.dummyNpcId) }.getOrNull()?.takeIf { it.isSpawned } ?: return
        npc.navigator.cancelNavigation()
        npc.entity.setGravity(false)
        npc.entity.velocity = Vector()
        npc.entity.teleport(settings.location.inWorld(world))
    }

    private fun dummyEffectLocation(settings: OriginTrainingDummyConfig): Location {
        val world = Bukkit.getWorld(settings.world) ?: error("Origin training world is unavailable")
        return settings.location.inWorld(world).add(0.0, 1.25, 0.0)
    }

    private fun isDummy(entityId: UUID, settings: OriginTrainingDummyConfig): Boolean =
        runCatching { CitizensAPI.getNPCRegistry().getById(settings.dummyNpcId) }.getOrNull()?.takeIf { it.isSpawned }?.entity?.uniqueId == entityId

    private fun trainer(settings: OriginTrainingDummyConfig) =
        runCatching { CitizensAPI.getNPCRegistry().getById(settings.trainerNpcId) }.getOrNull()?.takeIf { it.isSpawned }

    private fun formatDamage(damage: Double): String = String.format(java.util.Locale.US, "%.1f", damage)

    private const val LEGACY_COOLDOWN_FLAG = "rc_origin_forge_test_cooldown"
}
