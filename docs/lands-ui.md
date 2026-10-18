# Lands settlement dialogs

`/mm` → «Приваты», `/privat` and `/arc lands` open the same native Paper dialog.
The root lists the viewer's settlements and a separate «Приват под ногами» entry.
Opening any settlement card preserves the selected land for subsequent Lands commands;
`/lands edit НАЗВАНИЕ` remains the explicit selection action.
Outside protected land the root explains why inspection is unavailable.

Holding a native Lands claim block while standing inside a land you own or belong to shows a
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
  «Территория», «Телепорт домой», «Управление», «Показать границы», explicit admin entry.
  Actions without the required permission are omitted. In the two-column layout,
  «Добавить игрока» sits to the right of «Участники» when invitations are allowed.
- Members: paginated list, online status, actual regional role, player cards,
  invitations, permitted role assignments and removal confirmation.
- Rules: the viewer's permissions; role-specific flags for building, containers,
  doors, trapdoors, mechanisms, animals, PvP and villagers; natural flags for
  TNT, fire spread and creature spawning.
- Territory: borders, home placement, current-chunk claim/unclaim and the existing
  main-block guide. Home teleport is a separate overview action; this page has no
  teleport or additional-region selector.
- Settings: rename, Lands' entry/title message, native advanced settings,
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

The integration is verified against the deployed Lands 8.6.6 JAR. Native player
commands preserve Lands' own limits, economy and mutation checks; accepting a
command is not reported as completed mutation. Role flags are read-only in ARC; the native settings button opens Lands for
edits, preserving its configured role allow-edit lists and system-role policy.
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
-PlandsJar=/path/to/Lands-8.6.6.jar`. A build/test pass does not establish live
activation or ordinary-client visual acceptance; report those separately.
