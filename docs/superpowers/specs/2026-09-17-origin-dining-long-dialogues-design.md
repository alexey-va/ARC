# Origin Dining Long Ambient Dialogues

## Goal

Make the two Origin dining scenes feel inhabited when a player is nearby. The
five existing NPC pairs will receive a large authored catalog of Minecraft-
themed ambient conversations instead of repeating two isolated lines.

The target catalog is 30 chains: six variants for each of the five existing
guest pairs. Each chain contains 8–10 short Russian lines, alternating between
the configured first and second NPC. The chains are local TextDisplay speech,
not chat messages, and do not add player choices, quests, rewards, commands or
economy effects.

## Current boundary and evidence

The active owner is ARC Kotlin:

- `src/main/kotlin/ru/arc/origin/OriginDiningModule.kt` loads, selects and
  renders ambient conversations.
- `src/main/resources/modules/origin-dining.yml` is the bundled configuration
  and the runtime mirror is
  `ruscrafting-ops/classic/plugins/ARC/modules/origin-dining.yml`.
- `OriginDiningDialogue` currently contains only `firstLine` and `secondLine`,
  and the scheduler shows exactly those two lines.
- Existing Denizen restaurant scripts are compatibility/history material and
  must not be described as the active owner.

The current audience gate, NPC-distance gate, speech styling, look restoration,
waiter busy exclusion and reload lifecycle remain the existing contracts.

## Chosen data contract

Extend the dialogue record to store `lines: List<String>`:

```yaml
restaurant_a_creeper_01:
  first-npc-id: 433
  second-npc-id: 434
  lines:
    - "Я видел сегодня крипера у шахты."
    - "И ты всё ещё называешь это хорошим местом для прогулок?"
    - "Он стоял под деревом и смотрел на проход."
    - "Крипер не смотрит. Крипер оценивает радиус взрыва."
    - "Я поставил между нами два блока булыжника."
    - "Всего два?"
    - "Третий он уже съел вместе с моей калиткой."
    - "Значит, ужин сегодня за твой счёт."
```

The speaker is derived from the zero-based line index: even lines belong to
`first-npc-id`, odd lines to `second-npc-id`. The loader validates that every
configured chain has at least two non-blank lines and an even line count.
Existing `first-line`/`second-line` records are converted to a two-element
list as a compatibility fallback, so dormant or older mirrored configuration
does not fail to load during the migration.

No new shared or platform-neutral primitive is needed. This is a narrow
Origin-owned content/runtime DTO and scheduler change.

## Runtime flow

1. The existing `tickAmbient` finds an eligible pair using the current NPC
   spawn, waiter-busy, pair-distance and nearby-audience checks.
2. It creates a tokenized active-conversation state containing the selected
   dialogue, original rotations and the next line index. Only one ambient
   conversation may be active in this module at a time, preserving the current
   no-overlap behavior.
3. The first line is rendered immediately. Each next line is scheduled with
   the existing reply-delay range and alternates the speaker.
4. Before every scheduled line, the runtime rechecks the token, both NPCs,
   their world/spawn state, waiter busy state and nearby audience. If a waiter
   is called to a player, an NPC despawns, reload/stop invalidates the token,
   or the audience leaves, the chain is cancelled and all owned speech and
   rotations are cleaned up safely.
5. After the last line, authored rotations are restored and the normal random
   interval starts before another chain can begin. The selected dialogue ID is
   retained for no-immediate-repeat selection, including after cancellation.

The scheduled task must never emit raw line content into logs. Diagnostics use
the dialogue ID, line index, NPC IDs and cancellation reason only.

## Content direction

The 30 chains use the existing five pairs, with character-specific wording so
the same topic does not sound copied. The topic pool includes:

- creepers, TNT and sensible restaurant safety;
- redstone kitchen automation, hoppers and comparator arguments;
- Nether expeditions, ghasts, blaze rods and portal logistics;
- villagers, emerald discounts and suspiciously precise trade math;
- ancient cities, wool, sculk sensors and the Warden;
- minecarts, powered rails and cargo that never arrives on the right platform;
- the first night, beds, wooden houses and overconfident beginners;
- the Ender Dragon, elytra landings and shulker-box packing;
- archaeology, suspicious sand, brushes and the sniffer;
- potions, brewing mistakes, ocean monuments, netherite and farm projects.

Each chain has a small narrative shape: an observation or claim, a concrete
Minecraft detail, disagreement or escalation, and a punchline or local
resolution. Lines stay short enough for the existing 170-pixel TextDisplay
width; the content should be lively but suitable for a public spawn.

The six existing staff/bar records remain dormant unless explicitly added to
`scene.dialogue-ids` in a later content pass. This change focuses the larger
catalog on the five already validated seated guest pairs and avoids changing
waiter service availability at the same time.

## Documentation reconciliation

Update the active operational references in the ops repository:

- `docs/knowledge/npc-scene-origin-restaurant.md` names ARC as the owner of
  seating, ambient dialogue, waiter motion, paid dining and the native menu;
  Citizens/CMI ownership is described only for their respective entity/chair
  responsibilities.
- Its source links use the actual `.dscc` suffix and label the Denizen files as
  retired compatibility/history, not active implementation.
- `docs/knowledge/npc-scenes-origin-restaurant.json` separates the current ARC
  source/runtime owner from the 2026-09-13 live snapshot and records the legacy
  Denizen paths under an explicit compatibility field.
- `docs/knowledge/economy-origin-dining-20260912.json` keeps its dated
  historical assessment intact but labels the old Denizen source as historical
  and points to the current ARC owner, so it cannot be read as a current
  runtime claim.

No historical scene file is deleted and no executable Denizen behavior is
changed by the documentation correction.

## Compatibility and failure behavior

- Missing or empty `lines` falls back to the old two-line fields; malformed
  non-empty chains fail configuration loading with a path-specific error.
- A chain never blocks seating, paid delivery, ambient meal replacement or
  waiter navigation. Busy waiter checks win over ambient speech.
- Stop/reload cleanup invalidates active conversation state and leaves no
  persistent TextDisplay entities.
- The bundled source and `classic` mirror must be byte-for-byte identical for
  the changed configuration.

## Verification

Focused tests will cover:

- loading the new list format and legacy two-line fallback;
- rejecting blank, too-short or odd-length chains;
- counting the 30 active chains and preserving the six service dialogues;
- alternating speaker selection and token/cancellation behavior in the pure
  scheduler helper where practical;
- existing Origin dining layout regressions.

The cheapest deterministic source check remains:

```bash
./gradlew test --tests 'ru.arc.origin.OriginDiningLayoutTest'
```

After source and ops commits are published, the supported ARC deployment path
must deliver the JAR/config and perform its required activation. Source tests,
config mirror equality, active plugin state, and real player-visible speech
remain separate evidence layers; a passing unit test alone does not prove the
client saw a complete chain.
