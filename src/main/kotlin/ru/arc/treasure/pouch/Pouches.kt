package ru.arc.treasure.pouch

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.ops.OpsItemSpec
import ru.arc.treasure.core.Treasures
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil

object Pouches {
    private const val CONFIG_FILE = "pouches.yml"
    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataPath, CONFIG_FILE)

    @Volatile
    private var definitions: Map<String, PouchDefinition> = emptyMap()

    private val service: PouchService
        get() = PouchService(Treasures::getPool, Treasures.service::give)

    fun init() = reload()

    fun reload() {
        val loaded = config.keys("pouches").sorted().associate { rawId ->
            val definition = PouchDefinitionParser.parse(rawId, config.map<Any?>("pouches.$rawId"))
            definition.id to definition
        }
        loaded.values.forEach { definition ->
            runCatching { OpsItemSpec.build(definition.itemSpec()) }.getOrElse { failure ->
                throw IllegalArgumentException(
                    "pouches.${definition.id}.item is invalid: ${failure.message ?: failure::class.simpleName}",
                    failure,
                )
            }
        }
        definitions = loaded

        val unavailable = loaded.values.flatMap { definition ->
            definition.rewards.mapNotNull { source ->
                val pool = Treasures.getPool(source.poolId)
                if (pool == null || pool.isEmpty()) "${definition.id}:${source.poolId}" else null
            }
        }
        if (unavailable.isNotEmpty()) {
            warn("Pouches loaded with unavailable treasure pools: ${unavailable.joinToString()}")
        }
        info("Pouches loaded: ${loaded.size}")
    }

    fun all(): List<PouchDefinition> = definitions.values.sortedBy { it.id }

    fun get(rawId: String): PouchDefinition? =
        runCatching { definitions[PouchDefinitionParser.normalize(rawId)] }.getOrNull()

    fun createStack(rawId: String, amount: Int = 1): Result<ItemStack> = runCatching {
        val definition = get(rawId) ?: throw IllegalArgumentException("Unknown pouch: $rawId")
        OpsItemSpec.build(definition.itemSpec(amount))
    }

    fun open(rawId: String, player: Player): PouchOpenResult {
        val definition = get(rawId)
            ?: return PouchOpenResult(0, 0, listOf("Unknown pouch: $rawId"))
        val result = service.open(definition, player)
        if (result.shouldConsume) {
            runCatching {
                val message = definition.open.message
                    .replace("%pouch%", definition.id)
                    .replace("%rewards%", result.awarded.toString())
                player.sendMessage(TextUtil.mm(message))
            }.onFailure {
                warn("Pouch ${definition.id} reward message failed for ${player.name}: ${it::class.simpleName}")
            }
            runCatching {
                player.playSound(
                    player.location,
                    definition.open.sound,
                    definition.open.volume.coerceAtLeast(0f),
                    definition.open.pitch.coerceIn(0.5f, 2f),
                )
            }.onFailure {
                warn("Pouch ${definition.id} sound failed for ${player.name}: ${it::class.simpleName}")
            }
            if (result.failures.isNotEmpty()) {
                warn("Pouch ${definition.id} opened with ${result.failures.size} failed reward(s) for ${player.name}")
            }
        }
        return result
    }
}
