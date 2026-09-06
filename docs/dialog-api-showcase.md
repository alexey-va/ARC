# Native dialog API showcase

Open `/arc dialogdemo` on the runtime carrying this ARC build. This is an
isolated, public, stateless UI demonstration; it neither grants displayed items
nor changes player settings, permissions, world state or balances. Presentation
is in `modules/dialog-demo.yml`; `/arc reload` refreshes the text. Commands accept
only fixed internal page routes. Submitted strings are bounded and displayed as
literal components, never evaluated as commands or MiniMessage.

## Coverage matrix

| Surface | Live examples |
| --- | --- |
| Dialog types | `notice` (custom and default action), `confirmation`, `multi_action`, `dialog_list`, `server_links` |
| Body types | `plain_message`; `item` with and without description |
| Alignment | Identical paragraphs in centered plain message and left-aligned item description |
| Item options | Stack count, damage bar, tooltip on/off, decorations on/off, 16px and 48px allocated slots |
| Text input | Initial value, max length, width, hidden label, multiline explicit height and automatic height |
| Boolean input | Both initial states; custom `on`/`off` template values |
| Single option | Display labels, stable option IDs, initial selection, visible/hidden label |
| Number range | Negative/fractional continuous range, initial value, step, label format, width |
| Action button | Label, tooltip, width, nullable action |
| Static actions | Run fixed demo command, suggest command, copy clipboard, URL, inline show-dialog, custom payload, book-page limitation |
| Dynamic actions | Command template; custom key plus additions merged with form inputs; typed response view |
| Paper callback | Player-bound callback, one use, 60-second expiry, literal response display |
| Base fields | Title/external title, body/input ordering, Escape true/false, pause true/false, all three after-action modes |
| Layout | 1/2/3 columns, mixed button widths, text wrapping, scrolling, native fixed footer |
| Text components | RGB, gradient, decorations, paragraphs, vanilla fonts, pack glyph, translated component, keybind |
| Interaction | Text/item/entity hover, text click, insertion (client-context limitation) |
| Registry | Inline `RegistrySet.valueSet(RegistryKey.DIALOG, ...)`, external titles distinct from screen titles |

The `server_links` specimen uses the server's real links; it does not replace
network settings to fabricate a populated example. Registry bootstrap/reload,
dialog tags and the built-in `QUICK_ACTIONS`/`CUSTOM_OPTIONS` entries are content
registration mechanisms rather than additional UI widgets. The API reference
below documents them; this demo uses inline values without changing registries.

## Client boundaries and intentional exceptions

Verified against the official Minecraft 1.21.11 client and Paper's exact-version
API. The repository compiles against its existing Paper 26.1.2 dependency; the
showcase uses only the 1.21.11 dialog API surface. An explicit compile-only
Adventure dependency gives the server API precedence over the older Adventure
classes bundled inside the HuskHomes compile JAR. It adds no bundled runtime
Adventure copy.

- `PlainMessageHandler` calls `setCentered(true)`; `ItemHandler` leaves the
  description widget left-aligned. Changing width does not change alignment.
- Item slot size reserves space; it does not scale the item model. Item bodies
  are display elements, not inventory slots or item-transfer actions.
- Ordinary specimen pages use Back in the action grid and a Close footer, so
  Escape closes the complete flow. Notice/list/lifecycle cases intentionally
  exercise native Back footers and explain their behavior.
- The WAIT example intentionally sends no response, exposing the client's
  waiting screen and its delayed Back control. No delayed task reopens a menu
  after the player exits.
- `pause` only pauses an integrated single-player server; it cannot pause this
  multiplayer server. `pause=true` is demonstrated with `after_action=CLOSE`.
- URL confirmation, clipboard, insertion and suggested-command behavior depend
  on the client context/settings. No browser is opened automatically.
- `change_page` is a book action, not dialog pagination. `open_file` is blocked
  for server-provided content and is documented, not executed. Arbitrary images,
  free positioning/CSS, text-input passwords, inventory drag/drop and mouse
  position control are not provided by this API.

The production `PaperDialogRuntime` currently represents only multi-action
screens with plain messages and single-line text inputs. These specimens use
native builders to expose the rest of the API without replacing or copying that
runtime. Fixed demo custom events only echo bounded caller-provided values to
the caller. They do not authorize a gameplay operation or retain a session.
Paper's callback example is deliberately short-lived and one-use.

## Verification

Local unit/package checks cover resource routes and bounded literal echo.
Native acceptance must open every page and exercise form submission, nested
list, confirmation and close/Back transitions. A packet observer establishes
payload and routing, not pixel alignment or physical cursor preservation.

Sources:
- https://docs.papermc.io/paper/dev/dialogs/
- https://jd.papermc.io/paper/1.21.11/io/papermc/paper/registry/data/dialog/type/DialogType.html
- https://jd.papermc.io/paper/1.21.11/io/papermc/paper/registry/data/dialog/input/DialogInput.html
- https://jd.papermc.io/paper/1.21.11/io/papermc/paper/registry/data/dialog/body/ItemDialogBody.Builder.html
- Minecraft 1.21.11 official client mappings: `DialogBodyHandlers.PlainMessageHandler`, `DialogBodyHandlers.ItemHandler`, `MultiLineTextWidget`.
