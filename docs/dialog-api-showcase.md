# Native dialog API showcase

## Installed component gallery

Open **`/arc dialog preview`** or **`/arc dialogdemo design`**. The first root
button opens the opaque closed panel: a compact single column, a wide two-column
summary, three independently wrapped columns and a longer text block. The remaining sections contain all six text compositions (including
four progress palettes), 20 functional action styles plus Back, four ornamental
frames, the six textured tables T13–T18 and all 18 divider specimens. The original
API laboratory remains available through `/arc dialogdemo`, including its 18
table layouts.

Gallery navigation uses `PaperDialogRuntime` and shared history. Each section
keeps one stable screen ID while paging or displaying a sample-button response,
so Back returns to the visited root. The configured Escape preference is
preserved. Native gallery navigation remains undecorated; only the specimens
display the named fonts. Button samples never perform their pictured action.
The existing demo permission and configured aliases remain in force.

The gallery explicitly selects `arc:dialog_ui` on bitmap spans, independent of
the production-menu `presentation.font` setting. Captions use `minecraft:default`.
The filled panels call the shared `DialogTables.filledBody` constructor and use
`arc:dialog_ui_preview`. The v03 atlas contains 16×9px left/fill/right/divider tiles.
Its 194 row banks start at U+FA300 with stride 16; each provider has height 9 and
ascent `8 - 9 * row`. Top/middle/bottom tiles use offsets 0–3/4–7/8–11. Signed
ascents are accepted by the official 1.21.11 and 26.3 bitmap-provider codecs.
Negative binary spaces U+FA200–FA209 and positive spaces U+FA220–FA229 position
all background strips on the first text baseline, before any native words or
shadows. Subsequent native lines determine the widget's full height. Only the
background disables shadow; the native coin U+1F4B0 remains in the foreground.

The shared API supports 1–8 positively weighted columns, 64 logical rows including
an optional header, and 192 wrapped content lines. Cells wrap independently;
height follows the tallest cell in each row. Unsupported metrics, narrow widths
or larger content retain every cell in a readable unframed body. The four gallery
pages use body widths 300/508/508/508; they do not change the six existing tooltip
frame styles or production menus.

The body reserves 8px for native widget padding and 2px for wrapping headroom.
In the official clients, `StringSplitter.LineBreakFinder.accept` checks each
accumulated glyph advance before reading its following negative spacer. A panel
of width `P` reaches a temporary prefix of `P + 1`; its text budget is `P + 2`.
This prevents the earlier 506px-body/498px-panel right-cap wrap. The dated v02
red/green regression receipt is preserved separately from the v03 tests.
The white outline on a focused plain-message body belongs to Minecraft's
`FocusableTextWidget`; the server's plain-message API has no border/focus switch.

Focused tests cover all column counts, bounds and fallback, compact background
payloads, immediate client wrapping, background-first ordering, native coin/text
shadows, command aliases and actual shared-history navigation. The gallery's
component export is `build/reports/dialog-library/screens.json`. Source checks
and font-backed previews do not establish live pack loading or native appearance;
inspect all four pages in the actual client after matching ARC and pack activation.

## Original API laboratory

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
| Alignment | Measured left/center/right rows and 0/8/16/32px spacer samples |
| Tables | Eighteen numbered designs, including six custom tooltip frames T13–T18 |
| Dividers | Eighteen numbered designs: rules, dots, dashes, short accents, labels, segmented color and corners |
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
classes bundled inside the HuskHomes compile JAR. This explicit compile-only declaration does not add a runtime dependency;
existing transitive JAR contents are unchanged.

- Both handlers create `FocusableTextWidget`, whose constructor calls
  `setCentered(true)`. The earlier claim that item descriptions are left-aligned
  was incorrect: the superclass default is overridden by this constructor.
  Changing width does not change alignment. The alignment page now compares
  three measured layouts through `ru.arc.paper.menu.DialogTextLayout`, backed by
  `ru.arc.text.ComponentTextLayout` in arc-core. It subtracts the widget's 8px
  padding, wraps styled text and pads each line with the existing ItemsAdder
  U+F0F01..U+F0F0A space glyphs in `minecraft:default`. Resource-pack installation and native visual checks remain
  separate gates; packet inspection alone cannot prove pixel alignment.
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

### Table and divider design gallery

Open `/arc dialogdemo tables` or `/arc dialogdemo dividers`, also linked from
the root and alignment pages. Table pages hold three specimens each; divider
pages hold six. Page buttons address fixed routes (`tables-2` through `tables-6`,
`dividers-2` and `dividers-3`), and the native Back footer returns to the root.
Specimen IDs Т01–Т18 and Р01–Р18 are stable references for design feedback.
All displayed projects, counts and comparison plans are synthetic demonstration
data, not server limits, rewards or player statistics.

For production tooltip tables, use the [ready-made constructor](dialog-tables.md):
T13–T18 delegate to `DialogTables`; callers supply rows and choose a frame.

`DialogDesignGallery` builds every cell through `DialogTextLayout`, using the
same font snapshot as the alignment page. Numeric columns use explicit right
alignment; comparison values use centered cells. Rules use measured glyph
advances, including the asymmetric widths of left and right box corners.
Every composed line is padded to the native widget's 392px content width.

`DialogDesignGalleryTest` checks every line of all thirty-six specimens against the
shipped normal/bold glyph metrics, checks numeric-cell alignment, and exports
the actual production components to `build/reports/dialog-designs/content.json`.
Run `scripts/render-dialog-designs --ops-root /absolute/path/to/ruscrafting-ops`
for a font-backed preview through the canonical surface renderer.
`src/test/e2e/dialog-designs.spec.js` exercises gallery pages, page/back
commands and spacer delivery on real Paper in CI. Packet checks establish
content and navigation; only the actual client establishes final pixel appearance.

### Shared alignment adapter

Use `DialogTextLayout.body(component, TextAlignment.LEFT, width = 400)`
for a native body, or `layout(component, alignment, width)` for its typed result.
The adapter always applies measured alignment and falls back to the original
centered text only if metrics are unsupported. It does not consult Paper's pack
status, which does not reliably represent packs distributed by the proxy.
The client still needs the server font assets; client-side font overrides are
outside this API's knowledge. No additional spacer font is installed:
`PixelSpacing` selects existing ItemsAdder glyphs.

`fonts/dialog-font-metrics.json` is generated from the exact Minecraft 1.21.11
client plus the published server pack using ops'
`scripts/tools/build_dialog_font_metrics.py`. It includes source SHA-1, per-asset
SHA-256, provider diagnostics and metrics for the ordinary `uniform=false` client
option. Regenerate when font JSONs or referenced bitmaps change. Unknown font
providers fail the generator; unmeasured glyphs and non-text components fail the
layout explicitly. A different client font option requires a different snapshot.

`DialogTextLayoutTest` measures every output line of the Russian demo, including
bold and the ItemsAdder coin: 392px total, equal selected edges and preserved text.
This is a layout-model test, not a screenshot from a vanilla client.
The same alignment page also demonstrates standalone `PixelSpacing.padding`
with gaps of 0, 8, 16 and 32px between a common left marker and the sample word.

Local unit/package checks cover resource routes and bounded literal echo.
Native acceptance must open every page and exercise form submission, nested
list, confirmation and close/Back transitions. A packet observer establishes
payload and routing, not pixel alignment or physical cursor preservation.

Sources:
- https://docs.papermc.io/paper/dev/dialogs/
- https://jd.papermc.io/paper/1.21.11/io/papermc/paper/registry/data/dialog/type/DialogType.html
- https://jd.papermc.io/paper/1.21.11/io/papermc/paper/registry/data/dialog/input/DialogInput.html
- https://jd.papermc.io/paper/1.21.11/io/papermc/paper/registry/data/dialog/body/ItemDialogBody.Builder.html
- Minecraft 1.21.11 official client mappings: `DialogBodyHandlers.PlainMessageHandler`, `DialogBodyHandlers.ItemHandler`, `FocusableTextWidget`, `MultiLineTextWidget`.
