# Join and quit message dialogs

`/arc joinmessage` and `/arc quitmessage` open native Paper dialogs. Existing
command aliases and command permission overrides are retained. The main menu also
links directly to the join catalog through «Настройки → Сообщения при входе»;
this route retains the catalog permission check and native dialog history. The catalog is
still published by ProxyARC through `arc.join_message_catalog`.

## Player flow

- The catalog uses two columns and twelve phrases per page. A 600 GUI-unit grid uses
  two 299-unit buttons and the native 2-unit gutter.
  Click a phrase to enable/disable it. `✔` and green text identify enabled
  phrases; `[Недоступно]` identifies unavailable choices. The full phrase is also
  present in its tooltip. Extremely long operator-authored phrases can exceed a
  button at some client GUI scales; their tooltips preserve the complete text.
- Multiple selections remain supported. ProxyARC randomly picks one enabled
  phrase for each join or quit. With no selection it uses the network default.
- Saved personal phrases appear first in the same paginated catalog with a `★`
  marker and a tooltip identifying them. Clicking toggles them in place;
  editing and deleting remain in `Мои фразы`. Personal toggles recheck the
  custom permission, and saved phrases remain visible if that permission is lost.
- Next/previous are always present at the bottom and wrap between the first
  and last page. On a single page both keep that page. Personal settings and
  the kind switch precede pagination. The switch control opens the other kind.
- `Мои фразы` opens a separate personal library. Enter a full MiniMessage
  template, inspect the styled preview, then save and enable it. Use exactly one `%player_name%`
  wherever the name belongs. The template owns the prefix as well as nickname
  and body formatting; no network dot is added to new templates.
- Open a saved phrase to edit, disable, enable or delete it. Editing preloads the
  current template, including the published network prefix for old suffix-only
  phrases, replaces the same slot and preserves its enabled state. The dot and
  its color can be replaced or removed entirely.
  A full ten-phrase library remains editable. Duplicate targets and a deleted
  source are rejected without deleting either phrase. Formatting help preserves
  the unsaved draft, and preview Back returns to that draft.
- Phrase buttons use white `#ffffff` for unselected and green `#9bd48d` for enabled
  states. Only `○` / `✔` and color change on toggle; no state words or extra
  separators are inserted. Tooltips explain the available action. Utility colors encode categories: pagination is blue `#92bed8`, the
  personal library/editor is violet `#c4a7e7`, and switching join/quit is gold
  `#e5ba73`. Back stays neutral; deletion uses red. Personal template
  colors appear in tooltips and previews, while button labels retain state colors.
  Dialog actions
  use `›` for child screens and `‹ Назад` for returns. Existing operator text is
  preserved; values equal to the previous bundled defaults migrate automatically.

## Permissions and configuration

| Permission | Capability | Default |
| --- | --- | --- |
| `arc.join.message.gui` | Select ready-made join/quit phrases | op (unchanged) |
| `arc.join.message.custom` | Also create/manage personal phrases | false |

Custom editing requires both the applicable command permission and the custom
permission. Each callback rechecks permission. Catalog-specific permissions are
also retained and checked against the current catalog before enabling a phrase;
an already-enabled phrase can be disabled after its catalog permission is lost.

`modules/join-message-dialog.yml` owns all visible text, button width, page size,
and whether unavailable catalog entries remain visible. The old inventory
presentation in `modules/misc.yml` and `guis/menus.yml` is no longer used by these
commands. Grid width is bounded to 300–1024 and page size to 2–20.
Short last pages reserve empty cells so personal settings/kind switching and
pagination remain two separate complete rows. Clicking an empty cell only
refreshes the same page; it never changes a selection.

## Storage and broadcast contract

The existing Redis record/channel remain `arc.join_messages` /
`arc.join_messages_update`. `joinMessages` and `leaveMessages` still contain
selected template strings. New `customJoinMessages` and `customLeaveMessages`
contain saved legacy suffixes or full MiniMessage templates; missing or null
fields mean an empty library.
A legacy custom selection key is `%player_name% ` followed by its suffix.
A full template contains `%player_name%` and its selection key is `<reset>`
followed by the template. That leading reset also tells ProxyARC to omit the
family prefix. The shared selection strings remain the existing wire format.
The catalog also publishes nullable `joinPrefix` / `leavePrefix` fields so ARC
can expose the actual legacy prefix in the editor and preview. Missing fields
from older publishers use the previous green/red dot defaults; an explicitly
empty prefix remains empty. Prefix-only changes update the catalog revision.

Each library has at most ten phrases. Legacy suffixes keep their 120 UTF-16
limit. New templates permit 512 UTF-16 code units and 160 rendered characters
(measured using a representative 15-character nickname). Colors (named and HEX),
gradients, rainbow, decorations, resets and fonts are supported. Interactive,
newline, translation and unknown tags, extra placeholders and control/FORMAT
characters are rejected by both ARC and ProxyARC. Discord/Telegram receive plain
text. Font appearance depends on the receiving client's resource pack.

Disabled phrases remain saved; editing transfers the active selection key and
deleting removes both template and selection. Catalog cleanup preserves personal
selections. A successful callback follows the Redis flush and update publication.

MiniMessage syntax follows the [Adventure format reference](https://docs.advntr.dev/minimessage/format).
Example: `<gold>✦ <aqua>%player_name% <gray>снова с нами`.

Deploy the matching ProxyARC change together with ARC before enabling custom
editing. An old ProxyARC ignores custom selections because they are absent from
its catalog. First-ever joins retain their existing special announcement.

## Verification

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test shadowJar
./scripts/render-join-message-preview
```

`JoinMessageDialogTest` exercises pagination, toggles, entry permissions, revoked
custom permission, stale catalogs, cleanup, input, preview, save and deletion.
`CustomJoinMessageTest` covers legacy JSON, the wire round trip, bounds, invalid
input, merge, and disabled-library retention. ProxyARC tests cover selecting only
enabled valid custom phrases and preserving first-join behavior.

The preview uses source text with representative player/catalog values. It
checks the static catalog layout and custom library; the canonical renderer does
not model native text input widgets. The management screen and formatting help
are included. The static renderer interprets literal HEX examples as colors;
unit tests verify the actual help components retain the complete tag examples.
Actual client rendering and a live join/quit are separate runtime checks.
