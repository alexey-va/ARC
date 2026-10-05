# Lands settlement dialogs

`/mm` → «Приваты», `/privat` and `/arc lands` open the same native Paper dialog.
The root lists the viewer's settlements and a separate «Приват под ногами» entry.
Players can inspect foreign land without selecting it for subsequent Lands commands.
Outside protected land the root explains why inspection is unavailable.

`LandsManagementMenus` provides these pages:

- Overview: head, viewer role, selected region, chunks and online member count.
  The balance and chunk limit are shown in member/admin management, not public inspection.
- Members: paginated list, online status, actual regional role, player cards,
  invitations, permitted role assignments and removal confirmation.
- Rules: the viewer's permissions; role-specific flags for building, containers,
  doors, trapdoors, mechanisms, animals, PvP and villagers; natural flags for
  TNT, fire spread and creature spawning.
- Territory: borders, home, home placement, current-chunk claim/unclaim, region
  selection and the existing main-block guide.
- Settings: rename, Lands' entry/title message, native advanced settings,
  leaving, ownership-transfer proposal and deletion.
- Admin: explicit mode for a foreign settlement, with search by land name or
  head/member nickname. It requires `lands.admin.command.edit`; each operation
  still requires its specific native authority.

Role and natural rules are scoped to the selected area. Public inspection starts
with the area under the viewer, while ordinary management starts with the main
area. Changing area never changes the land identity. The existing claim block's
radius and visual settings are retained; the hidden region-tool entry stays hidden.

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

Focused verification: `./gradlew test --tests 'ru.arc.landsui.*'
-PlandsJar=/path/to/Lands-8.6.6.jar`. A build/test pass does not establish live
activation or ordinary-client visual acceptance; report those separately.

2026-10-05: ARC 1.4.288 passes all 42 focused Lands UI tests against the exact
Lands 8.6.6 runtime JAR. Server activation and client acceptance are separate.
