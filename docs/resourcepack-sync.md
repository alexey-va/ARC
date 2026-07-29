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

The hook passes the current ItemsAdder instance's `output/generated.zip` as
`RP_SOURCE`, so spawn and survival publish their own generated pack.
