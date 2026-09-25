# Chat glyph authorization

`ChatGlyphPolicy` validates player-authored Unicode and image aliases against
live permission callbacks. `ItemsAdderGlyphRegistry` is the isolated metadata
adapter for the deployed ItemsAdder 4.0.18 API; it uses raw registry values,
not `getString()` values with generated offsets. Permission strings are already
effective `ia.user.image.use.*` nodes, not configuration suffixes.

`ChatGlyphProtection` owns reloads and lifecycle. Spawn and survival use their
local ItemsAdder registry. Hooks initialize before ConfigModule assigns
`ARC.serverName`; resolve the identity from `ArcRedisConfig` during startup.
Spawn publishes metadata through
`ChatGlyphNetworkCatalog` using core's leased Redis presence directory; parkour
and opted-in isolated backends read that catalog without an ItemsAdder dependency. An absent/expired catalog
fails closed. Permissions are checked for every message, never replicated.

`ItemsAdderChatGuard` validates immutable signed player text before ARC's
title/NPC handling and checks every command before aliases can relay it.
Operators retain the native exemption. Server-added font offsets and badges
are outside player input. Unknown private-use scalars and technical glyphs
cannot be sent by non-operators.

An isolated backend opts in with `glyph-protection.isolated-enabled: true` in
`modules/chat-mode.yml` and a working `modules/redis.yml` connection. Restart
is required. This adds only `Redis` and `ChatGlyphProtection` to the four local
operations modules; network gameplay, synchronization and the hooks registry
remain absent. `IsolatedChatGlyphModule` owns the same guard and a minimal
Paper chat listener, without registering the full ARC chat feature set.

Runtime integration requires CMI Paper chat and `ChatRoom`, `Staff`, `Shout`
priorities `LOW`, after ARC `LOWEST`. CMI 9.8.10.0 registers those handlers with
`ignoreCancelled=true`; its command alias listener also returns on cancellation.
Production has no DiscordSRV backend plugin (bridges belong to ProxyARC).
If adding DiscordSRV, account for CMI's switch to legacy staff chat events.
The runtime configuration and rollout procedure live in the ops repository's
`docs/knowledge/itemsadder.md`.

Focused checks:

```sh
./gradlew test --tests 'ru.arc.chat.*' --tests 'ru.arc.listeners.ChatListenerTest' --tests 'ru.arc.IsolatedRuntimeProfileTest'
```
