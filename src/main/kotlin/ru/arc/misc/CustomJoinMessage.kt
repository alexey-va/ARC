package ru.arc.misc

/** Plain suffix after the player name; the same wire contract is validated by ProxyARC. */
internal object CustomJoinMessage {
    const val MAX_LENGTH = 120
    const val MAX_SAVED = 10
    private val markup = setOf('<', '>', '&', '§', '%', '\\', '#')

    fun normalize(raw: String): String {
        require(raw.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it in markup }) {
            "Custom message must be plain single-line text"
        }
        val text = raw.trim()
        require(text.isNotBlank() && text.length <= MAX_LENGTH) { "Invalid custom message length" }
        return text
    }

    fun selectionKey(message: String): String = "%player_name% $message"
}
