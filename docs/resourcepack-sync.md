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
one root `pack.mcmeta`. For packs that declare support for resource-pack format
65 or newer, it fills missing `pack.min_format` and `pack.max_format` from the
ItemsAdder `supported_formats` range. This is a staging-only compatibility fix
for ItemsAdder 4.0.17 output; `output/generated.zip` is not modified. The ZIP is
then integrity-checked before publication. If both modern fields already exist,
the staging archive is uploaded byte-for-byte without a metadata rewrite.

The hook passes the current ItemsAdder instance's `output/generated.zip` as
`RP_SOURCE`, so spawn and survival publish their own generated pack.
