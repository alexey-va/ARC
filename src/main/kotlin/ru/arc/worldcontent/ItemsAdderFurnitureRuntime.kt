package ru.arc.worldcontent

import dev.lone.itemsadder.api.CustomFurniture
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataType

data class RuntimeFurnitureHandle(
    val root: Entity,
    val family: FurnitureFamily,
    val namespacedId: String?,
)

interface FurnitureRuntime {
    val available: Boolean

    fun inspect(entity: Entity): RuntimeFurnitureHandle?

    fun remove(
        entity: Entity,
        family: FurnitureFamily,
    ): Boolean

    fun spawnBlock(
        namespacedId: String,
        block: Block,
    ): Entity

    fun spawnPreciseNonSolid(
        namespacedId: String,
        location: Location,
    ): Entity
}

object ItemsAdderFurnitureRuntime : FurnitureRuntime {
    private val simpleItemKey = NamespacedKey("itemsadder", "placeable_entity_item")
    private val simpleBehaviourKey = NamespacedKey("itemsadder", "placeable_behaviour_type")
    private val complexKey = NamespacedKey("itemsadder", "complex_furniture")

    override val available: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")

    override fun inspect(entity: Entity): RuntimeFurnitureHandle? {
        if (!available) return null
        inspectSimple(entity)?.let { return it }
        inspectComplex(entity)?.let { return it }
        return inspectMarker(entity)
    }

    override fun remove(
        entity: Entity,
        family: FurnitureFamily,
    ): Boolean {
        if (!available || !entity.isValid) return !entity.isValid
        return when (family) {
            FurnitureFamily.SIMPLE ->
                runCatching {
                    val furniture = CustomFurniture.byAlreadySpawned(entity)
                    if (furniture != null) {
                        furniture.remove(false)
                    } else {
                        CustomFurniture.remove(entity, false)
                    }
                    !entity.isValid || entity.isDead
                }.getOrDefault(false)

            FurnitureFamily.COMPLEX -> removeComplex(entity)
        }
    }

    override fun spawnBlock(
        namespacedId: String,
        block: Block,
    ): Entity {
        check(available) { "ItemsAdder is not enabled" }
        return CustomFurniture.spawn(namespacedId, block)?.entity
            ?: throw IllegalArgumentException("Unknown or non-furniture ItemsAdder id: $namespacedId")
    }

    override fun spawnPreciseNonSolid(
        namespacedId: String,
        location: Location,
    ): Entity {
        check(available) { "ItemsAdder is not enabled" }
        return CustomFurniture.spawnPreciseNonSolid(namespacedId, location)?.entity
            ?: throw IllegalArgumentException("Unknown or non-furniture ItemsAdder id: $namespacedId")
    }

    private fun inspectSimple(entity: Entity): RuntimeFurnitureHandle? =
        runCatching {
            val furniture = CustomFurniture.byAlreadySpawned(entity) ?: return@runCatching null
            val root = furniture.entity ?: return@runCatching null
            RuntimeFurnitureHandle(root, FurnitureFamily.SIMPLE, furniture.namespacedID)
        }.getOrNull()

    private fun inspectComplex(entity: Entity): RuntimeFurnitureHandle? {
        if (entity !is LivingEntity) return null
        return ComplexApi.inspect(entity)
    }

    private fun inspectMarker(entity: Entity): RuntimeFurnitureHandle? {
        val pdc = entity.persistentDataContainer
        val simpleId = pdc.get(simpleItemKey, PersistentDataType.STRING)
        val behaviour = pdc.get(simpleBehaviourKey, PersistentDataType.STRING)
        val complexId = pdc.get(complexKey, PersistentDataType.STRING)
        val type =
            when (entity) {
                is ArmorStand -> ProbeEntityType.ARMOR_STAND
                is ItemFrame -> ProbeEntityType.ITEM_FRAME
                is ItemDisplay -> ProbeEntityType.ITEM_DISPLAY
                is LivingEntity -> ProbeEntityType.LIVING_ENTITY
                else -> ProbeEntityType.OTHER
            }
        val classified =
            ItemsAdderMarkerPolicy.classify(
                EntityProbe(
                    uuid = entity.uniqueId,
                    type = type,
                    simpleId = simpleId?.takeIf { behaviour == "furniture" },
                    complexId = complexId,
                ),
            ) ?: return null
        return RuntimeFurnitureHandle(entity, classified.family, classified.namespacedId)
    }

    private fun removeComplex(entity: Entity): Boolean = ComplexApi.remove(entity)

    private object ComplexApi {
        private val type: Class<*>? by lazy {
            runCatching { Class.forName("dev.lone.itemsadder.api.CustomComplexFurniture") }.getOrNull()
        }

        fun inspect(entity: LivingEntity): RuntimeFurnitureHandle? {
            val api = type ?: return null
            return runCatching {
                val furniture = api.getMethod("byAlreadySpawned", LivingEntity::class.java).invoke(null, entity)
                    ?: return@runCatching null
                val root = api.getMethod("getEntity").invoke(furniture) as? Entity
                    ?: return@runCatching null
                val id = entity.persistentDataContainer.get(complexKey, PersistentDataType.STRING)
                RuntimeFurnitureHandle(root, FurnitureFamily.COMPLEX, id)
            }.getOrNull()
        }

        fun remove(entity: Entity): Boolean {
            if (entity !is LivingEntity) return false
            val api = type ?: return false
            return runCatching {
                val furniture = api.getMethod("byAlreadySpawned", LivingEntity::class.java).invoke(null, entity)
                    ?: return@runCatching false
                api.getMethod("remove", Boolean::class.javaPrimitiveType).invoke(furniture, false)
                !entity.isValid || entity.isDead
            }.getOrDefault(false)
        }
    }
}
