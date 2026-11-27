package ru.arc.playertransfer

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.Tasks
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

internal fun transferDisplayLabel(value: String): String =
    value.take(32).map { character ->
        if (character.isISOControl() || character == '§' || Character.isIdentifierIgnorable(character)) '�' else character
    }.joinToString("")

internal data class PlayerTransferPair(val source: UUID, val target: UUID) {
    companion object {
        fun parse(source: String, target: String): PlayerTransferPair {
            val from = canonicalUuid(source)
            val to = canonicalUuid(target)
            require(from != to) { "Для переноса нужны два разных UUID." }
            return PlayerTransferPair(from, to)
        }

        private fun canonicalUuid(value: String): UUID {
            val id = runCatching { UUID.fromString(value) }.getOrNull()
            require(id != null && id.toString().equals(value, ignoreCase = true) &&
                (id.mostSignificantBits != 0L || id.leastSignificantBits != 0L)
            ) { "Укажите полный UUID из записи аккаунта." }
            return id
        }
    }
}

/** Read-only comparison for a console operator. It does not acquire a network-wide player lease. */
internal object PlayerTransferModule : PluginModule {
    override val name = "PlayerTransfer"
    override val priority = 89

    @Volatile
    private var activePreviewGeneration: Any? = null

    val ready: Boolean get() = activePreviewGeneration != null

    override fun init() {
        activePreviewGeneration = Any()
    }

    override fun shutdown() {
        activePreviewGeneration = null
    }

    fun preview(sender: CommandSender, pair: PlayerTransferPair) {
        val generation = activePreviewGeneration ?: return
        val (source, target) = pair
        val locallyOnline = listOf(source, target).filter { Bukkit.getPlayer(it)?.isOnline == true }
        sender.sendMessage("Перенос $source → $target. Сбор сводки, только чтение.")
        if (locallyOnline.isNotEmpty()) {
            sender.sendMessage("Сейчас на этом сервере: ${locallyOnline.joinToString()}.")
        }

        val reads = PlayerTransferProviders.preview(source, target) + listOf(
            PlayerTransferLands.preview(source, target),
            wallet(source, target),
        )
        val reports = reads.mapIndexed { index, stage ->
            stage.thenApply { it }.toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).exceptionally { failure ->
                val cause = failure.cause ?: failure
                ARC.instance.logger.warning("PlayerTransfer preview section ${index + 1}: ${cause.javaClass.simpleName}")
                "Раздел ${index + 1}: данные недоступны (${cause.javaClass.simpleName})."
            }
        }
        CompletableFuture.allOf(*reports.toTypedArray()).thenRun {
            if (activePreviewGeneration !== generation) return@thenRun
            Tasks.scheduler.runSync(Runnable {
                if (activePreviewGeneration !== generation) return@Runnable
                reports.forEach { sender.sendMessage(it.join()) }
                sender.sendMessage("Перед изменениями отдельно проверьте UUID и присутствие обоих игроков на всей сети.")
            })
        }
    }

    private fun wallet(source: UUID, target: UUID): CompletionStage<String> =
        runCatching {
            if (Bukkit.getPluginManager().isPluginEnabled("RedisEconomy")) {
                WalletPreview.read(source, target)
            } else {
                "Кошельки: RedisEconomy недоступен."
            }
        }.fold(
            onSuccess = { CompletableFuture.completedFuture(it) },
            onFailure = { CompletableFuture.failedFuture(it) },
        )

    // Keep the optional API out of the module's loaded method signatures.
    private object WalletPreview {
        fun read(source: UUID, target: UUID): String {
            val api = RedisEconomyAPI.getAPI() ?: return "Кошельки: RedisEconomy ещё не готов."
            return listOf("vault", "tokens").joinToString(prefix = "Кошельки (кеш сервера): ", separator = ", ") { name ->
                val accounts = api.getCurrencyByName(name)?.accounts
                if (accounts == null) "$name недоступна" else "$name ${format(accounts[source])} → ${format(accounts[target])}"
            }
        }

        private fun format(balance: Double?): String = when {
            balance == null -> "нет записи"
            !balance.isFinite() -> "некорректное значение"
            else -> BigDecimal.valueOf(balance).stripTrailingZeros().toPlainString()
        }
    }
}
