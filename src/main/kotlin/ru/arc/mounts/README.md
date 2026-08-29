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
- Typed per-mount abilities are configured under `abilities`. The mountain goat
  and frog have inherent high jumps. Contextual permanent upgrades are bought
  from the detail screen: water breathing and night vision for aquatic mounts,
  fire resistance for Nether mounts, and a speed-enhancing dolphin grace for
  the dolphin. Effects are refreshed while riding and expire naturally after
  dismount without removing unrelated player effects.
- Every mount has three progression levels. The third level is the deliberately
  expensive final sprint and improves speed, steering and sprint response. An
  optional per-level `scale` multiplies the selected base or skin appearance;
  omitted values remain `1.0` for backward-compatible visuals.
- Mount speed is controller-owned and ramps independently of vanilla entity
  friction. `movement.acceleration-time`, `deceleration-time` and `turn-time`
  set global response times; `0s` restores instant response. A mount may
  override any of them under `mounts.<id>.motion`. Opposite input brakes close
  to zero before the new direction accelerates, and horses keep native riding
  while ARC ramps their movement-speed attribute.
- Appearance is deterministic. ARC fixes age, scale and variants, clears random
  entity equipment, then applies only the configured skin equipment. Zombie
  baby, iron guard and diamond warlord are separate unlockable skins.
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
deterministic base appearance, skins, equipment and cosmetic trails. Spawn and
survival keep separate tracked copies for their world and purchasing policies.

Metrics use no player or transaction labels:

- `arc_mounts_enabled`, `arc_mounts_purchases_enabled`;
- `arc_mounts_catalog_entries`, `arc_mounts_active_sessions`;
- `arc_mount_purchase_journal_unresolved`;
- `arc_mount_purchase_journal_records{status=...}`.

The package is self-contained behind `MountOwnership`, `MountWallet` and
`MountPurchaseJournal`; `MountModule` is its only ARC lifecycle entry point.
