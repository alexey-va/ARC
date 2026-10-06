package ru.arc.itemcatalog

import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.util.TextUtil
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Random
import java.util.UUID

/** Stable, unbiased three-option selection from one frozen ordered pool. */
object RewardChoiceSelector {
    fun offeredIndices(
        voucherId: UUID,
        definitionFingerprint: String,
        options: List<PhysicalRewardChoiceOption>,
    ): List<Int> {
        require(options.size in 3..32)
        require(options.map { it.id }.distinct().size == options.size)
        val digest = MessageDigest.getInstance("SHA-256")
        val seed = digest.digest(buildString {
            append("arc-reward-choice-v1\n")
            append(voucherId).append('\n').append(definitionFingerprint).append('\n')
            options.forEach { option -> append(option.id).append('|').append(option.childFingerprint).append('\n') }
        }.toByteArray(Charsets.UTF_8))
        val random = Random(ByteBuffer.wrap(seed).long)
        val order = options.indices.toMutableList()
        for (last in order.lastIndex downTo 1) {
            val index = random.nextInt(last + 1)
            val current = order[last]
            order[last] = order[index]
            order[index] = current
        }
        return order.take(3)
    }

}

/** Native three-button confirmation UI. Closing the dialog has no callback and cannot claim a voucher. */
class RewardChoiceController {
    fun open(
        player: Player,
        voucher: PhysicalRewardVoucherIdentity,
        spec: PhysicalRewardSpec,
        offeredIndices: List<Int>,
        onSelected: (Int) -> Unit,
    ) {
        val options = spec.choiceOptions ?: return
        if (offeredIndices.size != 3 || offeredIndices.distinct().size != 3 || offeredIndices.any { it !in options.indices }) return
        val buttons = offeredIndices.mapIndexed { buttonIndex, optionIndex ->
            val option = options[optionIndex]
            PaperDialogButton(
                id = PaperDialogActionId.of("choice_$buttonIndex"),
                label = TextUtil.mm("<#9bd48d>Получить ", true).append(
                    Component.text(
                        PlainTextComponentSerializer.plainText().serialize(TextUtil.mm(option.name, true)),
                        TextColor.fromHexString("#9bd48d"),
                    ),
                ),
                tooltip = TextUtil.mm(
                    (option.description.takeIf { it.isNotEmpty() } ?: listOf("Выбрать эту награду и использовать запечатанный дар."))
                        .joinToString("\n"),
                    true,
                ),
                width = 420,
                closeDialogBeforeAction = true,
                onClick = { onSelected(optionIndex) },
            )
        }
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "reward.choice.${voucher.id.toString().replace("-", "").take(8)}",
                title = TextUtil.mm("<gold><bold>Дар на выбор", true),
                body = listOf(
                    PaperDialogBody(
                        TextUtil.mm("<#e8dfd2>Выберите одну из трёх наград (3 из ${options.size}). Эти варианты закреплены за даром.", true),
                        width = 420,
                    ),
                ),
                buttons = buttons,
                columns = 1,
            ),
        )
    }
}
