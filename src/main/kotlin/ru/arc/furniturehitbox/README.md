# Furniture hitbox hint

`ItemInfoRuntime` shares its main-thread heartbeat with this independent hint.
Every online non-spectator player can hold sneak while aiming at IA furniture,
regardless of item-info preferences or world. Releasing sneak, changing target,
death, teleport, quit and module shutdown clear the viewer's frame.

`ItemsAdderFurnitureHitboxSource` reads the root entity's native bounding box,
confirmed by the public CustomFurniture API and furniture PDC identity. Nearby
queries only inspect loaded ItemDisplay, ArmorStand and ItemFrame roots. The
nearest native or visible-model surface wins; configured model profiles also
find props whose click box is smaller or offset. Profiles help find a root but never
replace its click box. Barrier hits use the public block API to find their root,
require the barrier center inside or on the boundary of that root's box, and show
the same whole box. As in IA, containment uses the block box shrunk by 0.5 rather
than the center-vector overload, which excludes maximum faces.
Sweeping between a model, native root and its barrier cells cannot shrink the
frame to an individual collision block.
Opaque blocks occlude selection, with IA's enclosed-support/owned-barrier rules.

The exact deployed ItemsAdder 4.0.18 artifact establishes this semantic path:
CustomFurniture -> furniture behaviour js -> native entity box (afz.a);
arm-swing listener kv -> jl.ao/ap -> entity ray and Entity.getBoundingBox.
The native furniture reach is five blocks. This implementation calls only public
Bukkit and CustomFurniture APIs; it does not invoke jl.ap, which schedules work,
or access the optional co/br/ci crop subsystem. Furniture has no separate
Interaction-box intersection. A real-artifact regression checks the relationship
from the public API, not just the signatures of plausible internal classes.

Readiness is reported once the public furniture registry is loaded. No
click/break events, protections, barriers, inventories or ownership are changed.

`PacketFurnitureHitboxOutline` uses Core 2.7.18 `PaperPacketDisplays`: twelve thin,
glowing block-display rods visible only to their owner, with no native entities.
Identical frames reuse their handles; partial failures and shutdown release them.

Focused checks:

```sh
./gradlew test --tests 'ru.arc.furniturehitbox.*' \
  --tests ru.arc.hooks.economyshop.FurnitureGalleryTargetTest \
  --tests ru.arc.hooks.economyshop.FurnitureGalleryInteractionRuntimeTest
```

Set `ITEMSADDER_4_0_18_JAR` to enable the licensed-artifact semantic path check.
It uses the JDK's javap and needs no IA transitive test-runtime dependencies.
Do not commit that proprietary JAR. Unit/artifact tests do not establish
native-client rendering or visibility through opaque models.
