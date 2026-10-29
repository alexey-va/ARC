package ru.arc.aurora

/** Resolved immutable sky controls: global defaults with optional per-profile overrides. */
data class AuroraSkyOptions(
    val fanDegrees: Double = 140.0,
    val angularGapDegrees: Double = 8.0,
    val anchorCandidates: Int = 12,
    val terrainClearance: Double = 1.0,
    val scaleMin: Double = 0.8,
    val scaleMax: Double = 1.0,
    val rollRadians: Double = 0.45,
    val waveAmplitude: Double = 0.22,
    val waveCycles: Double = 1.0,
    val waveSlope: Double = 0.07,
    val waveDepth: Double = 0.05,
    val strokeDrift: Double = 0.12,
    val strokeLift: Double = 0.05,
    val heldDrift: Double = 0.06,
    val heldLift: Double = 0.025,
    val exitTicks: Int = 40,
    val freshHeadRepair: Boolean = true,
    val freshHeadMaxAgeTicks: Int = 2,
    val yawMinDegrees: Double = 0.0,
    val yawMaxDegrees: Double = 360.0,
    val helixTurns: Double = 2.0,
    val helixRadius: Double = 0.3,
    val arcSweepDegrees: Double = 240.0,
)
