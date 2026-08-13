# Dartanboy Duels

Safety-hardened fork of [Dartanboy Duels](https://github.com/Dartanboy/Duels),
kept under the upstream MIT license. The original arena/command idea remains,
but match lifecycle, inventory recovery, challenge flow and statistics were
rewritten for the RusCrafting network.

The plugin keeps the upstream Bukkit plugin id `Duels` for command, data-folder
and integration compatibility. Its deployed JAR is named
`Dartanboy-Duels.jar`. It ignores the previous plugin's `config.yml`,
`lang.yml`, users and kits; only `rusduels.yml`, `rusduels-stats.yml` and
`recovery/` belong to this fork.

Safety invariants:

- persist an atomic per-player recovery snapshot before any match mutation;
- restore and save player data before acknowledging a snapshot;
- keep unresolved recovery snapshots across crashes and plugin restarts;
- restore on fatal damage, death fallback, quit, kick and plugin disable;
- scope every gameplay listener to active duel participants;
- prevent kit or own-inventory item transfer outside the duel;
- load before HuskSync so quit restoration runs before cross-server save.

Build and test:

```bash
cd /Users/alexey23/mcserver/ARC
./gradlew :rus-duels:test :rus-duels:jar
```
