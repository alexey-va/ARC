# Lands settlement dialogs

`/mm` → «Приваты», `/privat` and `/arc lands` open the same native Paper dialog.
The first complete root row holds «Приват под ногами» and «Выдать блок привата»
when a nearby land exists. Creation and guidance precede the settlement list.
Settlement buttons show the literal land name followed by the owner in parentheses.
The three-row summary fits its contents in a compact filled panel (up to 320
logical pixels). The instruction block has an opaque filled background at 400
pixels; a thin shared-library rule without decorative end caps separates the two
groups using the native body spacing, without extra blank bodies.
Opening any settlement card preserves the selected land for subsequent Lands commands;
The explicit «Выбрать для расширения» action changes that selection.
Outside protected land the current-land inspection action is omitted.

Holding an ARC claim block or a previously issued native Lands block inside a land you own or belong to shows a
personal six-button world menu: add a player, members, rules, territory, settings
and overview. Left and right click select the actual visible control without
Shift; clicks outside the panel keep the existing reusable claim-block behavior.
The menu belongs to `ClaimBlockTool`/`LandsUiModule`, independently of onboarding
and its world list. The old onboarding label is hidden while this menu is visible.
Names are literal components. Each click re-resolves the current land and membership,
then the existing dialog gateway rechecks membership and each native permission;
opening a page does not change Lands' selected edit land or mutate membership.
Text is configured under `text.panel-*` in `modules/lands-ui.yml`.
Shift+F and the global menu’s «Приват под ногами» entry open the same current-land
screen, also used by the panel’s overview action. Members see the same canonical
card as the settlement-list entry for the same land and area, with the same body
and permission-filtered button order. Direct management shortcuts retain native
member permissions. Neither entry selects a different Lands edit land or grants
administrative access. Foreign-land inspection remains read-only.
The close panel uses Builder’s button size, spacing, title scale and hover style.
While aiming at it, the onboarding boundary guide loses its glow; its geometry
remains visible. The glow restores on looking away.

`ClaimLandMenu` owns only the fixed world layout and per-viewer visuals;
`PaperPacketDisplays` owns shared transport, budget, received chunks and cleanup.
Head turns and crouching in place leave the menu stationary for aiming. Movement
uses short interpolation with obstacle checks, bounded upward/sideways detours
and closer placement down to 0.35 blocks when necessary; controls behind blocks
or beyond reach cannot be clicked. Expected blocked placements retry on the next
tick; unexpected renderer failures back off briefly and recover without reconnecting.
Item removal, leaving the land, teleport, world change, death, quit, reload and
shutdown remove the panel. Visual/native-client acceptance is separate from
focused geometry and dialog-routing tests.

`LandsManagementMenus` provides these pages:

- Overview: head, viewer role, selected region, chunks and online member count.
  The balance and chunk limit are shown in member/admin management, not public inspection.
  Buttons follow this order: «Участники», «Добавить игрока», «Разрешения», «Роли»,
  «Территория», «Спавн поселения», «Управление», «Выдать блок привата», explicit admin entry.
  Actions without the required permission are omitted. In the two-column layout,
  «Добавить игрока» sits to the right of «Участники» when invitations are allowed.
- Members: paginated list, online status, actual regional role, player cards,
  invitations, permitted role assignments and removal confirmation.
  Invite suggestions include the network online-player list and paginate all
  eligible names; `limits.max-listed-players` is the page size (12 by default).
  Self, existing area members and duplicate UUIDs are filtered before paging.
  Manual nickname entry remains available on every page.
- Rules: the viewer's permissions; role-specific flags for building, containers,
  doors, trapdoors, mechanisms, animals and villagers; natural flags for
  TNT, fire spread and creature spawning.
- Territory: home placement, current-chunk claim/unclaim and the existing
  claim-block expansion guide. Home teleport is a separate overview action; this page has no
  teleport or additional-region selector.
- Settings: rename, Lands' entry/title message,
  leaving, ownership-transfer proposal and deletion.
- Admin: explicit mode for a settlement, with search by land name or
  head/member nickname. It requires `lands.admin.command.edit`; each operation
  still requires its specific native authority.

Role and natural rules are scoped to the resolved area. Current-land entries
preserve the actual area under the viewer when resolving member access; the
settlement-list entry starts with the main area. Losing membership makes the
member lookup fail rather than falling back to broader authority. The explicit
admin button remains available to authorized viewers; simply opening a card never
enters admin mode.

«Выдать блок привата» is the same action on the root and overview, including
public inspection. Both use the existing gateway to issue the ARC tool and
report duplicate-item, full-inventory and unavailable results. Issuance has no
cooldown; opening a page never issues an item. The dialogs no longer offer
«Показать границы».
Native boundary rendering and the held-block grid/wall guide remain unchanged.

The menu, `/arc claimblock give` and kits using that command share `ClaimBlockItem`.
New grass-block tools carry only `arc:claim_block`, `arc:claim_block_radius` and
`arc:claim_block_owner` metadata, so Lands does not recognize them as consumable
claim items. `ClaimBlockIdentity` still accepts the previously supported native
`lands:type=CLAIM_BLOCK` plus radius format. Claims retain the public Lands
`Selection.claim` path and its permission and limit checks. Item name and lore
use `text.claim-block-*` in `modules/lands-ui.yml`.

The root and settlement card no longer expose claim-radius or guide-display
settings. Their screen handlers (`lands.claim-radius`, `lands.claim-display`) and
the additional-region selector (`lands.areas`) have been removed. Existing text
keys remain compatible with stored configurations, while claim-block radius,
guide preferences, current-chunk highlighting and real claim boundaries keep
their existing mechanics. The hidden region-tool entry stays hidden.

Targets use Lands ULIDs, not rendered names. The gateway re-resolves land, area,
role, membership and permissions for every mutation. Current-location inspection
expires when the viewer leaves the land. Natural-flag clicks compare the displayed state
before toggling; stale clicks refresh the screen instead. Unclaim confirmation
binds the exact world/chunk, rejects an active selection and checks the location
again. Deletion and transfer bind the owner snapshot and require typed land-name
confirmation in the dialog. Lands ownership transfer remains an offer that the
recipient must accept.

The integration is checked against the exact deployed Lands 8.9.6 JAR. Native player
commands preserve Lands' own limits, economy and mutation checks; accepting a
command is not reported as completed mutation. Role ACTION flags are edited in ARC dialogs. `LandsRoleEditing` binds the audited
8.9.6 native allow-toggle-flags, holder, owner/tenant/priority and event checks
once at startup. Unknown versions or mismatched bindings leave only role editing
read-only with an actionable warning; they cannot bypass native policy.
Natural-flag edits preserve native actor permission, toggle permission, display,
subarea/war restrictions and persistence rules.
No console command or administrator impersonation is used.

Visible text lives in `modules/lands-ui.yml`. Player/land/role names are inserted
as plain Adventure components, not parsed as MiniMessage. Kotlin owns typed
actions; configuration cannot execute arbitrary commands. The module starts
only when Lands is enabled and closes through ARC's normal module lifecycle.

Focused menu verification: `./gradlew :test --tests ru.arc.landsui.ClaimLandMenuTest
--tests ru.arc.landsui.ClaimBlockMenuInputTest --tests ru.arc.landsui.LandsUiControllerTest
--tests ru.arc.landsui.LandsUiInspectionTest --tests ru.arc.landsui.LandsUiConfigTest
--tests ru.arc.landsui.LandsManagementMenusTest --tests ru.arc.landsui.LandsUiManagementGatewayTest
-PlandsJar=/path/to/Lands-8.9.6.jar`. A build/test pass does not establish live
activation or ordinary-client visual acceptance; report those separately.


## Command entrypoints and guidance (2026-10-28)

`ClaimMenuCommandListener` uses `LandsUiCommandRoute` to route `/trust`, `/untrust`,
`/lands`, `/land`, their `lands:` namespace, and incomplete member actions into
ARC dialogs. Menu paths such as `/lands member menu`, `/lands area AREA menu`,
`/lands menu land roles`, `options`, `claims`, and `areas` reuse the same screens.
`/lands invites` and `/lands menu personal invites` use the native dialog inbox. A nonempty inbox also appears in the root utility
controls. Responses revalidate the invitation snapshot and use the native command
preflight for bans, limits and trust-owner policy; a dispatched request is not
reported as completed membership or ownership change.
Explicit action arguments remain Lands commands; unrelated namespaces, storage
inventories, administrative commands and unrelated Lands modules are preserved.

`LandsUiManagementService.commandContext` resolves the configured edit-by-location
policy or the existing edit land, then the exact requested area. Opening a page
never selects another land. A missing/revoked target returns to the root; it cannot
fall back to another settlement. The overview's explicit selection button is the
only new action that changes which settlement is used for expansion.

The root and its guides explain the next menu action instead of listing a command
manual. Regular announcement defaults and HelpCenter guidance use verified menu
entrypoints. Runtime copies preserve per-node presentation and the single Spawn
announcement producer; Survival continues receiving the network rotation.

Source checks use the exact unmodified Lands 8.9.6 runtime JAR (SHA-256
`b0057ed700bda1ed190beb4b7d8e38ead8f2c4221999d8021f1445947ad273ca`).
Focused checks include `LandsUiCommandRouteTest`, `LandsUiCommandMenusTest`,
`LandsUiInvitationsTest`, the existing Lands UI tests, `DenizenReplacementSubCommandsTest`,
`HelpCenterConfigTest` and `AnnounceManagerReloadTest`. Source tests, config/JAR
delivery, JVM activation and observation in a real Minecraft client remain
separate evidence.


Economic scope: this change preserves native prices, claim limits, membership
limits, fees and rewards. Direct before/after delta is zero Vault, zero premium
tokens, zero EliteMobs crystals and zero new item/XP rewards. Faster access can
change use of existing actions; that usage effect was not measured. The unrelated
EconomyShopGUI SELL-to-contract transition is unchanged. The book-application
help fallback now describes the existing `EliteEnchantingController.applyDirect`
path; no payment logic was changed.
