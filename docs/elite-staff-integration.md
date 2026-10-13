# EliteMobs spell weapons

`StaffEliteItems` owns the in-place migration and separate `arc:elite_staff_spell`
identity. Only an EliteMobs item with a valid native WANDS/STAVES identity can
use it. The original FMM ID and material remain the native progression identity.

| Native family | Spell IDs |
| --- | --- |
| WANDS (8) | chain, mark, frost, lance, nova, lunar, requiem, prism |
| STAVES (9) | ember, icicle, fault, gale, tide, necromancer, inferno, gravity, ossuary |

Assignment is uniform within the original family and persisted once. Drops and
the existing EliteLoot inventory refresh use the same presentation owner; the
held-slot/input boundary also migrates old items taken from storage. Unopened
offline storage is not rewritten. Native item level, price, soulbind, durability,
attributes and enchantment records are preserved. Additive native upgrades clone
the source, so the assigned spell survives. Repeat presentation removes only its
recorded ARC lore block before appending current controls.

The ordinary and duel spells use one renderer. RMB and Shift+RMB attack; LMB and
Shift+LMB defend. Only the comet and icicle primary attacks charge; fire and gravity
are held channels. Native class selection through F remains separate.

`StaffSpellInput` consumes owned input at HIGH before FMM's HIGHEST weapon
handlers. `StaffModeledInput` covers FMM hitboxes. Both interaction result fields
must be DENY for FMM air clicks; `startUsingItem` explicitly owns held use.
Mapped spell damage uses Paper MAGIC attribution, preventing FMM from treating a
spell impact as another melee cast. No native listener is removed or replaced.

`StaffSpellDamage` captures the real item level, material, native family/skill,
EliteMobs damage enchantment multiplier, critical hit and Loud Strikes state.
The snapshot uses an ARC-owned family enum so arenas without EliteMobs can
still construct casts. The guarded hit path converts it back to the native
skill; EliteMobs records that combat context for its per-skill kill XP.
Capture retains native durability preparation and last-durability rejection;
it does not introduce an additional per-cast durability charge.
`StaffEliteEnchantments` queries FMM's numeric enchantment providers at cast time:
multicast combines the original count × per-missile damage into the whole new
volley; blast increases its area/width; ignition runs after accepted damage and
respects the combustion event. Provider parameters remain authoritative.

Compatibility evidence: Spawn and Survival run EliteMobs 10.9.8; Spawn's root JAR
SHA-256 is `7c01d9fc2375d7f23d3a875f6e63f905672bbb73ab735c2672f747f4d79b2b24`.
FMM 2.12.6 SHA-256 is
`23644ddb9accc68f348bb3ede2ac731bcc0c56d6c01075e676e2456fed29dfc5`.
The used modeled-click and numeric-query signatures were compared against ARC's
compile-time FMM 2.12.1 artifact and the active 2.12.6 bytecode. FMM's inventory
model upgrader leaves items with an explicit item model alone.

## Balance and evidence limits

[Assessment](elite-staff-balance-20261013.json) keeps currency, XP and items
separate. Conversion directly grants zero of each; level/value/drop odds and
existing enchantment levels do not change. No new purchase, reroll, salvage or
refund path is introduced. SELL and the planned contract transition are unchanged.
Native reference attack budgets before skill/item scaling are 0.672 per 11 ticks
for wand missiles and 1.25 per 40 ticks for staff fireballs. The replacement uses
the existing ARC spell cooldowns and powers; area and control utility differ,
so equal combat throughput is not claimed. Kill income and collection time for
all 17 variants remain unmeasured; no currency conversion or new spending price
is inferred from the Vault/token ledger.

Focused checks cover stable assignment, metadata preservation, tooltip refresh,
input suppression, item-level/family damage, provider modifiers and existing
staff behavior. A passing mock/server check is not native-client visual or
hold/release acceptance. Delivery, runtime activation and client observations
are recorded separately in the task's deployment receipt.
