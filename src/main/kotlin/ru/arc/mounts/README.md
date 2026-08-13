# ARC mounts

Native replacement for `Denizen/scripts/activities/rideable_mobs.dsc`.

## Player behavior

- `/mounts` opens the collection.
- Left click summons an unlocked mount for the configured session duration.
- Right click opens purchase, upgrade, summon, and glow controls.
- Walking mounts use WASD, Space to jump, and Shift to dismount.
- Flying and swimming mounts use WASD, Space to ascend, and Shift to descend.
  Double-press Shift to dismount; ARC cancels the first vanilla dismount event.
- Mount and rider damage, logout, teleport, world change, expiry, invalid state,
  or leaving water removes the temporary entity.

Existing levels remain the LuckPerms nodes
`mcfine.mount.<mount>.<level>`. New glow ownership uses
`mcfine.mount.<mount>.glow`; disabling it adds the separate positive marker
`mcfine.mount.<mount>.glow.disabled` so inherited ownership is not destroyed.
Only a direct user marker counts as disabled, so an administrative wildcard
does not accidentally switch glow off.

## Extraction boundary

The package is intentionally self-contained:

- `MountDomain.kt` contains the catalog, profiles, input, and motion math with
  no Bukkit or ARC dependency.
- `MountOwnership` and `MountWallet` are service-provider interfaces.
- `LuckPermsMountOwnership` and `VaultMountWallet` are replaceable adapters.
- `MountSessionController` and `MountGuiController` are the Paper layer.
- `MountModule` is the only ARC lifecycle/bootstrap entry point.

To extract this as a plugin later, move `ru.arc.mounts`, replace `MountModule`
with a small `JavaPlugin`, and provide a scheduler plus the same LuckPerms and
Vault adapters. No other ARC gameplay module owns mount state.

## Configuration

The bundled and live file is `plugins/ARC/modules/mounts.yml`. Spawn and
survival intentionally have separate tracked copies because their world policy
differs. Catalog speed values preserve the former Denizen progression; type
scales convert them into bounded Bukkit velocity.

The optional `legacy-glow-owners` map is a read-only compatibility bridge for
old Denizen server flags. New writes never go back to Denizen.
