# Native cosmetics controls

`HelpCenterCosmeticsController` is a child of the existing HelpCenter flow.
Core owns native history, dispatch and the single footer. The production
gateways are created by `HelpCenterModule` and closed with its controller.

CosmeticsCore 1.3.13 uses its documented permissions, commands and PlaceholderAPI
contract. ARC has no paid-JAR dependency and does not inspect private classes.

- [Permissions](https://cosmeticscore.devs.beer/permissions): actor menu permission,
  per-ID visibility and wear entitlement.
- [Commands](https://cosmeticscore.devs.beer/commands): console
  `cosmetics <player> add|remove <id>`. This version has been verified without
  the optional `silent` argument.
- [PAPI](https://cosmeticscore.devs.beer/compatibility-with-other-plugins/compatible/placeholderapi):
  strict Boolean equipped state for every configured ID and category.
- [World configuration](https://cosmeticscore.devs.beer/settings): public world
  patterns. The installed public YAML also supplies names, types and category limits.

The catalog is captured at HelpCenter initialization/reload. When changing the
vendor's cosmetic definitions or limits, reload both owning configurations
together; a menu refresh rereads player state, not files. Unsupported definitions,
duplicate IDs or unresolved PAPI disable mutation. A category flag inconsistent
with the known equipped IDs also disables mutation. This does not claim discovery
of arbitrary additions through a private vendor API.

Before an add, ARC checks fresh actor entitlement, world and capacity. The three
item-backed wings additionally require their original item in storage inventory;
no item is consumed or permission granted. Removal remains possible after wear
entitlement is lost. The exact desired operation is captured by the card, so a
stale equip action cannot become a remove. Success requires a complete matching
post-action equipped set, including unchanged other cosmetics.
Selecting one of ARC's three item-backed wings first removes only the other
members of that same enum, checking each removal before adding the selection.
Freed wing slots count toward capacity; other body cosmetics remain equipped.
An unconfirmed partial operation is shown from actual state, without a promised rollback.

PlayerParticles remains authoritative for accessible presets, overlap rules,
commands, rendering and persistence. The native UI explains replacement versus
addition from the provider's current setting. Apply/reset confirmations capture
the provider snapshot; pending operations show a loading visit with the same
screen ID, and a late completion cannot reopen a dismissed view. Readback proves
the provider's current cache, not acknowledgement of its asynchronous database save.

Focused tests cover the gateway guards and actual Core native-history cycles.
`HelpCenterCosmeticsControllerTest` exports compiled screens under
`build/reports/cosmetics-dialog/` for offline rendering. These are source/layout
checks; live client appearance and provider behavior require separate acceptance.
