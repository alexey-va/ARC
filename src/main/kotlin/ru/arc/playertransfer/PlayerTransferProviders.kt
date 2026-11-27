package ru.arc.playertransfer

import net.william278.huskhomes.api.HuskHomesAPI
import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.data.BukkitData
import net.william278.husksync.data.DataSnapshot
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import ru.arc.ops.luckperms.LpSubjectRef
import ru.arc.ops.luckperms.LpSubjectType
import ru.arc.ops.luckperms.NativeLuckPermsSubjectGateway
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

internal object PlayerTransferProviders {
    /** Start on Paper's main thread. Provider futures perform storage reads asynchronously. */
    fun preview(source: UUID, target: UUID): List<CompletionStage<String>> = listOf(
        inspect("HuskHomes", "net.william278.huskhomes.api.HuskHomesAPI") { TransferHomes.preview(source, target) },
        inspect("HuskSync", "net.william278.husksync.api.BukkitHuskSyncAPI") { TransferSnapshots.preview(source, target) },
        inspect("LuckPerms", "net.luckperms.api.LuckPermsProvider") { TransferPermissions.preview(source, target) },
    )

    private fun inspect(
        name: String,
        apiClass: String,
        read: () -> CompletionStage<String>,
    ): CompletionStage<String> {
        if (!Bukkit.getPluginManager().isPluginEnabled(name)) {
            return CompletableFuture.completedFuture("$name — плагин недоступен на этом сервере")
        }
        return try {
            Class.forName(apiClass, false, javaClass.classLoader)
            read().handle { result, failure ->
                if (failure == null) "$name — $result" else readFailure(name, failure)
            }
        } catch (failure: Exception) {
            CompletableFuture.completedFuture(readFailure(name, failure))
        } catch (failure: LinkageError) {
            CompletableFuture.completedFuture(readFailure(name, failure))
        }
    }

    private fun readFailure(name: String, failure: Throwable): String {
        val cause = (failure as? CompletionException)?.cause ?: failure
        return "$name — чтение недоступно (${cause.javaClass.simpleName})"
    }
}

private object TransferHomes {
    fun preview(source: UUID, target: UUID): CompletionStage<String> {
        val api = HuskHomesAPI.getInstance()
        fun homeNames(uuid: UUID): CompletionStage<List<String>?> = api.getUserData(uuid).thenCompose { found ->
            val owner = found.orElse(null)?.user ?: return@thenCompose CompletableFuture.completedFuture(null)
            api.getUserHomes(owner).thenApply { homes -> homes.map { it.name } }
        }

        return homeNames(source).thenCombine(homeNames(target)) { legacy, premium ->
            val counts = "по данным провайдера: исходных домов ${legacy?.size ?: "запись недоступна"}, у получателя ${premium?.size ?: "запись недоступна"}"
            if (legacy == null || premium == null) return@thenCombine "$counts | совпадения имён неизвестны"
            val exact = legacy.count { it in premium }
            val targetNames = premium.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
            val collisions = legacy.filter { it.lowercase(Locale.ROOT) in targetNames }
            val sample = collisions.sorted().take(5).joinToString(", ", transform = ::transferDisplayLabel)
            val names = if (sample.isEmpty()) "" else " ($sample${if (collisions.size > 5) ", …" else ""})"
            "$counts | точных совпадений $exact, только по регистру ${collisions.size - exact}$names"
        }
    }
}

private object TransferSnapshots {
    fun preview(source: UUID, target: UUID): CompletionStage<String> {
        val api = BukkitHuskSyncAPI.getInstance()
        val online = Bukkit.getPlayer(source) != null || Bukkit.getPlayer(target) != null
        val currentVersion = api.plugin.minecraftVersion
        fun latest(uuid: UUID): CompletionStage<Snapshot?> = api.getUser(uuid).thenCompose { found ->
            val user = found.orElse(null) ?: return@thenCompose CompletableFuture.completedFuture(null)
            api.getLatestSnapshot(user).thenApply { saved ->
                saved.orElse(null)?.let { Snapshot(it, it.minecraftVersion.compareTo(currentVersion) == 0) }
            }
        }

        return latest(source).thenCombine(latest(target)) { legacy, premium ->
            val unavailable = "снимок недоступен"
            val saved = "последние сохранённые снимки | исходный ${legacy?.description ?: unavailable} | получатель ${premium?.description ?: unavailable}"
            val freshness = if (online) " | игрок онлайн на этом сервере, снимок может отставать" else ""
            val destination = when (premium?.hasProgress) {
                true -> "у получателя сохранены предметы или опыт"
                false -> "снимок получателя пуст по предметам и опыту"
                null -> "заполненность снимка получателя неизвестна"
            }
            "$saved | $destination | ${compareSlots(legacy, premium)}$freshness"
        }
    }

    private fun compareSlots(legacy: Snapshot?, premium: Snapshot?): String {
        if (legacy == null || premium == null) return "пересечения слотов неизвестны"
        if (!legacy.exactSlots || !premium.exactSlots) return "другая версия Minecraft, слоты нужно сверить вручную"

        val legacyInventory = legacy.inventory
        val premiumInventory = premium.inventory
        val inventory = if (legacyInventory != null && premiumInventory != null &&
            legacyInventory.size == premiumInventory.size && premiumInventory.size in setOf(41, 43)
        ) {
            val (overlaps, free) = transferSlotRoom(legacyInventory.used, premiumInventory.used, 36)
            "пересечений в инвентаре $overlaps, пустых у обоих обычных слотов $free/36"
        } else {
            "пересечения в инвентаре неизвестны"
        }
        val legacyEnder = legacy.enderChest
        val premiumEnder = premium.enderChest
        val ender = if (legacyEnder?.size == 27 && premiumEnder?.size == 27) {
            val (overlaps, free) = transferSlotRoom(legacyEnder.used, premiumEnder.used, 27)
            "пересечений в эндер-сундуке $overlaps, пустых у обоих слотов $free/27"
        } else {
            "пересечения в эндер-сундуке неизвестны"
        }
        return "$inventory | $ender"
    }

    private class Snapshot(snapshot: DataSnapshot.Unpacked, val exactSlots: Boolean) {
        val inventory = (snapshot.inventory.orElse(null) as? BukkitData.Items)?.contents?.let(::Slots)
        val enderChest = (snapshot.enderChest.orElse(null) as? BukkitData.Items)?.contents?.let(::Slots)
        private val experience = snapshot.experience.orElse(null)
        val hasProgress: Boolean? = when {
            inventory?.used?.isNotEmpty() == true || enderChest?.used?.isNotEmpty() == true -> true
            experience != null && (experience.totalExperience > 0 || experience.expLevel > 0 || experience.expProgress > 0) -> true
            !exactSlots || inventory?.size !in setOf(41, 43) || enderChest?.size != 27 ||
                experience == null || experience.totalExperience < 0 || experience.expLevel < 0 ||
                experience.expProgress !in 0f..1f -> null
            else -> false
        }
        val description: String = "${snapshot.id} от ${snapshot.timestamp.toInstant()}, " +
            "формат ${snapshot.formatVersion}, Minecraft ${snapshot.minecraftVersion}, " +
            "инвентарь ${inventory?.description() ?: "недоступен"}, эндер-сундук ${enderChest?.description() ?: "недоступен"}, " +
            "опыт ${experience?.let { "всего ${it.totalExperience}, уровень ${it.expLevel}, шкала ${it.expProgress}" } ?: "недоступен"}"
    }

    private class Slots(contents: Array<ItemStack?>) {
        val size = contents.size
        val used = contents.indices.filterTo(HashSet()) { contents[it]?.isEmpty == false }
        fun description(): String = "${used.size}/$size занято"
    }
}

private object TransferPermissions {
    fun preview(source: UUID, target: UUID): CompletionStage<String> {
        val users = NativeLuckPermsSubjectGateway()
        return users.get(LpSubjectRef(LpSubjectType.USER, source.toString()))
            .thenCombine(users.get(LpSubjectRef(LpSubjectType.USER, target.toString()))) { old, new ->
                val now = Instant.now()
                val legacy = old?.nodes?.filter { it.expiresAt?.isAfter(now) != false }?.toSet()
                val premium = new?.nodes?.filter { it.expiresAt?.isAfter(now) != false }?.toSet()
                val counts = "прямых активных узлов у источника ${legacy?.size ?: "запись недоступна"}, " +
                    "у получателя ${premium?.size ?: "запись недоступна"}"
                val delta = if (legacy != null && premium != null) {
                    "только у источника ${legacy.count { it !in premium }}, только у получателя ${premium.count { it !in legacy }}"
                } else {
                    "разница неизвестна"
                }
                "$counts | $delta"
            }
    }
}

/** Includes equipment in collisions and counts only ordinary slots as a relocation option. */
internal fun transferSlotRoom(source: Set<Int>, target: Set<Int>, storageSize: Int): Pair<Int, Int> =
    source.count { it in target } to (0 until storageSize).count { it !in source && it !in target }
