package ru.arc.dungeons.api;

/** Shared, deterministic outcome rules for ARC's book application flows. */
public enum ArcBookApplicationOutcome {
    SUCCESS,
    FAILURE,
    DESTROYED;

    /**
     * Applies the independent success and failure-destruction rolls used by both plugins.
     * Destruction is considered only after the success roll fails.
     */
    public static ArcBookApplicationOutcome roll(
            int successPercent,
            int destroyOnFailurePercent,
            double successRoll,
            double destroyRoll
    ) {
        if (successPercent < 0 || successPercent > 100) {
            throw new IllegalArgumentException("successPercent must be between 0 and 100");
        }
        if (destroyOnFailurePercent < 0 || destroyOnFailurePercent > 1) {
            throw new IllegalArgumentException("destroyOnFailurePercent must be between 0 and 1");
        }
        if (!Double.isFinite(successRoll) || successRoll < 0 || successRoll >= 1) {
            throw new IllegalArgumentException("successRoll must be finite and in [0, 1)");
        }
        if (!Double.isFinite(destroyRoll) || destroyRoll < 0 || destroyRoll >= 1) {
            throw new IllegalArgumentException("destroyRoll must be finite and in [0, 1)");
        }

        if (successRoll < successPercent / 100.0) return SUCCESS;
        return destroyRoll < destroyOnFailurePercent / 100.0 ? DESTROYED : FAILURE;
    }
}
