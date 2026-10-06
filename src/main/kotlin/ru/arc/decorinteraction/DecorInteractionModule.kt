package ru.arc.decorinteraction

import de.tr7zw.changeme.nbtapi.NBT
import dev.lone.itemsadder.api.CustomStack
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.types.PermissionNode
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.core.PluginModule
import ru.arc.ops.ItemPresets
import ru.arc.ops.OpsItemHandlers
import ru.arc.util.Logging
import ru.arc.util.TextUtil
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * ARC owner for the legacy Survival `/arcsystem` decor rewards.
 * All Bukkit/ItemsAdder/Brewery operations and rewards run synchronously on the
 * primary thread. A non-operator's local fallback is scoped by action; LuckPerms
 * persistence is asynchronous and its callback only logs failures. BreweryX
 * inserts a validated bottle during preparation, and ARC never delivers it twice.
 */
object DecorInteractionModule : PluginModule {
    const val PERMISSION = "arc.system.interactions"
    private const val RATE_LIMIT_NANOS = 3_000_000_000L

    override val name = "DecorInteractions"
    override val priority = 84

    private val rateLimiter = DecorInteractionRateLimiter(RATE_LIMIT_NANOS)
    private val localCooldowns = DecorInteractionCooldowns()

    @Volatile private var config = DecorInteractionConfig(enabled = false)

    val available: Boolean get() = config.enabled

    override fun init() {
        config = DecorInteractionConfig.load(ARC.instance.dataPath)
        Logging.info("Decor interactions {} by configuration", if (config.enabled) "enabled" else "disabled")
    }

    override fun reload() {
        config = DecorInteractionConfig.load(ARC.instance.dataPath)
        Logging.info("Decor interactions {} by configuration", if (config.enabled) "enabled" else "disabled")
    }

    override fun shutdown() {
        localCooldowns.clear()
        rateLimiter.clear()
        config = DecorInteractionConfig(enabled = false)
    }

    fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (!Bukkit.isPrimaryThread()) {
            Logging.warn("Decor interaction command reached the module outside the primary thread")
            return true
        }
        if (!available) {
            sender.sendMessage(TextUtil.mm("<red>Интеракции декора сейчас отключены."))
            return true
        }
        if (!sender.isOp && !sender.hasPermission(PERMISSION)) {
            sender.sendMessage(CommandConfig.noPermission())
            return true
        }
        if (args.size !in 2..3) {
            sender.sendMessage(CommandConfig.usage("/arc interact <drink|fountain|well|milk|tea|fish|harvest|rest> <игрок> [beer|wine|corn|rice]"))
            return true
        }

        val action = DecorInteractionAction.parse(args[0])
        if (action == null) {
            sender.sendMessage(TextUtil.mm("<gold>ARC System <dark_gray>• <red>Неизвестная интеракция."))
            return true
        }
        val target = Bukkit.getPlayerExact(args[1])
        if (target == null || !target.isOnline || target.name != args[1]) {
            sender.sendMessage(CommandConfig.playerNotFound(args[1]))
            return true
        }

        val variant = when (action) {
            DecorInteractionAction.DRINK -> BarrelDrink.parse(args.getOrNull(2).orEmpty())
            DecorInteractionAction.HARVEST -> HarvestCrop.parse(args.getOrNull(2).orEmpty())
            else -> null
        }
        if (action == DecorInteractionAction.DRINK && variant !is BarrelDrink) {
            target.sendMessage(TextUtil.mm("<gold>Напитки <dark_gray>• <red>Неизвестный напиток."))
            return true
        }
        if (action == DecorInteractionAction.HARVEST && variant !is HarvestCrop) {
            target.sendMessage(TextUtil.mm("<gold>Урожай <dark_gray>• <red>Неизвестный вид урожая."))
            return true
        }
        if (action !in setOf(DecorInteractionAction.DRINK, DecorInteractionAction.HARVEST) && args.size != 2) {
            sender.sendMessage(CommandConfig.usage("/arc interact <drink|fountain|well|milk|tea|fish|harvest|rest> <игрок> [beer|wine|corn|rice]"))
            return true
        }

        val now = System.nanoTime()
        localCooldowns.expire(now)
        if (!rateLimiter.tryAcquire(target.uniqueId, now)) return true
        if (!target.isOp && (localCooldowns.isActive(target.uniqueId, action, now) || target.hasPermission(action.cooldownNode))) {
            target.sendMessage(TextUtil.mm(cooldownMessage(action)))
            return true
        }

        val reward = prepareReward(target, action, variant) ?: return true
        val needsCooldown = !target.isOp
        if (needsCooldown) {
            // Lock this action before feedback/effects; BreweryX was validated above.
            localCooldowns.start(target.uniqueId, action, System.nanoTime())
            persistCooldown(target.uniqueId, action)
        }
        deliver(target, reward)
        return true
    }

    private fun prepareReward(
        target: Player,
        action: DecorInteractionAction,
        variant: Any?,
    ): PreparedReward? = when (action) {
        DecorInteractionAction.DRINK -> {
            val drink = variant as BarrelDrink
            val quality = Random.nextInt(1, 11)
            val slot = target.inventory.firstEmpty()
            if (slot < 0) {
                target.sendMessage(TextUtil.mm("<gold>Напитки <dark_gray>• <gray>Освободи один слот для бутылки."))
                null
            } else if (createBreweryBottle(target, slot, drink, quality)) {
                PreparedReward(action, label = drink.label, qualityLabel = quality.toString(), alreadyGiven = true)
            } else {
                null
            }
        }
        DecorInteractionAction.FOUNTAIN -> {
            val stacks = runCatching { ItemPresets.resolveStacks("money_handful", 1).getOrNull() }.getOrNull()
            if (stacks.isNullOrEmpty()) unavailableItem(target) else PreparedReward(action, checkNotNull(stacks))
        }
        DecorInteractionAction.WELL -> itemsAdderReward(target, action, "food_and_produce:bottle_of_water")
        DecorInteractionAction.MILK -> itemsAdderReward(target, action, "food_and_produce:milk_carton")
        DecorInteractionAction.TEA -> {
            val tea = TeaReward.roll(Random)
            itemsAdderReward(target, action, tea.itemId, tea.label)
        }
        DecorInteractionAction.FISH -> {
            val catch = rollFishCatch(Random)
            itemsAdderReward(target, action, catch.itemId, catch.fish.label, catch.quality.label)
        }
        DecorInteractionAction.HARVEST -> {
            val harvest = variant as HarvestCrop
            itemsAdderReward(target, action, harvest.itemId, harvest.label)
        }
        DecorInteractionAction.REST -> PreparedReward(action)
    }

    private fun itemsAdderReward(
        target: Player,
        action: DecorInteractionAction,
        itemId: String,
        label: String? = null,
        qualityLabel: String? = null,
    ): PreparedReward? {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return unavailableItem(target)
        val stack = runCatching {
            CustomStack.getInstance(itemId)?.itemStack?.clone()?.apply { amount = 1 }
        }.getOrNull()
            ?: return unavailableItem(target)
        return PreparedReward(action, listOf(stack), label, qualityLabel = qualityLabel)
    }

    private fun unavailableItem(target: Player): PreparedReward? {
        target.sendMessage(TextUtil.mm("<gold>ARC System <dark_gray>• <red>Награда сейчас недоступна. Попробуй позже."))
        return null
    }

    private fun createBreweryBottle(
        target: Player,
        slot: Int,
        drink: BarrelDrink,
        quality: Int,
    ): Boolean {
        // BreweryX has no compile-time API in ARC; reuse the established exact
        // `brew give` route and validate the resulting potion before accepting it.
        runCatching {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "brew give ${drink.id} $quality ${target.name}")
        }.onFailure { Logging.warn("BreweryX drink command threw while checking a decor reward", it) }
        val issued = target.inventory.getItem(slot)
        val isBreweryDrink = issued != null && issued.type == Material.POTION &&
            issued.itemMeta?.persistentDataContainer?.keys?.any {
                it.namespace.equals("breweryx", ignoreCase = true) && it.key == "brewdata"
            } == true
        if (!isBreweryDrink) {
            target.sendMessage(TextUtil.mm("<gold>Напитки <dark_gray>• <gray>Не удалось наполнить бутылку. Попробуй ещё раз."))
            return false
        }

        val accepted = checkNotNull(issued).clone()
        NBT.modify(accepted) { it.setString("rc_brewery_origin", "barrel") }
        target.inventory.setItem(slot, accepted)
        return true
    }

    private fun persistCooldown(playerId: UUID, action: DecorInteractionAction) {
        val cooldownExpiry = Instant.now().plus(action.cooldown)
        val update = runCatching {
            LuckPermsProvider.get().userManager.modifyUser(playerId) { user ->
                user.data().add(
                    PermissionNode.builder(action.cooldownNode)
                        .value(true)
                        .expiry(cooldownExpiry)
                        .build(),
                )
            }
        }.getOrElse { failure ->
            Logging.error("Decor interaction cooldown permission update could not start", failure)
            return
        }
        update.whenComplete { _, failure ->
            if (failure != null) Logging.error("Decor interaction cooldown permission update failed", failure)
        }
    }

    private fun deliver(target: Player, reward: PreparedReward) {
        when (reward.action) {
            DecorInteractionAction.DRINK -> {
                if (!reward.alreadyGiven) OpsItemHandlers.giveStacks(target, reward.items)
                val label = requireNotNull(reward.label)
                val quality = requireNotNull(reward.qualityLabel)
                val ending = if (target.isOp) "." else ". Следующий напиток — через час."
                target.sendMessage(TextUtil.mm("<gold>Напитки <dark_gray>• <green>Ты получил $label <gray>качества <yellow>$quality/10<gray>$ending"))
                particle(target, Particle.WAX_ON, 16, 0.4, 0.6, 0.4)
                sound(target, Sound.ENTITY_PLAYER_BURP, 0.9f, 1.1f)
            }
            DecorInteractionAction.FOUNTAIN -> {
                OpsItemHandlers.giveStacks(target, reward.items)
                val ending = if (target.isOp) "!" else "! <gray>Следующая находка — через три часа."
                target.sendMessage(TextUtil.mm("<gold>Фонтан <dark_gray>• <green>Среди бликов на воде нашлась горсть монет$ending"))
                particle(target, Particle.SPLASH, 24, 0.5, 0.6, 0.5)
                sound(target, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.2f)
            }
            DecorInteractionAction.WELL -> {
                OpsItemHandlers.giveStacks(target, reward.items)
                val ending = if (target.isOp) "." else ". <gray>Следующую можно набрать через минуту."
                target.sendMessage(TextUtil.mm("<gold>Колодец <dark_gray>• <green>Ты набрал бутылку прохладной воды$ending"))
                particle(target, Particle.SPLASH, 18, 0.4, 0.5, 0.4)
                sound(target, Sound.ITEM_BOTTLE_FILL, 0.8f, 1.1f)
            }
            DecorInteractionAction.MILK -> {
                OpsItemHandlers.giveStacks(target, reward.items)
                val ending = if (target.isOp) "." else ". <gray>Следующий — через пять минут."
                target.sendMessage(TextUtil.mm("<gold>Молочная ферма <dark_gray>• <green>Ты получил пакет свежего молока$ending"))
                particle(target, Particle.CLOUD, 12, 0.35, 0.4, 0.35)
                sound(target, Sound.ENTITY_COW_MILK, 0.8f, 1.0f)
            }
            DecorInteractionAction.TEA -> {
                OpsItemHandlers.giveStacks(target, reward.items)
                val ending = if (target.isOp) "." else ". <gray>Следующая чашка — через три минуты."
                target.sendMessage(TextUtil.mm("<gold>Чайный набор <dark_gray>• <green>Ты заварил ${reward.label}$ending"))
                particle(target, Particle.CLOUD, 10, 0.25, 0.45, 0.25)
                sound(target, Sound.BLOCK_BREWING_STAND_BREW, 0.7f, 1.2f)
            }
            DecorInteractionAction.FISH -> {
                OpsItemHandlers.giveStacks(target, reward.items)
                val ending = if (target.isOp) "." else ". <gray>Следующий улов — через двадцать минут."
                target.sendMessage(TextUtil.mm("<gold>Рыбалка <dark_gray>• <green>Тебе попался <yellow>${reward.label}<gray> ${reward.qualityLabel}$ending"))
                particle(target, Particle.SPLASH, 24, 0.6, 0.5, 0.6)
                sound(target, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.9f, 1.0f)
            }
            DecorInteractionAction.HARVEST -> {
                OpsItemHandlers.giveStacks(target, reward.items)
                val ending = if (target.isOp) "." else ". <gray>Новый урожай — через пять минут."
                target.sendMessage(TextUtil.mm("<gold>Урожай <dark_gray>• <green>Ты собрал ${reward.label}$ending"))
                particle(target, Particle.WAX_ON, 14, 0.45, 0.45, 0.45)
                sound(target, Sound.BLOCK_GRASS_BREAK, 0.8f, 1.3f)
            }
            DecorInteractionAction.REST -> {
                target.foodLevel = (target.foodLevel + 2).coerceAtMost(20)
                target.saturation = (target.saturation + 1.0f).coerceIn(0.0f, 20.0f)
                target.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 80, 0, false, true, true))
                val ending = if (target.isOp) "." else ". <gray>Снова отдохнуть можно через пять минут."
                target.sendMessage(TextUtil.mm("<gold>Отдых <dark_gray>• <green>Короткая передышка восстановила силы$ending"))
                particle(target, Particle.WAX_ON, 12, 0.4, 0.5, 0.4)
                sound(target, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f)
            }
        }
    }

    private fun particle(target: Player, particle: Particle, count: Int, x: Double, y: Double, z: Double) {
        target.spawnParticle(particle, target.location.add(0.0, 1.0, 0.0), count, x, y, z)
    }

    private fun sound(target: Player, sound: Sound, volume: Float, pitch: Float) {
        target.playSound(target.location, sound, volume, pitch)
    }

    private fun cooldownMessage(action: DecorInteractionAction): String =
        when (action) {
            DecorInteractionAction.DRINK -> "<gold>Напитки <dark_gray>• <gray>Новый напиток будет готов не раньше чем через час после предыдущего."
            DecorInteractionAction.FOUNTAIN -> "<gold>Фонтан <dark_gray>• <gray>Ты уже находил здесь монеты. Возвращайся через три часа после предыдущей находки."
            DecorInteractionAction.WELL -> "<gold>Колодец <dark_gray>• <gray>Вода ещё набирается. Попробуй снова через минуту после предыдущего раза."
            DecorInteractionAction.MILK -> "<gold>Молочная ферма <dark_gray>• <gray>Новая порция молока будет готова через пять минут после предыдущей."
            DecorInteractionAction.TEA -> "<gold>Чайный набор <dark_gray>• <gray>Чай ещё заваривается. Попробуй через три минуты после предыдущей чашки."
            DecorInteractionAction.FISH -> "<gold>Рыбалка <dark_gray>• <gray>Рыба распугана. Попробуй снова через двадцать минут после предыдущего улова."
            DecorInteractionAction.HARVEST -> "<gold>Урожай <dark_gray>• <gray>Здесь пока нечего собирать. Возвращайся через пять минут после предыдущего сбора."
            DecorInteractionAction.REST -> "<gold>Отдых <dark_gray>• <gray>Ты уже отдохнул. Следующая передышка поможет через пять минут после предыдущей."
        }

    private data class PreparedReward(
        val action: DecorInteractionAction,
        val items: List<ItemStack> = emptyList(),
        val label: String? = null,
        val qualityLabel: String? = null,
        val alreadyGiven: Boolean = false,
    )
}
