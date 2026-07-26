package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bukkit.boss.BarColor
import ru.arc.treasure.core.AeArg
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.MessageDestination
import ru.arc.treasure.core.MessageTarget
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasureMessage
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.util.UUID

/**
 * Strict native content-management boundary for ARC treasure/reward pools.
 *
 * The public JSON schema intentionally does not expose Bukkit's serialized
 * ItemStack map. Item rewards use the same ItemSpec schema as other ops tools.
 */
object OpsTreasurePoolHandlers {
    fun list(id: String? = null): Map<String, Any?> {
        val normalizedId = id?.takeIf(String::isNotBlank)?.let(::normalizePoolId)
        return OpsBukkitSync.call {
            val selected =
                if (normalizedId == null) {
                    Treasures.getAllPools().sortedBy { it.id }
                } else {
                    listOfNotNull(Treasures.getPool(normalizedId))
                }
            if (normalizedId != null && selected.isEmpty()) {
                throw NoSuchElementException("Treasure pool not found: $normalizedId")
            }
            val catalog = Treasures.getAllPools().associateBy { it.id }
            mapOf(
                "source" to "arc-treasure-pools",
                "count" to selected.size,
                "pools" to selected.map { poolToMap(it, catalog) },
            )
        }
    }

    fun preview(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val pool = parsePool(id, body)
            val catalog = validateCandidate(pool)
            mapOf(
                "source" to "arc-treasure-pools",
                "preview" to true,
                "persisted" to false,
                "exists" to (Treasures.getPool(pool.id) != null),
                "pool" to poolToMap(pool, catalog),
            )
        }

    fun upsert(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val pool = parsePool(id, body)
            val existed = Treasures.getPool(pool.id) != null
            validateCandidate(pool)
            val saved =
                runCatching { Treasures.replaceAndSave(pool) }
                    .getOrElse { throw IllegalStateException("Failed to save treasure pool ${pool.id}", it) }
            val catalog = Treasures.getAllPools().associateBy { it.id }
            mapOf(
                "source" to "arc-treasure-pools",
                "created" to !existed,
                "saved" to true,
                "pool" to poolToMap(saved, catalog),
            )
        }

    fun delete(id: String): Map<String, Any?> {
        val normalizedId = normalizePoolId(id)
        return OpsBukkitSync.call {
            if (Treasures.getPool(normalizedId) == null) {
                throw NoSuchElementException("Treasure pool not found: $normalizedId")
            }
            val dependants =
                Treasures
                    .getAllPools()
                    .filter { pool ->
                        pool.id != normalizedId &&
                            pool.treasures.any { it is Treasure.SubPool && it.poolId == normalizedId }
                    }.map { it.id }
                    .sorted()
            require(dependants.isEmpty()) {
                "Treasure pool $normalizedId is referenced by: ${dependants.joinToString()}"
            }
            val deleted =
                runCatching { Treasures.deleteAndSave(normalizedId) }
                    .getOrElse { throw IllegalStateException("Failed to delete treasure pool $normalizedId", it) }
            if (!deleted) {
                throw NoSuchElementException("Treasure pool not found: $normalizedId")
            }
            mapOf(
                "source" to "arc-treasure-pools",
                "deleted" to true,
                "id" to normalizedId,
            )
        }
    }

    internal fun parsePool(
        routeId: String,
        body: JsonObject,
    ): TreasurePool {
        val id = normalizePoolId(routeId)
        rejectUnknown(body, POOL_FIELDS)
        body.get("id")?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "id must be a string" }
            require(normalizePoolId(element.asString) == id) { "body id must match route id" }
        }

        val messages = parseMessages(body.get("messages"), "messages")
        val treasuresElement =
            body.get("treasures")?.takeUnless(JsonElement::isJsonNull)
                ?: throw IllegalArgumentException("treasures is required")
        require(treasuresElement.isJsonArray) { "treasures must be an array" }
        require(treasuresElement.asJsonArray.size() in 1..MAX_TREASURES) {
            "treasures must contain 1-$MAX_TREASURES entries; delete the pool instead of saving an empty one"
        }

        val seenIds = mutableSetOf<String>()
        val treasures =
            treasuresElement.asJsonArray.mapIndexed { index, element ->
                require(element.isJsonObject) { "treasures[$index] must be an object" }
                parseTreasure(id, index, element.asJsonObject).also {
                    require(seenIds.add(it.id)) { "treasures[$index].id is duplicated: ${it.id}" }
                }
            }
        return TreasurePool(id = id, treasures = treasures, messages = messages)
    }

    private fun parseTreasure(
        poolId: String,
        index: Int,
        body: JsonObject,
    ): Treasure {
        val path = "treasures[$index]"
        val type = requiredString(body, "type", path, 32).lowercase()
        val allowed = COMMON_TREASURE_FIELDS + (TYPE_FIELDS[type]
            ?: throw IllegalArgumentException("$path.type is unsupported: $type"))
        rejectUnknown(body, allowed, path)

        val explicitId =
            body.get("id")?.takeUnless(JsonElement::isJsonNull)?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$path.id must be a string" }
                normalizeTreasureId(it.asString, "$path.id")
            }
        val id = explicitId ?: UUID.randomUUID().toString()
        val weight = optionalInt(body, "weight", path, 1, 0, MAX_WEIGHT)
        val messages = parseMessages(body.get("messages"), "$path.messages")

        return when (type) {
            "item" -> {
                val preserveItem = optionalBoolean(body, "preserveItem", path, false)
                val itemPreview = body.get("itemPreview")?.takeUnless(JsonElement::isJsonNull)
                itemPreview?.let { require(it.isJsonObject) { "$path.itemPreview must be an object" } }
                val stack =
                    if (preserveItem) {
                        require(!body.has("item")) { "$path.item must be omitted when preserveItem=true" }
                        require(explicitId != null) { "$path.id is required when preserveItem=true" }
                        val existing = Treasures.getPool(poolId)?.findById(id)
                        require(existing is Treasure.Item) {
                            "$path cannot preserve missing or non-item reward: $id"
                        }
                        existing.stack.clone()
                    } else {
                        require(itemPreview == null) { "$path.itemPreview is output-only unless preserveItem=true" }
                        val item = requiredObject(body, "item", path)
                        OpsItemPresetHandlers.validateItemSpec(item)
                        OpsItemSpec.build(item)
                    }
                val range = parseIntRange(body.get("amount"), "$path.amount", 1, MAX_ITEM_AMOUNT)
                Treasure.Item(stack, range.first, range.second, weight, messages, id)
            }
            "money" -> {
                val range = parseDoubleRange(body.get("amount"), "$path.amount", 0.0, MAX_MONEY)
                Treasure.Money(range.first, range.second, weight, messages, id)
            }
            "command" -> {
                val commands = requiredStringList(body, "commands", path, 1, MAX_COMMANDS, MAX_COMMAND_LENGTH)
                commands.forEachIndexed { commandIndex, command ->
                    require(!command.startsWith('/')) { "$path.commands[$commandIndex] must not start with '/'" }
                    require('\n' !in command && '\r' !in command) {
                        "$path.commands[$commandIndex] must be a single line"
                    }
                }
                Treasure.Command(commands, weight, messages, id)
            }
            "sub-pool" -> {
                val poolId = requiredString(body, "poolId", path, MAX_ID_LENGTH).let(::normalizePoolId)
                Treasure.SubPool(poolId, weight, messages, id)
            }
            "enchant" -> {
                val range = parseIntRange(body.get("amount"), "$path.amount", 1, MAX_GENERATED_ITEMS)
                val exclude =
                    optionalStringList(body, "exclude", path, MAX_EXCLUDES, MAX_ID_LENGTH)
                        .map { it.lowercase() }
                        .toSet()
                Treasure.Enchant(range.first, range.second, exclude, weight, messages, id)
            }
            "potion" -> {
                val range = parseIntRange(body.get("amount"), "$path.amount", 1, MAX_GENERATED_ITEMS)
                Treasure.Potion(range.first, range.second, weight, messages, id)
            }
            "ae" -> parseAe(body, path, id, weight, messages)
            "slimefun" -> {
                val itemId = requiredString(body, "itemId", path, MAX_EXTERNAL_ID_LENGTH)
                require(EXTERNAL_ID_PATTERN.matches(itemId)) { "$path.itemId has an invalid value" }
                val range = parseIntRange(body.get("amount"), "$path.amount", 1, MAX_ITEM_AMOUNT)
                Treasure.Slimefun(itemId, range.first, range.second, weight, messages, id)
            }
            else -> error("unreachable")
        }
    }

    private fun parseAe(
        body: JsonObject,
        path: String,
        id: String,
        weight: Int,
        messages: List<TreasureMessage>,
    ): Treasure.Ae {
        val kind =
            when (requiredString(body, "kind", path, 32).lowercase()) {
                "item" -> AeKind.ITEM
                "random-book" -> AeKind.RANDOM_BOOK
                else -> throw IllegalArgumentException("$path.kind must be 'item' or 'random-book'")
            }
        val name =
            body.get("name")?.takeUnless(JsonElement::isJsonNull)?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$path.name must be a string" }
                it.asString.trim().also { value ->
                    require(value.isNotEmpty() && value.length <= MAX_EXTERNAL_ID_LENGTH) {
                        "$path.name must contain 1-$MAX_EXTERNAL_ID_LENGTH characters"
                    }
                    require(EXTERNAL_ID_PATTERN.matches(value)) { "$path.name has an invalid value" }
                }
            }
        require((kind == AeKind.ITEM) == (name != null)) {
            "$path.name is required only for kind=item"
        }
        val amount = optionalInt(body, "amount", path, 1, 1, MAX_ITEM_AMOUNT)
        require(kind == AeKind.ITEM || !body.has("amount")) {
            "$path.amount is supported only for kind=item"
        }
        val args = parseAeArgs(body.get("args"), "$path.args")
        return Treasure.Ae(kind, name, amount, args, weight, messages, id)
    }

    private fun parseAeArgs(
        element: JsonElement?,
        path: String,
    ): List<AeArg> {
        if (element == null || element.isJsonNull) return emptyList()
        require(element.isJsonArray) { "$path must be an array" }
        require(element.asJsonArray.size() <= MAX_AE_ARGS) { "$path must contain at most $MAX_AE_ARGS entries" }
        return element.asJsonArray.mapIndexed { index, entry ->
            require(entry.isJsonObject) { "$path[$index] must be an object" }
            val body = entry.asJsonObject
            rejectUnknown(body, AE_ARG_FIELDS, "$path[$index]")
            when (requiredString(body, "type", "$path[$index]", 32).lowercase()) {
                "random-tier" -> {
                    require(body.keySet() == setOf("type")) { "$path[$index] random-tier has extra range fields" }
                    AeArg.RandomTier
                }
                "random-slot" -> {
                    require(body.keySet() == setOf("type")) { "$path[$index] random-slot has extra range fields" }
                    AeArg.RandomSlot
                }
                "integer" -> {
                    val min = requiredInt(body, "min", "$path[$index]", 0, MAX_AE_INTEGER)
                    val max = optionalInt(body, "max", "$path[$index]", min, 0, MAX_AE_INTEGER)
                    require(min <= max) { "$path[$index].min must not exceed max" }
                    AeArg.IntRange(min, max)
                }
                else -> throw IllegalArgumentException(
                    "$path[$index].type must be random-tier, random-slot, or integer",
                )
            }
        }
    }

    private fun parseMessages(
        element: JsonElement?,
        path: String,
    ): List<TreasureMessage> {
        if (element == null || element.isJsonNull) return emptyList()
        require(element.isJsonArray) { "$path must be an array" }
        require(element.asJsonArray.size() <= MAX_MESSAGES) { "$path must contain at most $MAX_MESSAGES entries" }
        return element.asJsonArray.mapIndexed { index, entry ->
            require(entry.isJsonObject) { "$path[$index] must be an object" }
            val body = entry.asJsonObject
            val entryPath = "$path[$index]"
            rejectUnknown(body, MESSAGE_FIELDS, entryPath)
            val text = requiredString(body, "text", entryPath, MAX_MESSAGE_LENGTH)
            val destination =
                optionalEnum(body, "destination", entryPath, MessageDestination.CHAT) {
                    MessageDestination.valueOf(it.replace('-', '_').uppercase())
                }
            val target =
                optionalEnum(body, "target", entryPath, MessageTarget.PLAYER) {
                    MessageTarget.valueOf(it.replace('-', '_').uppercase())
                }
            val radius = optionalDouble(body, "nearbyRadius", entryPath, 50.0, 0.1, 10_000.0)
            require(target == MessageTarget.NEARBY || !body.has("nearbyRadius")) {
                "$entryPath.nearbyRadius is supported only for target=nearby"
            }
            val color =
                optionalEnum(body, "bossBarColor", entryPath, BarColor.YELLOW) {
                    BarColor.valueOf(it.replace('-', '_').uppercase())
                }
            val seconds = optionalInt(body, "bossBarSeconds", entryPath, 5, 1, 300)
            require(destination == MessageDestination.BOSS_BAR || (!body.has("bossBarColor") && !body.has("bossBarSeconds"))) {
                "$entryPath boss-bar fields are supported only for destination=boss-bar"
            }
            val subtitle =
                optionalString(body, "subtitle", entryPath, MAX_MESSAGE_LENGTH)
            require(destination == MessageDestination.TITLE || subtitle == null) {
                "$entryPath.subtitle is supported only for destination=title"
            }
            TreasureMessage(
                text = text,
                destination = destination,
                target = target,
                nearbyRadius = radius,
                bossBarColor = color,
                bossBarSeconds = seconds,
                titleSubtitle = subtitle,
            )
        }
    }

    private fun validateCandidate(candidate: TreasurePool): Map<String, TreasurePool> {
        val catalog = Treasures.getAllPools().associateBy { it.id }.toMutableMap()
        catalog[candidate.id] = candidate
        candidate.treasures.filterIsInstance<Treasure.SubPool>().forEach { subPool ->
            require(subPool.poolId in catalog) {
                "Treasure ${subPool.id} references missing sub-pool: ${subPool.poolId}"
            }
            require(!reachesPool(subPool.poolId, candidate.id, catalog, mutableSetOf())) {
                "Treasure pool ${candidate.id} would create a sub-pool cycle through ${subPool.poolId}"
            }
            require(isPoolUsable(subPool.poolId, catalog, mutableSetOf())) {
                "Treasure ${subPool.id} references unusable sub-pool: ${subPool.poolId}"
            }
        }
        require(candidate.treasures.any { it.weight > 0 }) {
            "Treasure pool ${candidate.id} must contain at least one positive-weight reward"
        }
        return catalog
    }

    private fun reachesPool(
        current: String,
        target: String,
        catalog: Map<String, TreasurePool>,
        visited: MutableSet<String>,
    ): Boolean {
        if (current == target) return true
        if (!visited.add(current)) return false
        return catalog[current]
            ?.treasures
            ?.filterIsInstance<Treasure.SubPool>()
            ?.any { reachesPool(it.poolId, target, catalog, visited) }
            ?: false
    }

    private fun isPoolUsable(
        id: String,
        catalog: Map<String, TreasurePool>,
        visiting: MutableSet<String>,
    ): Boolean {
        if (!visiting.add(id)) return false
        val pool = catalog[id] ?: return false
        val selectable = pool.treasures.filter { it.weight > 0 }
        if (selectable.isEmpty()) return false
        return selectable
            .filterIsInstance<Treasure.SubPool>()
            .all { isPoolUsable(it.poolId, catalog, visiting.toMutableSet()) }
    }

    private fun poolToMap(
        pool: TreasurePool,
        catalog: Map<String, TreasurePool>,
    ): Map<String, Any?> {
        val selectableWeight = pool.treasures.filter { it.weight > 0 }.sumOf { it.weight }
        val missingReferences =
            pool.treasures
                .filterIsInstance<Treasure.SubPool>()
                .map { it.poolId }
                .filterNot(catalog::containsKey)
                .distinct()
                .sorted()
        val duplicateIds = pool.treasures.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.sorted()
        val unusableSubPools =
            pool.treasures
                .filterIsInstance<Treasure.SubPool>()
                .filter { it.poolId in catalog }
                .map { it.poolId }
                .filterNot { isPoolUsable(it, catalog, mutableSetOf(pool.id)) }
                .distinct()
                .sorted()
        val cyclic =
            pool.treasures
                .filterIsInstance<Treasure.SubPool>()
                .any { reachesPool(it.poolId, pool.id, catalog, mutableSetOf()) }
        return linkedMapOf(
            "id" to pool.id,
            "size" to pool.size,
            "totalWeight" to pool.totalWeight,
            "selectableWeight" to selectableWeight,
            "healthy" to
                (
                    selectableWeight > 0 &&
                        missingReferences.isEmpty() &&
                        unusableSubPools.isEmpty() &&
                        duplicateIds.isEmpty() &&
                        !cyclic
                ),
            "cyclic" to cyclic,
            "missingSubPools" to missingReferences,
            "unusableSubPools" to unusableSubPools,
            "duplicateTreasureIds" to duplicateIds,
            "messages" to pool.messages.map(::messageToMap),
            "treasures" to pool.treasures.map { treasureToMap(it, selectableWeight) },
        )
    }

    private fun treasureToMap(
        treasure: Treasure,
        selectableWeight: Int,
    ): Map<String, Any?> {
        val result =
            linkedMapOf<String, Any?>(
                "id" to treasure.id,
                "type" to treasure.type,
                "weight" to treasure.weight,
                "probability" to
                    if (treasure.weight > 0 && selectableWeight > 0) {
                        treasure.weight.toDouble() / selectableWeight
                    } else {
                        0.0
                    },
            )
        when (treasure) {
            is Treasure.Item -> {
                result["preserveItem"] = true
                result["itemPreview"] = OpsItemSpec.toMap(treasure.stack)
                result["amount"] = rangeToMap(treasure.min, treasure.max)
            }
            is Treasure.Money -> result["amount"] = rangeToMap(treasure.min, treasure.max)
            is Treasure.Command -> result["commands"] = treasure.commands
            is Treasure.SubPool -> result["poolId"] = treasure.poolId
            is Treasure.Enchant -> {
                result["amount"] = rangeToMap(treasure.min, treasure.max)
                result["exclude"] = treasure.exclude.sorted()
            }
            is Treasure.Potion -> result["amount"] = rangeToMap(treasure.min, treasure.max)
            is Treasure.Ae -> {
                result["kind"] = if (treasure.kind == AeKind.ITEM) "item" else "random-book"
                treasure.itemName?.let { result["name"] = it }
                if (treasure.kind == AeKind.ITEM) result["amount"] = treasure.amount
                result["args"] = treasure.args.map(::aeArgToMap)
            }
            is Treasure.Slimefun -> {
                result["itemId"] = treasure.itemId
                result["amount"] = rangeToMap(treasure.min, treasure.max)
            }
        }
        result["messages"] = treasure.messages.map(::messageToMap)
        return result
    }

    private fun messageToMap(message: TreasureMessage): Map<String, Any?> =
        buildMap {
            put("text", message.text)
            put("destination", message.destination.name.lowercase().replace('_', '-'))
            put("target", message.target.name.lowercase().replace('_', '-'))
            if (message.target == MessageTarget.NEARBY) put("nearbyRadius", message.nearbyRadius)
            if (message.destination == MessageDestination.BOSS_BAR) {
                put("bossBarColor", message.bossBarColor.name.lowercase().replace('_', '-'))
                put("bossBarSeconds", message.bossBarSeconds)
            }
            if (message.destination == MessageDestination.TITLE) {
                message.titleSubtitle?.let { put("subtitle", it) }
            }
        }

    private fun aeArgToMap(arg: AeArg): Map<String, Any?> =
        when (arg) {
            AeArg.RandomTier -> mapOf("type" to "random-tier")
            AeArg.RandomSlot -> mapOf("type" to "random-slot")
            is AeArg.IntRange -> mapOf("type" to "integer", "min" to arg.min, "max" to arg.max)
        }

    private fun rangeToMap(
        min: Number,
        max: Number,
    ): Any = if (min == max) min else mapOf("min" to min, "max" to max)

    private fun parseIntRange(
        element: JsonElement?,
        path: String,
        minAllowed: Int,
        maxAllowed: Int,
    ): Pair<Int, Int> {
        val required = element?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("$path is required")
        if (required.isJsonPrimitive && required.asJsonPrimitive.isNumber) {
            val value = parseInt(required, path, minAllowed, maxAllowed)
            return value to value
        }
        require(required.isJsonObject) { "$path must be an integer or {min,max}" }
        val body = required.asJsonObject
        rejectUnknown(body, RANGE_FIELDS, path)
        val min = requiredInt(body, "min", path, minAllowed, maxAllowed)
        val max = optionalInt(body, "max", path, min, minAllowed, maxAllowed)
        require(min <= max) { "$path.min must not exceed max" }
        return min to max
    }

    private fun parseDoubleRange(
        element: JsonElement?,
        path: String,
        minAllowed: Double,
        maxAllowed: Double,
    ): Pair<Double, Double> {
        val required = element?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("$path is required")
        if (required.isJsonPrimitive && required.asJsonPrimitive.isNumber) {
            val value = parseDouble(required, path, minAllowed, maxAllowed)
            return value to value
        }
        require(required.isJsonObject) { "$path must be a number or {min,max}" }
        val body = required.asJsonObject
        rejectUnknown(body, RANGE_FIELDS, path)
        val min =
            body.get("min")?.takeUnless(JsonElement::isJsonNull)?.let {
                parseDouble(it, "$path.min", minAllowed, maxAllowed)
            } ?: throw IllegalArgumentException("$path.min is required")
        val max =
            body.get("max")?.takeUnless(JsonElement::isJsonNull)?.let {
                parseDouble(it, "$path.max", minAllowed, maxAllowed)
            } ?: min
        require(min <= max) { "$path.min must not exceed max" }
        return min to max
    }

    private fun requiredObject(
        body: JsonObject,
        field: String,
        path: String,
    ): JsonObject {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("$path.$field is required")
        require(element.isJsonObject) { "$path.$field must be an object" }
        return element.asJsonObject
    }

    private fun requiredString(
        body: JsonObject,
        field: String,
        path: String,
        maxLength: Int,
    ): String =
        optionalString(body, field, path, maxLength)
            ?: throw IllegalArgumentException("$path.$field is required")

    private fun optionalString(
        body: JsonObject,
        field: String,
        path: String,
        maxLength: Int,
    ): String? =
        body.get(field)?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$path.$field must be a string" }
            element.asString.trim().also {
                require(it.isNotEmpty()) { "$path.$field must not be empty" }
                require(it.length <= maxLength) { "$path.$field must be at most $maxLength characters" }
            }
        }

    private fun requiredStringList(
        body: JsonObject,
        field: String,
        path: String,
        minSize: Int,
        maxSize: Int,
        maxLength: Int,
    ): List<String> {
        val result = optionalStringList(body, field, path, maxSize, maxLength)
        require(result.size >= minSize) { "$path.$field must contain at least $minSize entries" }
        return result
    }

    private fun optionalStringList(
        body: JsonObject,
        field: String,
        path: String,
        maxSize: Int,
        maxLength: Int,
    ): List<String> {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull) ?: return emptyList()
        require(element.isJsonArray) { "$path.$field must be an array of strings" }
        require(element.asJsonArray.size() <= maxSize) { "$path.$field must contain at most $maxSize entries" }
        return element.asJsonArray.mapIndexed { index, entry ->
            require(entry.isJsonPrimitive && entry.asJsonPrimitive.isString) {
                "$path.$field[$index] must be a string"
            }
            entry.asString.trim().also {
                require(it.isNotEmpty() && it.length <= maxLength) {
                    "$path.$field[$index] must contain 1-$maxLength characters"
                }
            }
        }
    }

    private fun requiredInt(
        body: JsonObject,
        field: String,
        path: String,
        min: Int,
        max: Int,
    ): Int {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull)
            ?: throw IllegalArgumentException("$path.$field is required")
        return parseInt(element, "$path.$field", min, max)
    }

    private fun optionalInt(
        body: JsonObject,
        field: String,
        path: String,
        default: Int,
        min: Int,
        max: Int,
    ): Int =
        body.get(field)?.takeUnless(JsonElement::isJsonNull)?.let {
            parseInt(it, "$path.$field", min, max)
        } ?: default

    private fun optionalBoolean(
        body: JsonObject,
        field: String,
        path: String,
        default: Boolean,
    ): Boolean =
        body.get(field)?.takeUnless(JsonElement::isJsonNull)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "$path.$field must be a boolean" }
            it.asBoolean
        } ?: default

    private fun parseInt(
        element: JsonElement,
        path: String,
        min: Int,
        max: Int,
    ): Int {
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$path must be an integer" }
        val number = element.asBigDecimal
        val value = runCatching { number.intValueExact() }.getOrNull()
            ?: throw IllegalArgumentException("$path must be an integer")
        require(value in min..max) { "$path must be between $min and $max" }
        return value
    }

    private fun optionalDouble(
        body: JsonObject,
        field: String,
        path: String,
        default: Double,
        min: Double,
        max: Double,
    ): Double =
        body.get(field)?.takeUnless(JsonElement::isJsonNull)?.let {
            parseDouble(it, "$path.$field", min, max)
        } ?: default

    private fun parseDouble(
        element: JsonElement,
        path: String,
        min: Double,
        max: Double,
    ): Double {
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$path must be a number" }
        val value = element.asDouble
        require(value.isFinite() && value in min..max) { "$path must be finite and between $min and $max" }
        return value
    }

    private fun <T> optionalEnum(
        body: JsonObject,
        field: String,
        path: String,
        default: T,
        parser: (String) -> T,
    ): T {
        val raw = optionalString(body, field, path, 32) ?: return default
        return runCatching { parser(raw) }
            .getOrElse { throw IllegalArgumentException("$path.$field has an unsupported value: $raw") }
    }

    private fun rejectUnknown(
        body: JsonObject,
        allowed: Set<String>,
        path: String = "body",
    ) {
        val unknown = body.keySet() - allowed
        require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.sorted().joinToString()}" }
    }

    private fun normalizePoolId(raw: String): String {
        val id = raw.trim().lowercase()
        require(POOL_ID_PATTERN.matches(id)) {
            "Treasure pool ID must match ${POOL_ID_PATTERN.pattern}: $raw"
        }
        return id
    }

    private fun normalizeTreasureId(
        raw: String,
        path: String,
    ): String {
        val id = raw.trim().lowercase()
        require(TREASURE_ID_PATTERN.matches(id)) {
            "$path must match ${TREASURE_ID_PATTERN.pattern}"
        }
        return id
    }

    private val POOL_ID_PATTERN = Regex("^[a-z][a-z0-9_-]{0,63}$")
    private val TREASURE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val EXTERNAL_ID_PATTERN = Regex("^[A-Za-z0-9_:.+-]{1,128}$")
    private const val MAX_ID_LENGTH = 64
    private const val MAX_EXTERNAL_ID_LENGTH = 128
    private const val MAX_TREASURES = 500
    private const val MAX_WEIGHT = 1_000_000
    private const val MAX_ITEM_AMOUNT = 6400
    private const val MAX_GENERATED_ITEMS = 64
    private const val MAX_MONEY = 1_000_000_000_000.0
    private const val MAX_COMMANDS = 32
    private const val MAX_COMMAND_LENGTH = 512
    private const val MAX_EXCLUDES = 256
    private const val MAX_AE_ARGS = 16
    private const val MAX_AE_INTEGER = 1_000_000
    private const val MAX_MESSAGES = 32
    private const val MAX_MESSAGE_LENGTH = 4096

    private val POOL_FIELDS = setOf("id", "messages", "treasures")
    private val COMMON_TREASURE_FIELDS = setOf("id", "type", "weight", "messages")
    private val TYPE_FIELDS =
        mapOf(
            "item" to setOf("item", "preserveItem", "itemPreview", "amount"),
            "money" to setOf("amount"),
            "command" to setOf("commands"),
            "sub-pool" to setOf("poolId"),
            "enchant" to setOf("amount", "exclude"),
            "potion" to setOf("amount"),
            "ae" to setOf("kind", "name", "amount", "args"),
            "slimefun" to setOf("itemId", "amount"),
        )
    private val MESSAGE_FIELDS =
        setOf("text", "destination", "target", "nearbyRadius", "bossBarColor", "bossBarSeconds", "subtitle")
    private val RANGE_FIELDS = setOf("min", "max")
    private val AE_ARG_FIELDS = setOf("type", "min", "max")
}
