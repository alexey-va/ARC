package ru.arc.jobs.guis

import com.gamingmesh.jobs.Jobs
import com.gamingmesh.jobs.container.Job
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.jobs.BoostDataEntity
import ru.arc.jobs.BoostType
import ru.arc.jobs.JobsModule
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtil.formatAmount

fun createJobsListGui(config: Config, player: Player) {
    val data = JobsModule.getBoostData(player.uniqueId) ?: BoostDataEntity(player.uniqueId, HashSet())
    val entries = Jobs.getJobs().map { job ->
        val boost = calculateBoosts(job, data)
        val money = boost.money + JobsModule.getBoost(player, job.name, BoostType.MONEY) * 100
        val points = boost.points + JobsModule.getBoost(player, job.name, BoostType.POINTS) * 100
        val exp = boost.exp + JobsModule.getBoost(player, job.name, BoostType.EXP) * 100
        val name: Component = LegacyComponentSerializer.legacyAmpersand()
            .deserialize(job.displayName.replace("§", "&"))
            .decoration(TextDecoration.ITALIC, false)
        val presentation = ArcMenus.item(
            "jobs-entry",
            PaperMenuItemRenderContext(values = mapOf(
                "job" to name,
                "money" to Component.text(signed(config, money)),
                "exp" to Component.text(signed(config, exp)),
                "points" to Component.text(signed(config, points)),
            )),
        )
        val item = applyPresentation(job.guiItem.clone(), presentation)
        if (boost.money != 0.0 || boost.exp != 0.0 || boost.points != 0.0) {
            item.editMeta { it.setEnchantmentGlintOverride(true) }
        }
        ArcMenus.entry(item) { JobBoostGui(job, it, config) }
    }
    ArcMenus.open(
        player,
        ArcMenuSchema.JOBS_LIST,
        config.component("boost-menu.title", "<dark_gray>Профессии"),
        elements = mapOf(
            "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.JOBS_LIST, "back")) { it.closeInventory() },
            "buy" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.JOBS_LIST, "buy")) { BuyBoostGuiFactory.open(it, null, config) },
        ),
        regions = mapOf(ArcMenuSchema.JOB_ENTRIES to entries),
    )
}

private data class CalculatedBoost(val money: Double, val exp: Double, val points: Double)

private fun calculateBoosts(job: Job, data: BoostDataEntity) = CalculatedBoost(
    money = data.getBoost(job, BoostType.MONEY) * 100 - 100,
    points = data.getBoost(job, BoostType.POINTS) * 100 - 100,
    exp = data.getBoost(job, BoostType.EXP) * 100 - 100,
)

private fun signed(config: Config, value: Double): String {
    val prefix = when {
        value > 0 -> config.string("boost-menu.high-prefix", "+ ")
        value < 0 -> config.string("boost-menu.low-prefix", "- ")
        else -> ""
    }
    return prefix + formatAmount(kotlin.math.abs(value), 4)
}

private fun applyPresentation(base: ItemStack, presentation: ItemStack): ItemStack =
    base.clone().also { target ->
        val source = presentation.itemMeta
        target.editMeta { meta ->
            meta.displayName(source.displayName())
            meta.lore(source.lore())
        }
    }
