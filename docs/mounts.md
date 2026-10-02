# ARC mounts

The native mounts module is configured in `plugins/ARC/modules/mounts.yml`. The bundled resource is server-neutral; RusCrafting-specific ItemsAdder GUI models stay only in the runtime mirrors.

The bundled catalog contains 72 mounts, including the player-flight Skycruiser. The latest expansion adds Hoglin, Endermite, Piglin, Wither Skeleton, Vindicator, Creaking, Creeper, Silverfish, Witch, Camel Husk, Stray, Parched, Zoglin, Bogged, Piglin Brute, Pillager, Evoker, Shulker, Elder Guardian, Nautilus, and Zombie Nautilus. Guardian, Warden, Iron Golem, Copper Golem, and Magma Cube keep their existing canonical entries instead of being duplicated.

## Progression and tuning

Each configured level unlocks a maximum base speed. Walking levels also unlock a maximum automatic step height. The separate **Уровни** screen contains only level progression. **Настройки** brings together speed, step height, size, rider view, glow and ability upgrades, with skins reached from the same screen. Players can freely select a lower active value in `/mount` → mount details → **Настройки**:

- `tuning.speed-percentages` selects a percentage of the current level speed;
- `tuning.walking-step-heights` contains exact selectable native step heights in blocks;
- `tuning.walking-max-step-height-by-level` defines the non-decreasing ceiling unlocked by each level.
- `mounts.<id>.size-tuning` optionally adds authored intermediate profiles. `size-tuning-defaults` also supplies every mount with an ordinary profile and two grant-only comic extremes.

The tiny and colossal profiles are separate entitlements in `arc.mounts.<mount>.size.<size-id>`, issued by `/mount admin grant size ...`, rewards, or a configured permanent purchase. A `grant-only: true` size may declare `price` and `currency`; without a price it remains a reward-only entitlement. The selected state remains a separate `tuning.size` node, so revoking an entitlement safely removes the matching selection. Most mounts use ×10; already enlarged base models receive the largest safe multiplier inside the native scale envelope. Authored ×0.5, ×2 and ×3 profiles remain ordinary level-gated tuning.

The wallet is selected per purchase. Levels retain the mount's `currency`; glow uses `buy-glow-currency`; each skin, ability and paid size has its own `currency`. A skin's `price-<currency>` overrides its plain `price`. These fields fall back to the existing mount wallet when omitted, except default extreme sizes, which use tokens. Permanent purchases and admin grants write the same ownership nodes; choosing an already owned improvement is free. Purchases remain limited to the live spawn merchants and use the existing withdrawal journal and exact refund/recovery checks.

The 2026-10-02 catalog preserves every existing mount level price and existing token skin price. Simple upgrades use coins: glow costs 5,000–50,000, night vision 15,000 and water breathing 20,000. Advanced upgrades use tokens: patterned skins cost 15–150, fire resistance 50, dolphin grace 80, tiny size 40 and colossal size 100. Intermediate size, speed, step-height and rider-view choices remain free within the owned level's limits. Each purchase applies to one mount; it does not unlock that improvement across the collection.

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

Speed, step height, level/size scale, skin, glow and owned abilities resolve into one immutable runtime snapshot. A successful setting write reconciles the complete snapshot into the active ride on the main thread, so speed, step height, glow, skins and trails update immediately. Growing size is first checked against a feet-anchored prospective bounding box; a blocked growth remains saved for the next safe summon without creating mixed visual/session state. The accepted effective entity-scale range matches the native `0.0625..16` attribute contract, while player-facing tuning multipliers are bounded to `0.1..10` and every composed catalog appearance is validated before enable. Horses, Camels and Camel Husks retain native ridden physics and charged jumping or dashing, while ARC applies the configured speed, jump strength and selected step height continuously. ARC does not overwrite their velocity or facing every tick, which would conflict with the rider client's gravity and dash prediction. Other walking mounts use ARC velocity plus the native `STEP_HEIGHT` attribute. Piglins and Hoglins are kept stable outside the Nether, Shulkers remain visibly open, and active Creeper mounts cannot prime or explode.

Mounts may also declare `abilities.passive.<id>`. These effects require no purchase and are refreshed only while that exact mount session is active. Every bundled mount now has at least one inherent ability, passive, upgrade, or typed behavior. Passive names are rendered as inherent features in the mount card instead of appearing as purchasable upgrades.

Vehicle-controlled mounts keep their own motion state instead of feeding Minecraft-mutated entity velocity back into the controller. Global `movement.acceleration-time`, `movement.deceleration-time`, and `movement.turn-time` values describe the time to reach about 95% of the requested response at handling multiplier `1.0`; higher-level handling shortens those times. Set a value to `0s` for instant response. Any mount may override individual values under `mounts.<id>.motion`, with omitted values inherited from the global block. A reverse input brakes nearly to zero before acceleration changes direction unless all three timings are zero. The shipped catalog disables inertia for every mount, including per-mount overrides.

### Native vehicle flight

`control: native-flight` requires a flying `HAPPY_GHAST`. ARC equips an adult carrier with a harness and converts the configured nominal speed to its native `FLYING_SPEED` attribute. It never writes the driver’s position or velocity, or the carrier’s velocity or rotation. Vanilla Happy Ghast input handles flight along the view direction and Space ascent; double Shift retains ARC dismount handling. Native acceleration, turning and network corrections still belong to Minecraft.

Skycruiser uses `control: player-flight`, `entity: PHANTOM`, without a Happy Ghast or guest seats. The owner selected ordinary `/fly` input over seated vehicle physics. Its Phantom is a native passenger of the player: a winged companion above the pilot, rather than a vehicle under the pilot. Appearance, glow, trails and rider-only hiding apply to this cosmetic body. Scale changes do not enlarge the player's collision body.

Skycruiser has three permanent levels costing 1,200 / 1,800 / 3,000 tokens (6,000 total). Its nominal straight-flight rates before sprint/care are 0.605 / 0.825 / 1.045 blocks per tick at the production scale of 0.55. The existing nominal cap is 1.05; native diagonal and vertical travel are not a server-enforced velocity cap. Ordinary Java-client flight feel and body alignment require player acceptance; unit tests do not simulate client prediction.

Its complete optional basket is 7,130 tokens for all levels, both authored skins, all nine shared skins, fire resistance and both extreme sizes, plus 40,000 coins for glow and night vision. The currencies are separate; no exchange rate is implied.

### Native player flight

A flying mount without additional passenger seats can set `control: player-flight`; omitted `control` means `vehicle`. Only Skycruiser uses this mode in the shipped catalog; all ARC motion timings remain zero. The player uses ordinary creative-flight input and collision. The non-collidable, inert cosmetic mob rides the player through a native passenger link, so ARC never teleports a follower or writes either entity's velocity. Passenger links are replayed on the next tick after spawn/tracking, including to the pilot. Looking up/down does not steer altitude: use Space/Shift. After landing, double Space toggles ordinary flight again. Native client acceleration and sprint transitions remain distinct from ARC vehicle smoothing.

The configured level, speed tuning, ability/care boosts and sprint multiplier select a nominal straight-line fly speed. This is a conversion to native client flight units, not a server velocity clamp; diagonal/vertical input and client physics can differ. Prices, entitlements and resource quotas are unchanged. The visual body does not carry guests, take damage or execute ram/trample behaviors. Native passenger positioning replaces the old server-teleported follower and its offset setting. At the standard player pose the Phantom's feet attach 1.8 blocks above the player; size remains cosmetic. The existing rider visibility preference still applies. Java-client visual acceptance remains separate from server lifecycle tests.

The mount owns only temporary flight flags/speed. A flight-only PDC recovery marker is written before granting flight and is persisted alongside the player's native state. Removal, quit, death, world change, teleport, game-mode change and reload restore flight state; startup/join recover a saved marker after interruption. This does not restore inventory, location or unrelated player state. External fly-speed changes are preserved on cleanup; a revoked flight permission ends the mount instead of being continuously re-enabled. Double Shift or the ordinary mount removal action ends the session. Reload ends active rides, so summon again after changing `control`.


Flying sessions have two rider comfort features enabled by default:

- `rider-view.hide-flying-mount` sends rider-only invisibility metadata after the camera reaches `hide-at-pitch`; `show-at-pitch` is a lower return threshold that prevents flicker. Other players continue to see the mount; vehicle-controlled mounts keep their passenger relationship. With no saved rider preference, ordinary forms remain visible; automatic hiding defaults on only at effective appearance scale 2 or above. The same resolved default is used by summoning, live tuning and the tuning button, while an explicit player preference always wins.
- `movement.compensate-airborne-mining` adds a transient `BLOCK_BREAK_SPEED` modifier during a flying session. Native player-flight removes this modifier on landing and restores it on takeoff, so standing on the ground never gains an extra mining multiplier. Its ×5 result cancels Minecraft's ×0.2 airborne mining penalty without affecting the player's ground speed after dismount.

The collection list always places unlocked mounts before locked mounts while preserving catalog order inside both groups. Menu lore uses real empty lore rows between state, characteristics, profile/acquisition, and action sections.

Passenger seats are inherent catalog features and require no separate ownership. The configured count is the number of extra seats and excludes the driver: Camel and Camel Husk expose one, Ravager and Happy Ghast expose two, and Polar Bear exposes one. The list and detail cards show the capacity and the right-click boarding hint. A passenger exits with one Shift; the driver keeps the existing double-Shift dismount. Ending the driver's session cleans up every passenger, and flying passengers receive the existing slow-falling handoff after exit. Large non-native mounts prepare one invisible, silent Camel carrier per guest seat during summon; both carriers are native passengers of the root alongside the direct root driver. This setup takes about three seconds; during that window the passenger-preparing message asks guests to retry. A single carrier follows root yaw plus 180 degrees, placing the guest behind the driver. With two seats, the first carrier follows root yaw plus `passengers.carrier-yaw-offset`, the second follows root yaw minus it. `passengers.carrier-scale` controls carrier size. After a guest boards a custom carrier, ARC replays the full native passenger graph to the driver on the next tick (child seats first, root last). It does not teleport seats, rewrite their motion, or remount riders every tick; closed or replaced rides cannot replay stale links.

The carrier uses a fixed sitting pose. Vanilla client `startRiding` resets that pose when applying `SetPassengers`, even if the server still considers the carrier seated. A mount-owned PacketEvents listener restores sitting metadata immediately after every passenger packet containing a registered carrier, including initial tracking, later observers and driver graph replay. It reads only concurrent entity IDs on the packet thread and drops pending corrections after carrier cleanup. This preserves the short seated attachment height without position packets or repeated remounts.

The collection no longer spends a permanent slot on balance. Price, balance and the exact remainder or shortage are shown together only in the purchase confirmation. Actionable lore ends in the shared `[▶] ЛКМ — результат` footer after one blank row, and the handler accepts only the exact click type printed there. Selected, truly locked, disabled, completed and loading states have neither the footer nor a click handler. A not-yet-owned mount with a configured first-level price is a separate actionable acquisition state; it is labelled `Доступен к получению` and opens progression instead of masquerading as locked. The full collection guide intentionally remains 13 visible rows by owner decision.

Skin cards describe only changes from the mount's base appearance. Unsupported or unchanged age, inherited scale and raw enum/particle identifiers are omitted. Trails carry localized names and emit from a rear-body anchor derived from the current scaled bounding box, so effects remain visible on small and large entities. Every mount also receives nine shared effect choices. Structured patterns emit at most eight particles per interval, scale their geometry from the live body size, and retain a bounded radius on comic giant profiles.

## Typed mount behaviors

Inherent ride mechanics live under `mounts.<id>.behaviors` and are separate from permanent potion upgrades. Every behavior owns a short player-facing description shown in the mount card. The `ram` behavior uses a fresh sprint-forward press, a bounded acceleration request window, one short active window and one target. Its swept corridor accepts only living `Enemy` entities ahead of the mount, excluding players, bosses, passive mobs and every ARC mount.

The `trample` behavior covers hostile mobs underneath a moving mount's authored body volume. It has a minimum movement fraction, bounded downward/horizontal reach, a per-target cooldown and a maximum target count. Ravager uses a 2-damage one-second **Топот**; Hoglin and Zoglin use the same typed mechanic with their own authored reach, cooldown and damage. All variants apply damage through `target.damage(damage, rider)`, preserving ordinary Paper damage events, armor, resistance, protection-plugin cancellation, kill attribution and loot hooks. No behavior writes health directly, damages blocks, adds velocity or bypasses protection.

## Favorite and quick summon

An owned mount can be selected as the favorite from its detail screen in `/mount`. The selection is stored as exactly one direct positive LuckPerms node:

```text
arc.mounts.favorite.<mount>
```

Saving a new favorite removes every older direct node with the `arc.mounts.favorite.` prefix. A favorite is resolved against the current catalog and current ownership on every summon, so deleting a catalog entry or revoking its levels cannot leave a usable stale shortcut.

The favorite has two server-side quick summon paths:

- sneak + the client's **swap item with offhand** action (`Shift + F` with default controls), when the player assigns **Favorite mount** in main-menu settings. The default assignment is the main menu. `MenuShortcutController` owns the sole key listener; the mount controller only performs the selected summon. Missing favorites produce the existing guidance to select one in `/mount`;
- right-click with the reusable **Свисток маунта**, issued from the favorite mount's detail screen. The whistle stores only an ARC item marker and always summons the currently selected favorite.

Both paths use the same summon service as the collection and detail menu. World, water, vehicle, cooldown, tuning, skin, glow, and ability checks therefore remain identical. `quick-summon.sneak-swap-hands` and `quick-summon.whistle` independently disable the two entry points; both default to `true` when omitted by an older runtime mirror.

## Administration

`/mount admin grant-all <player>` grants the maximum configured level of every catalog mount. It does not grant glow, skins, ability upgrades, or grant-only sizes; those remain independent ownership records. Use `/mount admin grant size <player> <mount> <size-id>` and the matching `revoke` command for extreme sizes.

Every purchasable category uses the same entitlement as its individual administration command:

```text
/mount admin grant level <player> skycruiser 3
/mount admin grant glow <player> skycruiser
/mount admin grant skin <player> skycruiser starlight
/mount admin grant ability <player> skycruiser night-vision
/mount admin grant size <player> skycruiser colossal
```

Replace `grant` with `revoke` to remove the corresponding right. These commands grant ownership without withdrawing currency. Free speed, step-height, ordinary size and rider-view selectors remain player preferences, with their maximum values unlocked by levels.
