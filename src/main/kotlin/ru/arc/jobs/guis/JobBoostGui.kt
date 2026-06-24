package ru.arc.jobs.guis

import com.gamingmesh.jobs.container.Job
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.gui.dynamicGui
import ru.arc.jobs.BoostType
import ru.arc.jobs.JobsBoostData
import ru.arc.jobs.JobsModule
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import java.util.concurrent.TimeUnit

/**
 * GUI showing boosts for a specific job.
 *
 * Uses dynamic rows based on boost count.
 */
fun JobBoostGui(
    job: Job,
    player: Player,
    config: Config,
): ChestGui {
    // Get player's boosts for this job
    val playerData = JobsModule.getBoostData(player.uniqueId)
    val boosts = playerData?.boostsForJob(job) ?: emptyList()

    // Get base boosts
    val baseBoosts = getBaseBoosts(player, job)
    val totalItems = boosts.size + baseBoosts.size

    return dynamicGui(
        config = config,
        titleKey = "job-menu.title",
        placeholders = mapOf("job" to JobsModule.jobDisplayMinimessage(job.name)),
        itemCount = totalItems,
        minRows = 2,
        maxRows = 6,
        navRows = 1,
        player = player,
    ) {
        // Background for nav bar only
        navBackground()

        // Content: base boosts + individual boosts
        pagination {
            // Base boosts (server-wide or permission-based)
            items(baseBoosts) {
                material(it.material)
                display("Все <green>+<boost>%")
                lore(listOf("<gray>Буст от привелегий"))
                tagResolver(createBaseBoostResolver(job, it.boost))
                fromConfig(config, it.configPath)
            }

            // Individual player boosts
            items(boosts) {
                material(getMaterialForBoostType(it.type))
                display("<gold><type> <green>+<amount>%")
                lore(listOf("<gray>Истечет через: <expire>", "<gray>ID: <id>"))
                tagResolver(createBoostResolver(job, it))
                fromConfig(config, "job-menu.boost")
            }
        }

        // Navigation bar
        navBar {
            back(configKey = "job-menu.back") {
                GuiUtils.constructAndShowAsync({ createJobsListGui(config, player) }, player)
            }

            button(4) {
                material(Material.GREEN_STAINED_GLASS_PANE)
                display("<green>Купить буст")
                lore(emptyList())
                fromConfig(config, "job-menu.buy")
                onClick {
                    GuiUtils.constructAndShowAsync({ BuyBoostGuiFactory.create(player, job, config) }, player)
                }
            }
        }
    }
}

/**
 * Data class for base boost display info.
 */
private data class BaseBoostInfo(
    val material: Material,
    val configPath: String,
    val boost: Double,
)

/**
 * Get base boosts to display.
 */
private fun getBaseBoosts(
    player: Player,
    job: Job,
): List<BaseBoostInfo> {
    val moneyBaseBoost = JobsModule.getBoost(player, job.name, BoostType.MONEY) * 100
    val pointsBaseBoost = JobsModule.getBoost(player, job.name, BoostType.POINTS) * 100
    val expBaseBoost = JobsModule.getBoost(player, job.name, BoostType.EXP) * 100

    // No base boosts
    if (moneyBaseBoost <= 1 && pointsBaseBoost <= 1 && expBaseBoost <= 1) {
        return emptyList()
    }

    // Check if all boosts are approximately equal - show single "all" item
    if (kotlin.math.abs(moneyBaseBoost - pointsBaseBoost) < 1.0 &&
        kotlin.math.abs(moneyBaseBoost - expBaseBoost) < 1.0
    ) {
        return listOf(
            BaseBoostInfo(
                Material.DIAMOND,
                "job-menu.all-base-boost",
                moneyBaseBoost,
            ),
        )
    }

    // Individual boost items
    return buildList {
        if (moneyBaseBoost > 1) {
            add(
                BaseBoostInfo(
                    Material.GOLD_INGOT,
                    "job-menu.money-base-boost",
                    moneyBaseBoost,
                ),
            )
        }
        if (pointsBaseBoost > 1) {
            add(
                BaseBoostInfo(
                    Material.NETHER_STAR,
                    "job-menu.points-base-boost",
                    pointsBaseBoost,
                ),
            )
        }
        if (expBaseBoost > 1) {
            add(
                BaseBoostInfo(
                    Material.EXPERIENCE_BOTTLE,
                    "job-menu.exp-base-boost",
                    expBaseBoost,
                ),
            )
        }
    }
}

/**
 * Get material for boost type.
 */
private fun getMaterialForBoostType(type: BoostType): Material =
    when (type) {
        BoostType.EXP -> Material.EXPERIENCE_BOTTLE
        BoostType.MONEY -> Material.GOLD_INGOT
        BoostType.POINTS -> Material.NETHER_STAR
        else -> Material.DIAMOND
    }

/**
 * Create resolver for base boost display.
 */
private fun createBaseBoostResolver(
    job: Job,
    boost: Double,
): TagResolver =
    TagResolver
        .builder()
        .tag("job", Tag.inserting(mm(job.name, true)))
        .tag("boost", Tag.inserting(mm(formatAmount(boost, 3), true)))
        .build()

/**
 * Create resolver for individual boost display.
 */
private fun createBoostResolver(
    job: Job,
    boost: JobsBoostData,
): TagResolver {
    val expiresIn = boost.expiresInMillis()
    val expire = TextUtil.time(expiresIn, TimeUnit.MILLISECONDS)
    return TagResolver
        .builder()
        .tag("job", Tag.inserting(mm(job.name, true)))
        .tag("id", Tag.inserting(mm(boost.id, true)))
        .tag("type", Tag.inserting(mm(boost.type.display, true)))
        .tag("amount", Tag.inserting(mm(formatAmount(boost.boost * 100, 3), true)))
        .tag("expire", Tag.inserting(mm(expire, true)))
        .build()
}
