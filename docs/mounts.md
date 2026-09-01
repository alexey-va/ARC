# ARC mounts

The native mounts module is configured in `plugins/ARC/modules/mounts.yml`. The bundled resource is server-neutral; RusCrafting-specific ItemsAdder GUI models stay only in the runtime mirrors.

## Progression and tuning

Each configured level unlocks a maximum base speed. Walking levels also unlock a maximum automatic step height. Players can freely select a lower active value in `/mount` → mount details → **Развитие и тюнинг**:

- `tuning.speed-percentages` selects a percentage of the current level speed;
- `tuning.walking-step-heights` contains exact selectable native step heights in blocks;
- `tuning.walking-max-step-height-by-level` defines the non-decreasing ceiling unlocked by each level.
- `mounts.<id>.size-tuning` optionally adds authored intermediate profiles. `size-tuning-defaults` also supplies every mount with an ordinary profile and two grant-only comic extremes.

The tiny and colossal profiles are deliberately not level rewards: a player must own `arc.mounts.<mount>.size.<size-id>`, issued by `/mount admin grant size ...` or another reward system. The selected state remains a separate `tuning.size` node, so revoking an entitlement safely removes the matching selection. Most mounts use ×10; already enlarged base models receive the largest safe multiplier inside the native scale envelope. Authored ×0.5, ×2 and ×3 profiles remain ordinary level-gated tuning.

With no saved choice, the maximum unlocked value is active. A saved lower choice persists after an upgrade. If a level is revoked, an out-of-range step height is clamped to the new ceiling at runtime.

The production scale is `1.10 / 1.50 / 2.00 / 3.00 / 4.00` blocks. Level 1 always clears ordinary one-block terrain, level 2 unlocks up to two blocks, and level 3 unlocks up to four. Values from an older configuration below `1.10` resolve safely to the new minimum. Heights of three and four blocks intentionally behave like wall climbing and can be less convenient under low ceilings, so the player can select a lower unlocked value.

## Permission state

Ownership and player settings use only the `arc.mounts.*` namespace. Tuning is stored as one direct positive LuckPerms node per setting:

```text
arc.mounts.<mount>.tuning.speed.<percentage>
arc.mounts.<mount>.tuning.step-height.<hundredths>
arc.mounts.<mount>.tuning.size.<size-id>
arc.mounts.<mount>.size.<grant-only-size-id>
```

For example, 65% speed and a 1.10-block step height are `arc.mounts.horse.tuning.speed.65` and `arc.mounts.horse.tuning.step-height.110`; the Ravager's large profile is `arc.mounts.ravager.tuning.size.massive`. Setters remove older nodes with the same exact prefix before writing the new state, so every server resolves one shared choice.

## Runtime behavior

Speed, step height, level/size scale, skin, glow and owned abilities resolve into one immutable runtime snapshot. A successful setting write reconciles the complete snapshot into the active ride on the main thread, so speed, step height, glow, skins and trails update immediately. Growing size is first checked against a feet-anchored prospective bounding box; a blocked growth remains saved for the next safe summon without creating mixed visual/session state. The accepted effective entity-scale range matches the native `0.0625..16` attribute contract, while player-facing tuning multipliers are bounded to `0.1..10` and every composed catalog appearance is validated before enable. Non-horse walking mounts use ARC velocity plus the native `STEP_HEIGHT` attribute. Horses retain native ridden movement and charged jumping, while ARC applies the configured speed, jump strength and selected step height continuously.

Mounts may also declare `abilities.passive.<id>`. These effects require no purchase and are refreshed only while that exact mount session is active. Every bundled mount now has at least one inherent ability, passive, upgrade, or typed behavior. Passive names are rendered as inherent features in the mount card instead of appearing as purchasable upgrades.

ARC keeps its own motion state instead of feeding Minecraft-mutated entity velocity back into the controller. Global `movement.acceleration-time`, `movement.deceleration-time`, and `movement.turn-time` values describe the time to reach about 95% of the requested response at handling multiplier `1.0`; higher-level handling shortens those times. Set a value to `0s` for instant response. Any mount may override individual values under `mounts.<id>.motion`, with omitted values inherited from the global block. A reverse input brakes nearly to zero before acceleration changes direction.

Flying sessions have two rider comfort features enabled by default:

- `rider-view.hide-flying-mount` sends rider-only invisibility metadata after the camera reaches `hide-at-pitch`; `show-at-pitch` is a lower return threshold that prevents flicker. Other players continue to see the mount, and the client keeps the vehicle relationship.
- `movement.compensate-airborne-mining` adds a transient `BLOCK_BREAK_SPEED` modifier only for the duration of a flying session. Its ×5 result cancels Minecraft's ×0.2 airborne mining penalty without affecting the player's ground speed after dismount.

The collection list always places unlocked mounts before locked mounts while preserving catalog order inside both groups. Menu lore uses real empty lore rows between state, characteristics, profile/acquisition, and action sections.

The collection no longer spends a permanent slot on balance. Price, balance and the exact remainder or shortage are shown together only in the purchase confirmation. Actionable lore ends in the shared `[▶] ЛКМ — результат` footer after one blank row, and the handler accepts only the exact click type printed there. Selected, truly locked, disabled, completed and loading states have neither the footer nor a click handler. A not-yet-owned mount with a configured first-level price is a separate actionable acquisition state; it is labelled `Доступен к получению` and opens progression instead of masquerading as locked. The full collection guide intentionally remains 13 visible rows by owner decision.

Skin cards describe only changes from the mount's base appearance. Unsupported or unchanged age, inherited scale and raw enum/particle identifiers are omitted. Trails carry localized names and emit from a rear-body anchor derived from the current scaled bounding box, so effects remain visible on small and large entities.

## Typed mount behaviors

Inherent ride mechanics live under `mounts.<id>.behaviors` and are separate from permanent potion upgrades. Every behavior owns a short player-facing description shown in the mount card. The `ram` behavior uses a fresh sprint-forward press, a bounded acceleration request window, one short active window and one target. Its swept corridor accepts only living `Enemy` entities ahead of the mount, excluding players, bosses, passive mobs and every ARC mount.

The `trample` behavior covers hostile mobs underneath a moving mount's authored body volume. It has a minimum movement fraction, bounded downward/horizontal reach, a per-target cooldown and a maximum target count. Ravager uses a 2-damage one-second **Топот**; Mega Pig uses the same typed mechanic as a wider 3-damage **Землетрясение**. Both behaviors apply damage through `target.damage(damage, rider)`, preserving ordinary Paper damage events, armor, resistance, protection-plugin cancellation, kill attribution and loot hooks. Neither behavior writes health directly, damages blocks, adds velocity or bypasses protection.

## Favorite and quick summon

An owned mount can be selected as the favorite from its detail screen in `/mount`. The selection is stored as exactly one direct positive LuckPerms node:

```text
arc.mounts.favorite.<mount>
```

Saving a new favorite removes every older direct node with the `arc.mounts.favorite.` prefix. A favorite is resolved against the current catalog and current ownership on every summon, so deleting a catalog entry or revoking its levels cannot leave a usable stale shortcut.

The favorite has two server-side quick summon paths:

- sneak + the client's **swap item with offhand** action (`Shift + F` with default controls); the swap is intercepted only when the player already has a favorite, otherwise vanilla hand swapping remains unchanged;
- right-click with the reusable **Свисток маунта**, issued from the favorite mount's detail screen. The whistle stores only an ARC item marker and always summons the currently selected favorite.

Both paths use the same summon service as the collection and detail menu. World, water, vehicle, cooldown, tuning, skin, glow, and ability checks therefore remain identical. `quick-summon.sneak-swap-hands` and `quick-summon.whistle` independently disable the two entry points; both default to `true` when omitted by an older runtime mirror.

## Administration

`/mount admin grant-all <player>` grants the maximum configured level of every catalog mount. It does not grant glow, skins, ability upgrades, or grant-only sizes; those remain independent ownership records. Use `/mount admin grant size <player> <mount> <size-id>` and the matching `revoke` command for extreme sizes.
