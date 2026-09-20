# Origin restaurant life

`OriginDiningModule` owns orders, payment, player service, guest dishes and
waiter routes. `OriginDiningLife` and `OriginDiningConversations` use the shared
`OriginSceneExecution`, `OriginSceneCoordinator` and `OriginSceneResources`.
Neither introduces a Denizen animation owner.

## Behaviour

- A delivery timeout is not arrival: retry once, then cancel without charging.
- Return-home callbacks have operation tokens. New delivery ownership removes
  obsolete returns. Ambient rest starts after physical home arrival.
- Existing immediate personal waiter glow and the 120-second post-service
  player cooldown are unchanged.
- A seated guest takes three bites, with native hand/use-item gestures and
  nearby sounds. The full dish stays visually unchanged until an empty plate
  replaces it; intermediate visual stages are deliberately absent.
- Waiters collect empty plates before refilling. The collected plate stays in
  the waiter's hand until home. Invalid meal entities re-enter the service queue.
- Empty/missing meals enter an oldest-first queue every second; a missing meal
  takes priority over collecting another plate. Rest is 3–6 seconds, only after
  home arrival. Guests take three bites 20–32.5 seconds apart.
- Each venue has its own conversation slot. Pairs are selected oldest-first;
  variants round-robin. Text reading time scales with phrase length. Toasts use
  one mug; other exchanges use nods. Service interrupts the guest's conversation.
- Mateo takes a mug, pours from a tilted bottle, presents a foamy drink and
  wipes the counter. Every third order has two mugs.

## Ownership and budgets

Audience refresh is once per second, only in the scene world. Ambient eating,
bar work and conversations stop without nearby viewers. Particle/sound delivery
is restricted to viewers within 14 blocks. No MiniMessage parsing was added to
a per-tick path. Existing meal entities are reused; at most three short-lived
bar props are visible together. There are at most four simultaneous life cycles,
and at most four native props per small resource lease.

Scene cleanup restores exact equipment, active-use state, facing and LookClose.
LookClose is disabled only while the scene owns facing. Borrowed meal displays
settle to their original transform on interruption; only the meal owner removes
them. All delayed service callbacks check the active route token.

Config is in `modules/origin-dining.yml`: `life.*`, `conversation.*`, and the
existing `timing.*`, `navigation.*`, `scene.*`. IA models must be published before
activating a config that references their IDs. Missing models are logged once
per service lifetime; failed empty-plate replacement never marks a full visible
dish as empty.

Diagnostics: `ORIGIN_DINING` records delivery/retry/home/collection transitions;
`ORIGIN_DINING_LIFE` records completed cycles, empty plates and failures. A started
route is not proof of delivery. Confirm `AMBIENT_ROUTE_FINISHED reason=cleared`,
`WAITER_RELEASED`, and a later `AMBIENT_MEAL_SERVED` for a complete guest cycle.

Focused tests cover timing/fairness, cancellation, token ownership, layout,
item-resource cleanup, geometry endpoints and the fixed bottle-mouth pivot.
Native live readback and local textured model previews are distinct from
in-game visual acceptance.

## Table contact regression

The old anchor (table top +0.1) and model clearance (+0.04) stacked into a
0.14-block hover. Native readback on 2026-09-20 confirmed the fish display at
(-15.5,71.1,40.5), translation Y=0.12125, above a log whose visible top is Y=71.
Use the tabletop itself as the anchor: `GROUND`, scale .65, support translation
Y=.24375 for egg/steak and .08125 for fish, including their empty plates.
The six exact textured models have contact residual <1e-4 at Y=71.
`diningSurfaceAnchor` additionally resolves loaded vanilla collision surfaces
within .25 of the authored anchor (including carpet), excluding invisible
barriers; furniture without visible native support retains its authored height.

The brewery has six candidate stair chairs and five ambient guests. Reserving
three candidates previously removed guests 419/420 and their food table from
the active scene. Reserve one candidate (other player tables remain outside
this zone); log insufficient capacity and rejected passenger attachment rather
than reporting successful seating. Reset waiter home yaw after route arrival.
