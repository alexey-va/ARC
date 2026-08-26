package ru.arc.autobuild

import de.tr7zw.changeme.nbtapi.NBT
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.util.TextUtil.strip
import java.util.UUID

data class BuildBookTransform(
    val rotation: Int = 0,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val offsetZ: Int = 0,
) {
    fun validated(maxOffset: Int = BuildBookSettings.maxOffset): BuildBookTransform = apply {
        require(rotation in CARDINAL_ROTATIONS) { "Build-book rotation must be cardinal" }
        require(offsetX in -maxOffset..maxOffset) { "Build-book X offset is outside its safety bound" }
        require(offsetY in -maxOffset..maxOffset) { "Build-book Y offset is outside its safety bound" }
        require(offsetZ in -maxOffset..maxOffset) { "Build-book Z offset is outside its safety bound" }
    }

    fun rotate(delta: Int): BuildBookTransform =
        copy(rotation = normalizeRotation(rotation + delta)).validated()

    fun offset(dx: Int = 0, dy: Int = 0, dz: Int = 0): BuildBookTransform =
        copy(
            offsetX = (offsetX + dx).coerceIn(-BuildBookSettings.maxOffset, BuildBookSettings.maxOffset),
            offsetY = (offsetY + dy).coerceIn(-BuildBookSettings.maxOffset, BuildBookSettings.maxOffset),
            offsetZ = (offsetZ + dz).coerceIn(-BuildBookSettings.maxOffset, BuildBookSettings.maxOffset),
        ).validated()

    /** Rotates a local book offset into the same world axes as the structure. */
    fun rotatedOffset(fullRotation: Int): Triple<Int, Int, Int> =
        when (normalizeRotation(fullRotation)) {
            90 -> Triple(-offsetZ, offsetY, offsetX)
            180 -> Triple(-offsetX, offsetY, -offsetZ)
            270 -> Triple(offsetZ, offsetY, -offsetX)
            else -> Triple(offsetX, offsetY, offsetZ)
        }

    companion object {
        val CARDINAL_ROTATIONS = setOf(0, 90, 180, 270)

        fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

        fun parseLegacy(rotation: String?, yOffset: String?): BuildBookTransform? {
            val parsedRotation = rotation?.toDoubleOrNull()?.toInt() ?: 0
            val parsedYOffset = yOffset?.toDoubleOrNull()?.toInt() ?: 0
            return runCatching {
                BuildBookTransform(
                    rotation = normalizeRotation(parsedRotation),
                    offsetY = parsedYOffset,
                ).validated()
            }.getOrNull()
        }
    }
}

data class BuildBookData(
    val buildingId: String,
    val title: String,
    val transform: BuildBookTransform = BuildBookTransform(),
    val playerCreated: Boolean = false,
    val creatorId: UUID? = null,
    val blockCount: Int? = null,
    val cooldownSeconds: Long? = null,
) {
    fun validated(): BuildBookData = apply {
        require(BUILDING_ID.matches(buildingId)) { "Build-book building id is invalid" }
        require(title.isNotBlank() && title.length <= 48 && title.none(Char::isISOControl)) { "Build-book title is invalid" }
        transform.validated()
        require(blockCount == null || blockCount in 1..10_000) { "Build-book block count is invalid" }
        require(cooldownSeconds == null || cooldownSeconds in 0..BuildCooldownPolicy.MAX_SECONDS) {
            "Build-book cooldown is invalid"
        }
        require(!playerCreated || creatorId != null) { "Player-created build books require a creator" }
    }

    companion object {
        private val BUILDING_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
    }
}

object BuildBookSettings {
    private val config: Config get() = ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")

    val maxOffset: Int get() = config.integer("build-book.player-copy.max-offset", 16)
    val maxBooksPerPlayer: Int get() = config.integer("build-book.player-copy.max-per-player", 24)
    val customModelData: Int get() = config.integer("build-book.player-copy.custom-model-data", 0)
    val defaultTitle: String get() = config.string("build-book.player-copy.default-name", "Моя постройка")

    fun validate() {
        require(maxOffset in 0..64) { "Build-book max-offset must be between 0 and 64" }
        require(maxBooksPerPlayer in 1..100) { "Build-book max-per-player must be between 1 and 100" }
        require(customModelData >= 0) { "Build-book custom-model-data cannot be negative" }
        require(defaultTitle.isNotBlank() && defaultTitle.length <= 48 && defaultTitle.none(Char::isISOControl)) {
            "Build-book default name is invalid"
        }
        REQUIRED_SCALARS.forEach { path -> require(config.stringOrNull(path) != null) { "Missing build-book text '$path'" } }
        REQUIRED_LISTS.forEach { path -> require(config.stringListOrNull(path)?.isNotEmpty() == true) { "Missing build-book lore '$path'" } }
    }

    private val REQUIRED_SCALARS = setOf(
        "build-book.display-name",
        "build-book.received",
        "build-book.editor.title",
        "build-book.editor.invalid",
        "build-book.editor.no-permission",
        "build-book.editor.preview-blocked",
        "build-book.editor.overview.name",
        "build-book.editor.axis-x.name",
        "build-book.editor.axis-y.name",
        "build-book.editor.axis-z.name",
        "build-book.editor.rotation.name",
        "build-book.editor.reset.name",
        "build-book.editor.close.name",
    )
    private val REQUIRED_LISTS = setOf(
        "build-book.lore",
        "build-book.editor.overview.lore",
        "build-book.editor.axis-x.lore",
        "build-book.editor.axis-y.lore",
        "build-book.editor.axis-z.lore",
        "build-book.editor.rotation.lore",
        "build-book.editor.reset.lore",
        "build-book.editor.close.lore",
    )
}

object BuildBookCodec {
    private const val SCHEMA_VERSION = 1
    private val schemaKey get() = NamespacedKey(ARC.instance, "build_book_schema")
    private val buildingKey get() = NamespacedKey(ARC.instance, "build_book_id")
    private val titleKey get() = NamespacedKey(ARC.instance, "build_book_title")
    private val rotationKey get() = NamespacedKey(ARC.instance, "build_book_rotation")
    private val offsetXKey get() = NamespacedKey(ARC.instance, "build_book_offset_x")
    private val offsetYKey get() = NamespacedKey(ARC.instance, "build_book_offset_y")
    private val offsetZKey get() = NamespacedKey(ARC.instance, "build_book_offset_z")
    private val playerCreatedKey get() = NamespacedKey(ARC.instance, "build_book_player_created")
    private val creatorKey get() = NamespacedKey(ARC.instance, "build_book_creator")
    private val blockCountKey get() = NamespacedKey(ARC.instance, "build_book_block_count")
    private val cooldownKey get() = NamespacedKey(ARC.instance, "build_book_cooldown_seconds")

    fun read(item: ItemStack): BuildBookData? {
        if (item.type != Material.BOOK) return null
        val pdc = item.itemMeta?.persistentDataContainer ?: return null
        val buildingId = pdc.get(buildingKey, PersistentDataType.STRING)
        if (buildingId != null) {
            if (pdc.get(schemaKey, PersistentDataType.INTEGER) != SCHEMA_VERSION) return null
            return runCatching {
                BuildBookData(
                    buildingId = buildingId,
                    title = pdc.get(titleKey, PersistentDataType.STRING)?.takeIf(String::isNotBlank) ?: buildingId,
                    transform = BuildBookTransform(
                        rotation = pdc.get(rotationKey, PersistentDataType.INTEGER) ?: 0,
                        offsetX = pdc.get(offsetXKey, PersistentDataType.INTEGER) ?: 0,
                        offsetY = pdc.get(offsetYKey, PersistentDataType.INTEGER) ?: 0,
                        offsetZ = pdc.get(offsetZKey, PersistentDataType.INTEGER) ?: 0,
                    ),
                    playerCreated = (pdc.get(playerCreatedKey, PersistentDataType.BYTE) ?: 0) != 0.toByte(),
                    creatorId = pdc.get(creatorKey, PersistentDataType.STRING)?.let(UUID::fromString),
                    blockCount = pdc.get(blockCountKey, PersistentDataType.INTEGER),
                    cooldownSeconds = pdc.get(cooldownKey, PersistentDataType.LONG),
                ).validated()
            }.getOrNull()
        }
        return readLegacy(item)
    }

    fun write(item: ItemStack, data: BuildBookData) {
        val checked = data.validated()
        item.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            pdc.set(schemaKey, PersistentDataType.INTEGER, SCHEMA_VERSION)
            pdc.set(buildingKey, PersistentDataType.STRING, checked.buildingId)
            pdc.set(titleKey, PersistentDataType.STRING, checked.title)
            pdc.set(rotationKey, PersistentDataType.INTEGER, checked.transform.rotation)
            pdc.set(offsetXKey, PersistentDataType.INTEGER, checked.transform.offsetX)
            pdc.set(offsetYKey, PersistentDataType.INTEGER, checked.transform.offsetY)
            pdc.set(offsetZKey, PersistentDataType.INTEGER, checked.transform.offsetZ)
            pdc.set(playerCreatedKey, PersistentDataType.BYTE, (if (checked.playerCreated) 1 else 0).toByte())
            checked.creatorId?.let { pdc.set(creatorKey, PersistentDataType.STRING, it.toString()) }
            checked.blockCount?.let { pdc.set(blockCountKey, PersistentDataType.INTEGER, it) }
            checked.cooldownSeconds?.let { pdc.set(cooldownKey, PersistentDataType.LONG, it) }
        }
    }

    fun update(item: ItemStack, data: BuildBookData): ItemStack = item.clone().also { updated ->
        write(updated, data)
        BuildBookItems.refreshAppearance(updated, data)
    }

    fun matches(item: ItemStack, expected: BuildBookData): Boolean = read(item) == expected

    private fun readLegacy(item: ItemStack): BuildBookData? = runCatching {
        NBT.get<BuildBookData?>(item) { nbt ->
            val buildingId = nbt.getString("arc:building_key").takeIf(String::isNotBlank) ?: return@get null
            val transform = BuildBookTransform.parseLegacy(
                nbt.getString("arc:rotation").takeIf { nbt.hasTag("arc:rotation") },
                nbt.getString("arc:y_offset").takeIf { nbt.hasTag("arc:y_offset") },
            ) ?: return@get null
            val cooldown = nbt.getString("arc:cooldown_seconds")
                .takeIf { nbt.hasTag("arc:cooldown_seconds") }
                ?.toLongOrNull()
            BuildBookData(
                buildingId = buildingId,
                title = buildingId,
                transform = transform,
                cooldownSeconds = cooldown,
            ).validated()
        }
    }.getOrNull()
}

object BuildBookItems {
    fun create(data: BuildBookData, modelId: Int = BuildBookSettings.customModelData): ItemStack =
        ItemStack(Material.BOOK).also { item ->
            BuildBookCodec.write(item, data)
            refreshAppearance(item, data, modelId)
        }

    fun refreshAppearance(item: ItemStack, data: BuildBookData, modelId: Int? = null) {
        val config = ConfigManager.ofModule(ARC.instance.dataPath, "auto-build.yml")
        item.editMeta { meta ->
            strip(config.component("build-book.display-name", "<#92bed8><bold>Книга строительства"))?.let(meta::displayName)
            meta.lore(
                config.componentList("build-book.lore") {
                    tag("name", Component.text(data.title))
                    tag("rotation", Component.text(data.transform.rotation))
                    tag("offset_x", Component.text(data.transform.offsetX))
                    tag("offset_y", Component.text(data.transform.offsetY))
                    tag("offset_z", Component.text(data.transform.offsetZ))
                    tag("blocks", Component.text((data.blockCount ?: BuildingManager.getBuilding(data.buildingId)?.volume ?: "?").toString()))
                }.mapNotNull(::strip),
            )
            @Suppress("DEPRECATION")
            if (modelId != null && modelId > 0) meta.setCustomModelData(modelId)
        }
    }
}
