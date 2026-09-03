# Lands settlement dialog

`/arc lands` opens ARC's native Paper dialog for the Lands settlement system.
The public `/privat` CMI alias is the normal player entry point.

The root screen is dynamic: it reads the viewer's current settlements from
LandsAPI and renders one action per land. Selecting a land opens its current
chunk, member, limit and balance summary plus controls for:

- the built-in Lands settings menu;
- current members, removal confirmation, online add candidates and typed nick input;
- claim, unclaim, land spawn, spawn placement and area management;
- owner-only rename and two-screen deletion.

The active servers use `general.cmd-land-argument: false`. Every domain action
therefore re-resolves the selected land by numeric ID, verifies that it remains
in the player's Lands set, calls `LandPlayer.setEditLand`, and only then runs the
canonical player command. No rendered name or stale screen is trusted. Lands
remains responsible for role permissions, limits, money, confirmations and the
actual mutation.

Visible text and limits live in `modules/lands-ui.yml`. Kotlin owns semantic
actions only; the config cannot execute arbitrary commands. The module starts
only when Lands is enabled and closes through ARC's normal module lifecycle.
