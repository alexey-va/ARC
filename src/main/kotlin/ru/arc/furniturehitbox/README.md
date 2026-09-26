# Furniture hitbox hint

`ItemInfoRuntime` shares its main-thread heartbeat with this independent hint.
Every online non-spectator player can hold sneak while aiming at IA furniture,
regardless of item-info preferences or world. Releasing sneak, changing target,
death, teleport, quit and module shutdown clear the viewer's frame.

`ItemsAdderFurnitureHitboxSource` supports the deployed ItemsAdder 4.0.18.
Physical furniture blocks resolve through its public block API. Display furniture
uses a read-only, cached-reflection adapter to its existing per-player target and
Interaction. It never calls IA's mutating target resolver or creates Interaction
entities. If the visible model extends beyond the click area, bounded nearby
loaded-root queries use the existing furniture model profiles to find the prop;
`ci.j(Location)` plus an exact root UUID check resolves the native hit geometry.
The suggested box then follows IA's own float width=max(X,Z), float height,
center-X/min-Y/center-Z rule, not the visible model dimensions. Current native
Interaction bounds take precedence once IA selects that root. Both paths intersect
that box with the native ray-selection bounds: IA validates the click again, so
the square Interaction excess must never be presented as a working click area. Opaque blocks
occlude selection. Profile queries also work outside the gallery world.

The exact-version adapter binds once, re-reads live manager instances after IA
reloads, waits for the nullable furniture-manager field during asynchronous IA
data loading, and disables only this optional hint with one warning on incompatibility.
The shared heartbeat reports readiness once the native managers actually exist;
the throwing d.v() accessor must not be used as a startup readiness check.
An IA upgrade needs a new verified adapter; never silently reuse obfuscated names.
No click/break events, protections, barriers, inventories or ownership are changed.

`PacketFurnitureHitboxOutline` uses Core 2.7.13 `PaperPacketDisplays`: twelve thin,
glowing block-display rods visible only to their owner, with no native entities.
Identical frames reuse their handles; partial failures and shutdown release them.

Focused checks:

```sh
./gradlew test --tests 'ru.arc.furniturehitbox.*' \
  --tests ru.arc.hooks.economyshop.FurnitureGalleryTargetTest \
  --tests ru.arc.hooks.economyshop.FurnitureGalleryInteractionRuntimeTest
```

An additional licensed-JAR binding check is enabled by `ITEMSADDER_4_0_18_JAR`.
Its test runtime also needs ProtocolLib 5.4.0 and JCTools 4.0.5 (server-provided
IA dependencies); add them through a temporary Gradle init script's
`testRuntimeOnly` configuration. Do not commit the proprietary JAR or add those
libraries to ARC's shaded payload. The check validates real reflective member
signatures without initializing a server. Unit/contract tests do not establish
native-client rendering or visibility through opaque models.
