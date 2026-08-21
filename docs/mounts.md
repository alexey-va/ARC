# ARC mounts

The native mounts module is configured in `plugins/ARC/modules/mounts.yml`. The bundled resource is server-neutral; RusCrafting-specific ItemsAdder GUI models stay only in the runtime mirrors.

## Progression and tuning

Each configured level unlocks a maximum base speed. Walking levels also unlock a maximum automatic step height. Players can freely select a lower active value in `/mount` → mount details → **Развитие и тюнинг**:

- `tuning.speed-percentages` selects a percentage of the current level speed;
- `tuning.walking-step-heights` contains exact selectable native step heights in blocks;
- `tuning.walking-max-step-height-by-level` defines the non-decreasing ceiling unlocked by each level.

With no saved choice, the maximum unlocked value is active. A saved lower choice persists after an upgrade. If a level is revoked, an out-of-range step height is clamped to the new ceiling at runtime.

The production scale is `1.10 / 1.50 / 2.00 / 3.00 / 4.00` blocks. Level 1 always clears ordinary one-block terrain, level 2 unlocks up to two blocks, and level 3 unlocks up to four. Values from an older configuration below `1.10` resolve safely to the new minimum. Heights of three and four blocks intentionally behave like wall climbing and can be less convenient under low ceilings, so the player can select a lower unlocked value.

## Permission state

Ownership and player settings use only the `arc.mounts.*` namespace. Tuning is stored as one direct positive LuckPerms node per setting:

```text
arc.mounts.<mount>.tuning.speed.<percentage>
arc.mounts.<mount>.tuning.step-height.<hundredths>
```

For example, 65% speed and a 1.10-block step height are `arc.mounts.horse.tuning.speed.65` and `arc.mounts.horse.tuning.step-height.110`. Setters remove older nodes with the same exact prefix before writing the new state, so spawn and survival resolve one shared choice.

## Runtime behavior

The resolved speed and step height are copied into the mount session at summon time. Non-horse walking mounts use ARC velocity plus the native `STEP_HEIGHT` attribute. Horses retain native ridden movement and charged jumping, while ARC applies the configured speed, jump strength, and selected step height continuously.

Flying sessions have two rider comfort features enabled by default:

- `rider-view.hide-flying-mount` sends rider-only invisibility metadata after the camera reaches `hide-at-pitch`; `show-at-pitch` is a lower return threshold that prevents flicker. Other players continue to see the mount, and the client keeps the vehicle relationship.
- `movement.compensate-airborne-mining` adds a transient `BLOCK_BREAK_SPEED` modifier only for the duration of a flying session. Its ×5 result cancels Minecraft's ×0.2 airborne mining penalty without affecting the player's ground speed after dismount.

The collection list always places unlocked mounts before locked mounts while preserving catalog order inside both groups. Menu lore uses real empty lore rows between state, characteristics, profile/acquisition, and action sections.

## Administration

`/mount admin grant-all <player>` grants the maximum configured level of every catalog mount. It does not grant glow, skins, or ability upgrades; those remain independent ownership records.
