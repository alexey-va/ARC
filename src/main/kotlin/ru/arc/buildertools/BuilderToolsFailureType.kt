package ru.arc.buildertools

/** Secret-safe exception classification for SQL, economy, and plugin boundaries. */
internal object BuilderToolsFailureType {
    private val SAFE_NAME = Regex("[A-Za-z0-9_$]{1,80}")

    fun of(failure: Throwable?): String {
        if (failure == null) return "missing_result"
        val name = failure.javaClass.simpleName
        return name.takeIf(SAFE_NAME::matches) ?: "unknown_failure"
    }
}
