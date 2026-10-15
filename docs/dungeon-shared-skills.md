# Automatic paired weapon progression

ArcDungeons supplies the temporary addon implementation of
[EliteMobs #232](https://github.com/MagmaGuy/EliteMobs/issues/232). It uses the
public `EliteSkillXpGainEvent` and `PlayerData` API; EliteMobs is not patched.
Compatibility was checked against the installed EliteMobs 10.9.8 JAR
(`7c01d9fc2375d7f23d3a875f6e63f905672bbb73ab735c2672f747f4d79b2b24`).

## Player behavior

- No training selector or additional command is required. The effective class,
  including the class locked for a dungeon run, and the weapon credited by
  EliteMobs determine the recipients.
- New XP catches up the weaker weapon skill, then divides evenly. Existing XP
  is never moved or removed. Armor receives its existing native awards.
- First satisfy weapon prerequisites in the current class path, using native
  stage thresholds 10, 30, 60 and 90. Adventurer and alternate weapon affinities
  such as Paladin's maces use the same rule.
- After the current stage's weapons are ready, prepare only immediate child
  forms that support the credited weapon. Choose the smallest remaining weapon
  XP requirement, with stable class ID as the tie-breaker. Once ready, the next
  eligible child can be prepared. This never selects a class, grants an ability,
  skips a challenge or traverses the whole connected weapon graph.
- An award has at most one companion recipient. It stops at the prerequisite
  threshold; any remaining XP stays with the original weapon. The next award
  reevaluates the path. Once all matching immediate children are prepared, the
  current pair keeps sharing without a level ceiling, including while waiting
  for class XP or the normal class choice. Terminal classes share continuously.
- An unsupported weapon, missing class profile or absent prerequisite recipient
  keeps the native award. Administrative skill setters and future XP books do
  not pass through this combat hook.

At the Bowmaster/BOWS and Arbalist/CROSSBOWS forks, multiple future pairs are
possible. The nearest prerequisite is prepared first; the addon cannot infer a
preferred future specialization without asking the player. There is deliberately
no additional choice or persistent training state.

## Award and persistence contract

Native contribution attribution, farming eligibility, dungeon lockouts,
reinforcement checks and perks run before the event. The addon divides the
already calculated weapon budget once. Class XP has its own earlier native
award path and is unchanged. No currencies, items or extra XP are issued.

At HIGHEST the listener prepares the split and leaves at least one XP in the
native event. At MONITOR it grants the companion only when the event is still
uncancelled, retains the prepared amount, and player data and cross-server sync
remain ready. A later zero, cancellation or replacement amount invalidates the
companion award. MONITOR handlers must obey Bukkit's read-only event contract.
One-XP awards remain entirely native: a zero source share cannot be distinguished
from another plugin's zero veto using this API.

Both totals use native `PlayerData` writes and the existing EmSync snapshots;
there is no new synchronous storage work or separate progression database.
EliteMobs does not expose an atomic two-skill commit. The companion is committed
during the event and the source by EliteMobs immediately afterward, so a process
failure between native writes is not a transactional pair guarantee.

The companion uses the public skill XP bar and combat-level display refresh.
Native private level-up titles are not invoked reflectively. The native kill XP
popup only counts the source return amount; the companion has its own XP bar.

## Balance and verification

[Before/after assessment](economy-dungeon-shared-skills-20261016.json) uses
explicit sensitivity assumptions, not measured progression rates. At 50/200/1000
eligible awards per day and hypothetical 100 XP per award, total weapon XP stays
5,000/20,000/100,000 per player-day. Direct deltas are separately zero vault,
zero tokens, zero EliteMobs crystals and zero items. Existing boosts apply once.
The SELL-to-contract transition is unaffected. Equal XP does not guarantee equal
wall-clock progression: gear access and combat throughput can change.

Focused tests cover routing across the native catalog, deterministic forks,
catch-up/splitting, finite caps, cancellation/replacement, missing data, lifecycle
cleanup and overflow. Runtime activation and an actual dungeon kill remain
separate from these source-level checks.

Removal should accompany an upstream equivalent, so two independent shared-XP
implementations cannot stack. The public API request for replacement class
abilities is separately tracked in
[EliteMobs #231](https://github.com/MagmaGuy/EliteMobs/issues/231).
