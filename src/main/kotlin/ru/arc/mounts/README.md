# ARC mounts

Native production replacement for `Denizen/scripts/activities/rideable_mobs.dsc`.

## Player behavior

- `/mount` opens a paginated 30-mount collection with walking, flying and
  swimming filters. Left click summons an owned mount; right click opens its
  progression, glow and appearance controls.
- Walking mounts use WASD, automatically step over one-block terrain and use
  Space to jump. Horses retain native ridden physics so gravity and terrain
  transitions remain correct; hold and release Space for their charged jump.
  Flying and swimming mounts use WASD, Space to ascend and Shift to descend.
  Every mount uses double Shift to dismount; a single Shift never ends the ride.
- Typed per-mount abilities are configured under `abilities`. The mountain
  goat, frog, horse and fox have authored jump strengths. Contextual permanent
  upgrades are bought from the detail screen: night vision also fits the
  skeleton and Enderman, water breathing supports aquatic mounts, fire
  resistance protects Nether mounts, and dolphin grace accelerates the
  dolphin. Effects are refreshed while riding and expire naturally after
  dismount without removing unrelated player effects.
- Every mount has three progression levels. The third level is the deliberately
  expensive final sprint and improves speed, steering and sprint response. An
  optional per-level `scale` multiplies the selected base or skin appearance;
  omitted values remain `1.0` for backward-compatible visuals.
- Individual mounts may expose authored `size-tuning` profiles. These are not a
  global percentage slider: every profile has a player-facing name, a bounded
  multiplier and an optional level gate. The horse, Ravager and bee ship with
  compact, standard and large profiles; the large profile unlocks at level 3.
- Mount speed is controller-owned and ramps independently of vanilla entity
  friction. `movement.acceleration-time`, `deceleration-time` and `turn-time`
  set global response times; `0s` restores instant response. A mount may
  override any of them under `mounts.<id>.motion`. Opposite input brakes close
  to zero before the new direction accelerates, and horses keep native riding
  while ARC ramps their movement-speed attribute.
- Motion overrides give representative mounts a distinct weight without a
  second controller: the fox and Breeze react quickly, while the camel and
  Ravager accelerate and turn more deliberately.
- Appearance is deterministic. ARC fixes age, scale and variants, clears random
  entity equipment, then applies only the configured skin equipment. Zombie
  baby, iron guard and diamond warlord are separate unlockable skins.
- Cosmetic trails use the live scaled bounding box and movement direction to
  emit behind the body instead of at the entity origin. Their localized name,
  cadence, density, rear offset, height, spread and speed are catalog data.
- Settings are reconciled into an active ride as one immutable snapshot after
  LuckPerms persistence succeeds. Speed, step height, glow, skin, trail,
  abilities and safe size changes therefore take effect without resummoning.
  If a larger hitbox would intersect blocks, the saved size is deferred until
  the next safe summon instead of partially mutating the current ride.
- Typed `behaviors` are separate from purchasable potion abilities. The
  Ravager's `ram` is requested by a fresh sprint-forward press, waits for the
  mount to accelerate, then attempts one standard player-attributed hit on the
  first hostile mob in its swept path. It never targets players or passive
  mobs, never damages blocks, never bypasses a cancelled damage event and has a
  four-second cooldown even when the hit misses or is denied.
- The temporary vehicle entity is invulnerable and absorbs damage without
  ending the ride. Ordinary rider damage still applies; one hit whose final
  post-reduction damage reaches `safety.rider-knockoff-damage` dismisses the
  mount on the next tick. Rider suffocation inside a block remains cancelled as
  a mount hitbox safety measure.
- Rider death, logout, teleport, world change, expiry, idle timeout, invalid
  state, world-border/height escape, or genuinely leaving water removes the
  temporary entity. Kelp, seagrass and bubble columns remain valid aquatic
  environments.
- A short summon cooldown and a hard server-side velocity cap protect against
  duplicate entities and unsafe catalog values.
- Native phasing entities such as the Vex use a swept Paper collision check
  before ARC applies velocity. Blocked axes stop while free axes keep moving,
  so the mount slides along a wall instead of carrying its rider through it.

## Permissions and commands

All access, ownership and settings use only `arc.mounts.*`:

- `arc.mounts.use` — open and use `/mount`;
- `arc.mounts.<mount>.<level>` — owned progression level;
- `arc.mounts.<mount>.glow` and `.glow.disabled` — glow ownership and setting;
- `arc.mounts.<mount>.skin.<skin>` — skin ownership;
- `arc.mounts.<mount>.skin.active.<skin>` — selected skin marker;
- `arc.mounts.<mount>.ability.<ability>` — permanent contextual ability;
- `arc.mounts.<mount>.tuning.speed.<percentage>` — selected speed profile;
- `arc.mounts.<mount>.tuning.step-height.<hundredths>` — selected step height;
- `arc.mounts.<mount>.tuning.size.<size>` — selected authored size profile;
- `arc.mounts.admin` — all `/mount admin ...` operations.

The command surface is deliberately unified:

- `/mount admin summon <mount> [level] [skin]` — short test ride using the
  selected configured level, never an arbitrary raw speed;
- `/mount admin grant <level|skin|glow|ability> <player> <mount> [value]` — grant an
  exact ownership node;
- `/mount admin revoke <level|skin|glow|ability> <player> <mount> [value]` — revoke an
  exact ownership node and any dependent active skin/glow setting.

`/mount admin summon zombie 3 baby` is the administrator smoke command. No
legacy `/ride-mob`, `/unlock-mount`, `arc.mounts.ride` or `mcfine.*` mount
permission is registered or consulted.

## Spawn and protection integration

ARC tags the entity inside Paper's spawn initializer and generates a one-time
in-memory token before `CreatureSpawnEvent`. It uncancels only a cancelled
`CUSTOM` event whose owner UUID, catalog ID and token all match the currently
pending ARC summon. This lets mounts work at spawn while ordinary WorldGuard
mob-spawn restrictions remain intact.

## Economy safety

Purchases are enabled only on the spawn node. Prices are converted to exact
minor currency units and charged directly through the RedisEconomy 4.5.12 API.
Historical provider doubles may contain a sub-cent binary tail; ARC accepts
only a tiny drift within 0.05 of one minor unit, while real fractional-cent
balances still fail closed. The adapter also refuses non-zero provider tax or a
changed pre-call balance.

Before any withdrawal, ARC atomically writes
`plugins/ARC/data/mount-purchases.json`. The journal records the intended
permission and exact before/after balance evidence. Permission failure triggers
one exact compensating refund. On restart ARC recovers a proven withdrawal by
checking the direct LuckPerms node and, for an ambiguous provider call, matching
the transaction UUID, amount, currency and timestamp in RedisEconomy history.
Unprovable outcomes fail closed in `MANUAL_REVIEW`, block duplicate purchases
for that player and are exposed through logs and bounded Prometheus metrics.
ARC rechecks those records on every start and automatically resumes only when
the exact debit, refund or direct permission can be proven.

## Configuration and observability

The bundled and live file is `plugins/ARC/modules/mounts.yml`. It owns the
catalog, rarity, descriptions, three level price/speed/handling/scale values,
deterministic base appearance, skins, equipment, authored size profiles,
behaviors and cosmetic trail geometry. Spawn, survival and parkour keep
separate tracked copies for their world and purchasing policies.

Metrics use no player or transaction labels:

- `arc_mounts_enabled`, `arc_mounts_purchases_enabled`;
- `arc_mounts_catalog_entries`, `arc_mounts_active_sessions`;
- `arc_mount_purchase_journal_unresolved`;
- `arc_mount_purchase_journal_records{status=...}`.

The package is self-contained behind `MountOwnership`, `MountWallet` and
`MountPurchaseJournal`; `MountModule` is its only ARC lifecycle entry point.
