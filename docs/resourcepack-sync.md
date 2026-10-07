# ItemsAdder resource pack publication

ARC listens for `ItemsAdderPackCompressedEvent`, extracts
the publisher, Python optimizer and pinned vanilla texture catalogue from the
plugin JAR to `plugins/ARC/scripts/`, and runs the publisher asynchronously.

All publication settings are read from
`plugins/ARC/modules/resourcepack-sync.yml` and passed to the script as process
environment variables. No external `.env` file is used.

The public bundled default keeps credential fields empty. Real bucket-scoped
credentials belong only in the private server configuration.

The script uploads:

- `RusCraftingResource.zip` — universal pack, retaining compatibility overlays;
- `RusCraftingResource-{1.21.11,26.1,26.2,26.3}.zip` — one current optimized
  pack for each explicitly supported client protocol;
- `RusCraftingResource.zip.sha256` — checksums of the entire current set, used
  to skip unchanged publications. The first line remains the universal checksum.

These are fixed current object keys, not dated archives. A changed variant
invalidates the set even when the universal ZIP is unchanged. All variants are
built and checked before the first upload. All ZIP uploads finish before the
proxy hash-refresh notification; the checksum manifest is committed only after
the acknowledgement. Interrupted publication therefore remains retryable.
Separate S3 objects are not a cross-object atomic transaction: a later failed
upload does not roll back objects already replaced. Retry republishes the whole
set; do not treat the old checksum manifest as proof of object consistency.
An unchanged set skips uploads only after every object passes a size and
SHA-256 metadata check. Missing or replaced objects trigger a repair upload.

## Content optimization and client selection

`resourcepack_variants.py` resolves the active overlays in their original
priority order into one effective resource layer for each selected format. It
preserves the effective asset bytes and metadata, and rewrites owned atlas
directory sources to explicit `single` texture references. Exact adjacent
repeated source blocks are collapsed; empty directory results disappear from
the specialized files. Unknown atlas source types are preserved. Texture
resolution and model geometry are not reduced.

`resourcepack_clients.json` records protocols 774/775/776/777 and resource
formats 75.0/84.0/88.0/97.1. Its texture IDs come from the corresponding official
client JARs; each entry retains the download URL and verified client SHA-1.
Updating a client requires regenerating its inventory from that exact client,
checking the version/protocol/format in `version.json`, and updating the proxy
thresholds together. The publisher needs no Minecraft download at runtime.

Each atlas rewrite verifies its resulting sprite-ID-to-resource mapping for
the selected vanilla client plus the effective server pack. Lower vanilla atlas
definitions remain active. Client-added textures that relied on our broad
directory sources are outside this explicit inventory; packs supplying their
own atlas declarations still use the normal resource-pack priority rules.
Keep unknown clients on the universal fallback instead of reusing a frozen
catalogue for a newer protocol.

VelocityResourcepacks build 667 natively supports a flat ordered `variants`
list under `packs.global`, with the shared permission gate on that parent.
The parent must not also have `url`/`hash`. Child entries have individual URLs,
SHA-1 hashes and stable UUIDs; numeric `version` is a minimum protocol, not a
Minecraft version string. Selection order is:

| Minimum protocol | ZIP |
|---|---|
| 778 | universal (future clients) |
| 777 | 26.3 |
| 776 | 26.2 |
| 775 | 26.1 |
| 774 | 1.21.11 |
| 0 | universal (older clients) |

`vrp reload` activates configuration for normal subsequent selection without
resending to online players. `vrp reload resend` additionally forces current
players to reconsider their pack. The existing `generatehashes` notification
updates and persists hashes for each direct variant; do not nest variants.

For an isolated publisher diagnostic, `RP_VARIANTS_ENABLED=0` retains the
single-ZIP path. Production defaults to building the full set. Run the focused
checks with `python3 -m unittest discover -s src/test/python -v` and the existing
`ItemsAdderHookTest` Gradle test.

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

After the current ZIP object upload succeeds, the script publishes a versioned event
containing only the staged ZIP SHA-256 and a random request ID to
`arc.resourcepack.published` through the existing Redis connection. ProxyARC
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
