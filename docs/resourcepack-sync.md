# ItemsAdder resource pack publication

ARC listens for `ItemsAdderPackCompressedEvent`, extracts
`scripts/resourcepack_sync.sh` from the plugin JAR to
`plugins/ARC/scripts/resourcepack_sync.sh`, and runs it asynchronously.

All publication settings are read from
`plugins/ARC/modules/resourcepack-sync.yml` and passed to the script as process
environment variables. No external `.env` file is used.

The public bundled default keeps credential fields empty. Real bucket-scoped
credentials belong only in the private server configuration.

The script uploads:

- `RusCraftingResource.zip` — latest resource pack;
- `RusCraftingResource.zip.sha256` — checksum used to skip unchanged packs;
- `archive/YYYYMMDD-HHMMSS-RusCraftingResource.zip` — versioned archive.

On the production spawn node it also treats spawn ItemsAdder as the only
content authority. A completed `iazip` stages and checksum-verifies exact copies
of `contents/` and the active `storage/` cache, publishes the shared client ZIP,
then atomically swaps those two trees into the sibling survival runtime and
sends `iareload` to its tmux console. The script waits for ItemsAdder's
`Reload completed.` log marker, verifies `contents/` byte-for-byte, and verifies
the active cache mappings semantically because ItemsAdder may reorder YAML
entries while loading them. A changed key or value still fails the publication.
The previous survival trees are retained below the repository-ignored
`.mc-ops/itemsadder-mirror/` root; `survival-mirror.backup-keep` controls bounded
retention.

The mirror is disabled by default and enabled only in the private spawn
`resourcepack-sync.yml`. Source/target directory names and the tmux session are
strictly bounded, the target must remain below the same network root, and a
directory lock rejects concurrent mirror attempts.

After the archive and public object uploads succeed, the script publishes a
versioned event containing only the staged ZIP SHA-256 and a random request ID
to `arc.resourcepack.published` through the existing Redis connection. ProxyARC
accepts that event only from the Paper server identities and runs the fixed
VelocityResourcePacks `generatehashes` command.
Each request has a random ID; the script waits up to 30 seconds for ProxyARC to
acknowledge successful command dispatch in the fixed Redis field for the
originating Paper server. Only the latest acknowledgement is retained for each
allowed server, so interrupted uploads cannot grow Redis state. The manifest is
written only after that acknowledgement, so a missing proxy listener or
rejected command remains retryable instead of recording a completed publication
with a stale Velocity hash. Set
`publish-notification.enabled: false` only for an intentionally standalone
environment.

Before hashing or uploading, the script validates that the archive has exactly
one root `pack.mcmeta`. It normalizes ItemsAdder 4.0.17 metadata into the exact
Minecraft 1.21.11 layout: `supported_formats` moves from the JSON root into the
`pack` object as the legacy `[min, 64]` segment, while missing `pack.min_format`
and `pack.max_format` retain the complete declared range. It also removes the
single blanket `entity/` directory source that ItemsAdder adds to the modern
blocks atlas; Minecraft already owns those vanilla textures in dedicated entity
atlases, and loading them twice produces hundreds of duplicate-sprite warnings.
These are staging-only compatibility fixes; `output/generated.zip` is not
modified. The ZIP is integrity-checked before publication. Already-correct
metadata and atlas content are left byte-identical.

The hook passes the current ItemsAdder instance's `output/generated.zip` as
`RP_SOURCE`. Production pack generation and publication run on spawn only;
survival consumes the mirrored registry and the same published pack.
