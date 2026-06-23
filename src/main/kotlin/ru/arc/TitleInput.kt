package ru.arc

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import ru.arc.board.guis.Inputable
import ru.arc.core.Tasks
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.Logging.warn
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class TitleInput(
    val player: Player,
    val inputable: Inputable,
    val id: Int,
) {
    var timestamp: Long = System.currentTimeMillis()

    init {
        if (clearTask == null) setupTask(5)
        if (activeInputs.containsKey(player)) {
            warn("Player {} already has title input, replacing", player.name)
        }
        activeInputs[player] = this
        sendStartMessage()
        sendTitle()
    }

    fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > 120_000L

    fun sendDenyMessage(message: String) {
        inputable.denyMessage(message, id)?.let { player.sendMessage(it) }
    }

    fun sendStartMessage() {
        inputable.startMessage(id)?.let { player.sendMessage(it) }
    }

    fun sendTimeoutMessage() {
        player.sendMessage(Component.text("Вы не успели ввести текст!", NamedTextColor.RED))
    }

    private fun remove() {
        activeInputs.remove(player)
    }

    private fun sendTitle() {
        player.showTitle(
            Title.title(
                Component.text("Введите в чате...", NamedTextColor.GREEN),
                Component.text(" "),
                Title.Times.times(
                    Duration.ofMillis(1000),
                    Duration.ofMillis(58_000),
                    Duration.ofMillis(1000),
                ),
            ),
        )
    }

    companion object {
        private val activeInputs: MutableMap<Player, TitleInput> = ConcurrentHashMap()
        private var clearTask: Any? = null

        @JvmStatic
        fun setupTask(period: Long) {
            clearTask =
                Tasks.scheduler.repeating(period = period.ticks, delay = period.ticks) {
                    val iter = activeInputs.entries.iterator()
                    while (iter.hasNext()) {
                        val (p, input) = iter.next()
                        if (!p.isOnline || input.isExpired()) {
                            iter.remove()
                            if (p.isOnline) input.sendTimeoutMessage()
                        }
                    }
                }
        }

        @JvmStatic
        fun hasInput(player: Player): Boolean = activeInputs.containsKey(player)

        @JvmStatic
        fun processMessage(
            player: Player,
            message: String,
        ) {
            val titleInput = activeInputs[player] ?: return
            if (titleInput.inputable.isCancelInput(message, titleInput.id)) {
                titleInput.inputable.onInputCancel(titleInput.id)
                titleInput.remove()
                player.clearTitle()
                return
            }
            if (!titleInput.inputable.satisfy(message, titleInput.id)) {
                titleInput.sendDenyMessage(message)
                titleInput.timestamp = System.currentTimeMillis()
                titleInput.sendTitle()
                return
            }
            titleInput.inputable.setParameter(titleInput.id, message)
            titleInput.inputable.proceed()
            titleInput.remove()
            player.clearTitle()
        }
    }
}
