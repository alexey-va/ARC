package ru.arc.jobs.guis

import com.gamingmesh.jobs.Jobs
import com.gamingmesh.jobs.container.Job
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import ru.arc.configs.Config
import ru.arc.gui.gui
import ru.arc.jobs.BoostDataEntity
import ru.arc.jobs.BoostType
import ru.arc.jobs.JobsModule
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm

/**
 * GUI showing list of all jobs with their boost status.
 * Migrated to new GUI DSL.
 */
fun createJobsListGui(
    config: Config,
    player: Player,
): ChestGui {
    val data =
        JobsModule.getBoostData(player.uniqueId)
            ?: BoostDataEntity(player.uniqueId, HashSet())

    return gui(config, "boost-menu.title", rows = 3, player = player) {
        // Background
        background()

        // Jobs list
        pagination(0 until 2) {
            items(Jobs.getJobs()) { job ->
                val boost = calculateBoosts(job, data)
                val resolver = createResolver(config, player, job, boost, data)

                material(job.guiItem.type)
                displayFromConfig("boost-menu.job-display")
                loreFromConfig("boost-menu.job-lore")
                tagResolver(resolver)
                flags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)

                // Add enchant glow if player has any boost for this job
                if (boost.money != 0.0 || boost.exp != 0.0 || boost.points != 0.0) {
                    enchant(Enchantment.VANISHING_CURSE, 1, true)
                }

                onClick {
                    GuiUtils.constructAndShowAsync({ JobBoostGui(job, player, config) }, player)
                }
            }
        }

        // Navigation bar
        navBar {
            back(configKey = "boost-menu.back")

            button(4) {
                material(Material.GREEN_STAINED_GLASS_PANE)
                displayFromConfig("boost-menu.buy-display")
                loreFromConfig("boost-menu.buy-lore")
                onClick {
                    GuiUtils.constructAndShowAsync({ BuyBoostGuiFactory.create(player, null, config) }, player)
                }
            }
        }
    }
}

/**
 * Data class for calculated boost values.
 */
private data class CalculatedBoost(
    val job: Job,
    val money: Double,
    val exp: Double,
    val points: Double,
)

/**
 * Calculate boost values for a job.
 */
private fun calculateBoosts(
    job: Job,
    data: BoostDataEntity,
): CalculatedBoost {
    val boostMoney = data.getBoost(job, BoostType.MONEY) * 100 - 100
    val boostPoints = data.getBoost(job, BoostType.POINTS) * 100 - 100
    val boostExp = data.getBoost(job, BoostType.EXP) * 100 - 100
    return CalculatedBoost(job, boostMoney, boostExp, boostPoints)
}

/**
 * Create tag resolver for job item display.
 */
private fun createResolver(
    config: Config,
    player: Player,
    job: Job,
    boost: CalculatedBoost,
    data: BoostDataEntity,
): TagResolver {
    val prefixUp = config.string("boost-menu.high-prefix", "<green>+ ")
    val prefixLow = config.string("boost-menu.low-prefix", "<red>- ")

    val moneyBaseBoost = JobsModule.getBoost(player, job.name, BoostType.MONEY) * 100
    val pointsBaseBoost = JobsModule.getBoost(player, job.name, BoostType.POINTS) * 100
    val expBaseBoost = JobsModule.getBoost(player, job.name, BoostType.EXP) * 100

    val moneyBoost = boost.money + moneyBaseBoost
    val pointsBoost = boost.points + pointsBaseBoost
    val expBoost = boost.exp + expBaseBoost

    fun getPrefix(value: Double): String =
        when {
            value > 0 -> prefixUp
            value < 0 -> prefixLow
            else -> ""
        }

    val name: Component =
        LegacyComponentSerializer
            .legacyAmpersand()
            .deserialize(job.displayName.replace("§", "&"))
            .decoration(TextDecoration.ITALIC, false)

    return TagResolver
        .builder()
        .tag("player", Tag.inserting(mm(player.name, true)))
        .tag("job", Tag.inserting(name))
        .tag(
            "money_boost",
            Tag.inserting(mm(getPrefix(moneyBoost) + formatAmount(kotlin.math.abs(moneyBoost), 4), true)),
        ).tag("exp_boost", Tag.inserting(mm(getPrefix(expBoost) + formatAmount(kotlin.math.abs(expBoost), 4), true)))
        .tag(
            "points_boost",
            Tag.inserting(mm(getPrefix(pointsBoost) + formatAmount(kotlin.math.abs(pointsBoost), 4), true)),
        ).build()
}
