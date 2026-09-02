package ru.arc.jobs.guis

import com.gamingmesh.jobs.container.Job
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.jobs.BoostType
import ru.arc.jobs.JobsBoostData
import ru.arc.jobs.JobsModule
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import java.util.concurrent.TimeUnit

fun JobBoostGui(job: Job, player: Player, config: Config) {
    val personal = JobsModule.getBoostData(player.uniqueId)?.boostsForJob(job).orEmpty()
    val entries = baseBoosts(player, job).map { (type, amount) ->
        ArcMenus.entry(
            ArcMenus.item(
                "job-base-boost",
                values("type" to type.display, "boost" to formatAmount(amount, 3)),
            ).withType(material(type)),
            enabled = false,
        )
    } + personal.map(::personalEntry)
    val title = config.string("job-menu.title", "<dark_gray>Бусты: <job>")
        .replace("<job>", JobsModule.jobDisplayMinimessage(job.name))
    ArcMenus.open(
        player,
        ArcMenuSchema.JOB_BOOSTS,
        TextUtil.mm(title, true),
        elements = mapOf(
            "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.JOB_BOOSTS, "back")) { createJobsListGui(config, it) },
            "buy" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.JOB_BOOSTS, "buy")) { BuyBoostGuiFactory.open(it, job, config) },
        ),
        regions = mapOf(ArcMenuSchema.JOB_BOOST_ENTRIES to entries),
    )
}

private fun personalEntry(boost: JobsBoostData): PaperMenuEntry {
    val expire = TextUtil.time(boost.expiresInMillis(), TimeUnit.MILLISECONDS)
    return ArcMenus.entry(
        ArcMenus.item(
            "job-player-boost",
            values(
                "type" to boost.type.display,
                "amount" to formatAmount(boost.boost * 100, 3),
                "expire" to expire,
                "id" to boost.id,
            ),
        ).withType(material(boost.type)),
        enabled = false,
    )
}

private fun baseBoosts(player: Player, job: Job): List<Pair<BoostType, Double>> {
    val money = JobsModule.getBoost(player, job.name, BoostType.MONEY) * 100
    val points = JobsModule.getBoost(player, job.name, BoostType.POINTS) * 100
    val exp = JobsModule.getBoost(player, job.name, BoostType.EXP) * 100
    if (money <= 1 && points <= 1 && exp <= 1) return emptyList()
    if (kotlin.math.abs(money - points) < 1.0 && kotlin.math.abs(money - exp) < 1.0) {
        return listOf(BoostType.ALL to money)
    }
    return buildList {
        if (money > 1) add(BoostType.MONEY to money)
        if (points > 1) add(BoostType.POINTS to points)
        if (exp > 1) add(BoostType.EXP to exp)
    }
}

private fun material(type: BoostType): Material = when (type) {
    BoostType.EXP -> Material.EXPERIENCE_BOTTLE
    BoostType.MONEY -> Material.GOLD_INGOT
    BoostType.POINTS -> Material.NETHER_STAR
    BoostType.ALL -> Material.DIAMOND
}

private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
    values = pairs.associate { (key, value) -> key to Component.text(value) },
)
