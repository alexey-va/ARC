# Join and quit message dialogs

`/arc joinmessage` and `/arc quitmessage` open native Paper dialogs. Existing
command aliases and command permission overrides are retained. The catalog is
still published by ProxyARC through `arc.join_message_catalog`.

## Player flow

- The catalog uses one column, six phrases per page, and 600 GUI-unit buttons.
  Click a phrase to enable/disable it. `[Вкл]` and green text identify enabled
  phrases; `[Недоступно]` identifies unavailable choices. The full phrase is also
  present in its tooltip. Extremely long operator-authored phrases can exceed a
  button at some client GUI scales; their tooltips preserve the complete text.
- Multiple selections remain supported. ProxyARC randomly picks one enabled
  phrase for each join or quit. With no selection it uses the network default.
- Saved personal phrases appear first in the same paginated catalog with a `★`
  marker and a tooltip identifying them. Clicking toggles them in place;
  editing and deleting remain in `Мои фразы`. Personal toggles recheck the
  custom permission, and saved phrases remain visible if that permission is lost.
- Next/previous retain the message kind. The switch control opens the other kind.
- `Мои фразы` opens a separate personal library. Enter a suffix, inspect the
  preview, then save and enable it. The player name is always prepended. Open a
  saved phrase to disable, enable or delete it.

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
commands. Width is bounded to 300–1024 and page size to 1–10.

## Storage and broadcast contract

The existing Redis record/channel remain `arc.join_messages` /
`arc.join_messages_update`. `joinMessages` and `leaveMessages` still contain
selected template strings. New `customJoinMessages` and `customLeaveMessages`
contain saved plain suffixes; missing or null new fields mean an empty library.
A custom selection key is `%player_name% ` followed by its suffix.

Each library has at most ten phrases, each at most 120 UTF-16 code units after
trimming. ARC validates before saving and ProxyARC validates before selection.
Control/FORMAT characters, markup, color codes and placeholder syntax are
rejected. Disabled phrases remain saved; deleting removes both the suffix and
its selection key. Catalog cleanup preserves personal selections. A successful
save callback follows the Redis flush and update publication.

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
not model native text input widgets. Unit tests cover form actions, but actual
client rendering and a live join/quit are separate runtime checks.
