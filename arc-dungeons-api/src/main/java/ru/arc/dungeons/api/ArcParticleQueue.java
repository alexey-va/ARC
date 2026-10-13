package ru.arc.dungeons.api;
import com.destroystokyo.paper.ParticleBuilder;
/** Shares ARC's existing aggregate per-tick particle budget. */
public interface ArcParticleQueue { void queue(ParticleBuilder particle); }
