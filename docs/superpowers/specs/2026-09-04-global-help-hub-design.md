# Global Help Hub Design

## Goal

Turn ARC `/help` into the compact, task-oriented entry point for ordinary
RusCrafting players without duplicating every plugin command on the root
screen.

## Root information architecture

The root has ten two-column destinations, ordered by player intent:

1. `Сейчас` and `Поиск`
2. `Перемещения` and `Поселения`
3. `Активности` and `Развитие`
4. `Торговля` and `Игроки`
5. `Технологии` and `Настройки`

`Про меня` becomes the profile block inside `Сейчас`. `С чего начать` becomes
a recommendation shown when the profile lacks a home or settlement. The old
zMenu main-menu route is not promoted on the root; the supported destinations
it owned are migrated into focused ARC pages.

## Dynamic `Сейчас`

ARC loads the existing profile snapshot and derives a bounded ordered list of
next actions. Missing first home and first settlement come first. Rank goals,
BattlePass, and events follow only when their owning command/plugin is
available. A partial profile failure still exposes safe static destinations.

Recommendations are pure planner output so their priority and boundedness are
covered without coupling tests to Paper dialogs.

## Catalog pages

`Активности` contains events, duels, BattlePass, giveaways, dungeons and shared
worksites. `Технологии` contains Slimefun, custom items, enchantments, building
tools and mounts. `Настройки` contains explicit chat, land-border and trail
controls; ambiguous toggle-only labels are forbidden. Existing trade and
progress pages remain focused.

Every catalog action declares an optional required plugin and permission. The
controller filters unavailable actions before rendering and before search, so
the UI never advertises a route the current node cannot execute.

## Player actions

`Игроки` reads ARC's proxy-wide synchronized player registry and lists at most
the configured limit across all backend servers, excluding the viewer, with a
bounded case-insensitive name search. Each entry retains its backend server for
the detail view. If the registry is temporarily unavailable before its first
snapshot, the current backend's Bukkit list is the bounded fallback. Selecting
a player opens actions for teleport requests, private message, payment, duel,
and a settlement invite flow.

Free-form player names, messages and amounts are normalized through typed
command builders. Messages reject controls and are limited to 128 characters.
Payments use a positive decimal amount with no more than two fraction digits
and require a confirmation dialog before dispatch. Settlement invites require
an explicit settlement selection and re-resolve it immediately before command
execution.

## Situational help

The search page includes `Что случилось?`. Its focused page covers being stuck,
returning to the prior location, returning to spawn, finding homes, settlement
problems, rank requirements and voting rewards. Each row gives one explanation
and one exact action; it does not pretend to be a support ticket system.

## Visual contract

The native dialog uses readable off-white body text, amber for primary or
attention actions, cyan for travel and communication, green only for current
or successful state, and red only for destructive or dangerous actions. Button
labels are not bold. Screen titles may be bold; body text uses short sections
and real line breaks, never padded alignment or multiple bullets on one line.

The visual manifest renders root, loading, populated, empty, error,
confirmation and hover variants for every new page. Production deployment
requires a fresh complete visual dump and player smoke; Git publication alone
does not activate the feature.

## Compatibility and failure behavior

The bundled YAML remains the complete portable default and merge-forwards new
keys into existing installations. Dynamic lists are bounded by configuration.
Unknown or unavailable integrations are omitted rather than failing the whole
menu. All command dispatch remains player-owned and permission-checked by the
owning plugin.
