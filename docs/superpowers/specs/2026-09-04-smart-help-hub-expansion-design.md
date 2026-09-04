# Smart Help Hub Expansion Design

## Goal

Turn the existing native-dialog help center into the main player control surface without turning its root into a wall of buttons. The hub must help a player decide what to do, act on an item or location, repeat common actions, and recover from common problems.

## Product shape

The root remains a two-column, ten-button screen. New capabilities live behind the existing `Про меня`, `Команды и поиск`, `Активности`, `Технологии`, and `Настройки` sections:

- `Про меня`: actionable next steps, favorites, recent actions, and incoming-request shortcuts.
- `Команды и поиск`: lexical search plus deterministic parameter extraction for homes, players, and payments.
- `Активности`: a goal navigator and the currently available activity entry points.
- `Технологии`: held-item context, recipe lookup for ItemsAdder items, catalog, auction, sale, and enchantment entry points.
- `Настройки`: current chat mode and explicit switches whose current state is not falsely inferred.
- `Что случилось?`: symptom-first diagnostics with concrete checks and safe recovery actions.

## Truthfulness boundary

The dialog only labels something as current, pending, or available when ARC owns the state or an exact plugin API exposes it. For plugins that only expose a command, the hub says `Открыть` or `Проверить` and never invents pending rewards, timers, invites, or event state.

## Smart query resolver

Natural-language parameter extraction is deterministic and bounded. It runs before fuzzy catalog search and recognizes only closed grammars:

- delete, move, or open an existing home by exact normalized home name;
- teleport to, call, message, invite, duel, or pay an exact proxy-visible player;
- open known sections such as dungeons.

The resolver receives the current home and network-player snapshots. It never treats an arbitrary token as a command argument. Destructive home deletion and payments always open confirmation screens. Ambiguous or missing entities fall back to ordinary search instead of guessing.

## Personal actions

Favorites and recent actions store only stable catalog action IDs, never command strings. A player may pin at most four actions; recent history retains at most six unique actions. Persistence uses a bounded JSON object in one Redis hash field per UUID with optimistic compare-and-set updates. If Redis is unavailable, the menu remains usable and explains that personalization is temporarily unavailable.

Only successfully dispatched catalog actions enter recent history. Dynamic destructive or monetary actions are not persisted.

## Context snapshots

The gateway builds a bounded snapshot on the Paper main thread:

- server, world, coordinates, and world kind;
- held material, display name, amount, and exact ItemsAdder namespaced ID when present;
- current Lands area name and owner role when exposed by the active Lands API;
- known feature availability.

Context screens derive buttons from this snapshot. Recipe lookup is offered only for an exact ItemsAdder ID. Selling remains an entry into the existing sell flow and never silently sells the held stack.

## Action center and goals

The action center combines concrete profile gaps with safe plugin entry points. It may say that a first home or first private area is missing, and it may offer to check quests, votes, battle pass, events, duels, or Lands requests. It must not claim those systems contain pending actions without an API snapshot.

The goal navigator maps six player intentions to small curated action sets: earn, build, explore, fight, develop, and play together. It is static routing over the available typed catalog, not a recommendation model.

## Diagnostics

Diagnostics are symptom-first. Each screen shows the facts ARC can prove (plugin available, homes loaded, current world, current private area, network player visibility) and then offers a short recovery route. No report is submitted and no external message is sent without a separate player confirmation flow.

## UI and copy

Use the established RusCrafting palette: gold for the primary step, cyan for navigation, green for safe state, orange for economy/activity, red only for danger or failure, muted blue-gray for secondary text. Buttons use regular weight; bold is reserved for the destructive confirmation phrase. One semantic statement per line, no fake table alignment, and no implementation names in player-facing text.

## Structure and lifecycle

`HelpCenterController` stays an assembly and navigation layer. Pure query, personalization, context, and goal planning move into separate files with unit tests. Async Redis and home loads are bound to the module lifecycle and return to the main thread before touching Bukkit UI.

## Acceptance

- Existing `/menu`, `/help`, homes, player actions, Lands, and inventory-return flows still work.
- Parameterized queries select only exact known entities and preserve confirmation gates.
- Favorites/recent actions are bounded, validated, and fail soft when Redis is absent.
- Held-item actions never infer a custom item ID.
- All new screens remain reachable without adding root clutter and can return to the root.
- Config defaults, bundled YAML, runtime mirror, tests, visual manifest, and deployable JAR remain consistent.
