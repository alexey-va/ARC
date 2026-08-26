#!/usr/bin/env bash
#
# ItemsAdder generated.zip → Yandex Object Storage (ruscraftinresources).
# Bundled in ARC and extracted to plugins/ARC/scripts/resourcepack_sync.sh.
#
set -euo pipefail

LOG_TAG="resourcepack_sync"

log() { echo "[$(date -Iseconds)] ${LOG_TAG}: $*"; }
die() { log "ERROR: $*"; exit 1; }

: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID not set}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY not set}"
: "${RP_SOURCE:?RP_SOURCE not set}"

S3_ENDPOINT="${S3_ENDPOINT:-https://storage.yandexcloud.net}"
S3_BUCKET="${S3_BUCKET:-ruscraftinresources}"
RP_UPLOAD_NAME="${RP_UPLOAD_NAME:-RusCraftingResource.zip}"

AWS="${AWS_CLI:-aws}"
S3_KEY="${S3_RP_KEY:-${RP_UPLOAD_NAME}}"
S3_MANIFEST_KEY="${S3_RP_MANIFEST_KEY:-${RP_UPLOAD_NAME}.sha256}"
ARCHIVE_PREFIX="${S3_RP_ARCHIVE_PREFIX:-archive}"
IA_MIRROR_ENABLED="${IA_MIRROR_ENABLED:-0}"
IA_MIRROR_SOURCE_SERVER="${IA_MIRROR_SOURCE_SERVER:-classic}"
IA_MIRROR_TARGET_SERVER="${IA_MIRROR_TARGET_SERVER:-classic_survival}"
IA_MIRROR_TARGET_SESSION="${IA_MIRROR_TARGET_SESSION:-survival}"
IA_MIRROR_BACKUP_KEEP="${IA_MIRROR_BACKUP_KEEP:-3}"
IA_MIRROR_RELOAD_TIMEOUT_SECONDS="${IA_MIRROR_RELOAD_TIMEOUT_SECONDS:-120}"

[[ -f "${RP_SOURCE}" ]] || die "Missing ${RP_SOURCE} — regenerate ItemsAdder pack first"

REDIS="${REDIS_CLI:-redis-cli}"

for required_command in python3 unzip zip; do
  command -v "${required_command}" >/dev/null 2>&1 || die "Missing required command: ${required_command}"
done
if [[ "${RP_NOTIFY_ENABLED:-1}" == "1" ]]; then
  command -v "${REDIS}" >/dev/null 2>&1 || die "Missing required command: ${REDIS}"
fi

mirror_staging_dir=""
mirror_lock_dir=""
mirror_source_itemsadder=""
mirror_target_server=""
mirror_target_itemsadder=""
mirror_backup_root=""

cleanup() {
  if [[ -n "${mirror_staging_dir}" && -d "${mirror_staging_dir}" ]]; then
    rm -rf -- "${mirror_staging_dir}"
  fi
  if [[ -n "${mirror_lock_dir}" && -d "${mirror_lock_dir}" ]]; then
    rmdir -- "${mirror_lock_dir}" 2>/dev/null || true
  fi
  if [[ -n "${staging_dir:-}" && -d "${staging_dir}" ]]; then
    rm -rf -- "${staging_dir}"
  fi
}
trap cleanup EXIT

prepare_itemsadder_mirror() {
  [[ "${IA_MIRROR_ENABLED}" == "1" ]] || return 0
  [[ "${IA_MIRROR_SOURCE_SERVER}" =~ ^[A-Za-z0-9_-]+$ ]] ||
    die "IA_MIRROR_SOURCE_SERVER must be a server directory name"
  [[ "${IA_MIRROR_TARGET_SERVER}" =~ ^[A-Za-z0-9_-]+$ ]] ||
    die "IA_MIRROR_TARGET_SERVER must be a server directory name"
  [[ "${IA_MIRROR_TARGET_SESSION}" =~ ^[A-Za-z0-9_-]+$ ]] ||
    die "IA_MIRROR_TARGET_SESSION must be a tmux session name"
  [[ "${IA_MIRROR_BACKUP_KEEP}" =~ ^[1-9][0-9]*$ ]] &&
    (( IA_MIRROR_BACKUP_KEEP <= 20 )) ||
    die "IA_MIRROR_BACKUP_KEEP must be between 1 and 20"
  [[ "${IA_MIRROR_RELOAD_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]] &&
    (( IA_MIRROR_RELOAD_TIMEOUT_SECONDS <= 600 )) ||
    die "IA_MIRROR_RELOAD_TIMEOUT_SECONDS must be between 1 and 600"

  local source_output source_server network_root target_server rsync_bin
  source_output="$(cd "$(dirname "${RP_SOURCE}")" && pwd -P)"
  mirror_source_itemsadder="$(cd "${source_output}/.." && pwd -P)"
  source_server="$(cd "${mirror_source_itemsadder}/../.." && pwd -P)"
  network_root="$(cd "${source_server}/.." && pwd -P)"

  [[ "$(basename "${source_server}")" == "${IA_MIRROR_SOURCE_SERVER}" ]] ||
    die "ItemsAdder mirror can run only from ${IA_MIRROR_SOURCE_SERVER}, got ${source_server}"

  target_server="${network_root}/${IA_MIRROR_TARGET_SERVER}"
  [[ -d "${target_server}" ]] || die "Missing mirror target server directory: ${target_server}"
  mirror_target_server="$(cd "${target_server}" && pwd -P)"
  [[ "$(dirname "${mirror_target_server}")" == "${network_root}" ]] ||
    die "Mirror target server escapes the network root: ${mirror_target_server}"
  mirror_target_itemsadder="${mirror_target_server}/plugins/ItemsAdder"
  [[ -d "${mirror_target_itemsadder}" ]] ||
    die "Missing mirror target ItemsAdder directory: ${mirror_target_itemsadder}"
  mirror_target_itemsadder="$(cd "${mirror_target_itemsadder}" && pwd -P)"
  case "${mirror_target_itemsadder}/" in
    "${mirror_target_server}/"*) ;;
    *) die "Mirror target ItemsAdder directory escapes ${mirror_target_server}" ;;
  esac
  [[ "${mirror_source_itemsadder}" != "${mirror_target_itemsadder}" ]] ||
    die "ItemsAdder mirror source and target resolve to the same directory"

  for tree in contents storage; do
    [[ -d "${mirror_source_itemsadder}/${tree}" ]] ||
      die "Missing source ItemsAdder tree: ${mirror_source_itemsadder}/${tree}"
    [[ -d "${mirror_target_itemsadder}/${tree}" ]] ||
      die "Missing target ItemsAdder tree: ${mirror_target_itemsadder}/${tree}"
  done

  rsync_bin="${IA_MIRROR_RSYNC_CLI:-rsync}"
  command -v "${rsync_bin}" >/dev/null 2>&1 || die "Missing required command: ${rsync_bin}"

  mirror_backup_root="${network_root}/.mc-ops/itemsadder-mirror"
  [[ ! -L "${network_root}/.mc-ops" ]] || die "Refusing symlinked backup parent: ${network_root}/.mc-ops"
  [[ ! -L "${mirror_backup_root}" ]] || die "Refusing symlinked backup root: ${mirror_backup_root}"
  mkdir -p -- "${mirror_backup_root}"
  chmod 700 "${mirror_backup_root}"
  mirror_lock_dir="${mirror_backup_root}/.lock"
  mkdir -- "${mirror_lock_dir}" 2>/dev/null ||
    die "Another ItemsAdder mirror is already running (${mirror_lock_dir})"

  mirror_staging_dir="$(mktemp -d "${mirror_target_itemsadder}/.arc-mirror-staging.XXXXXX")"
  for tree in contents storage; do
    mkdir -p -- "${mirror_staging_dir}/${tree}"
    "${rsync_bin}" -a --delete \
      "${mirror_source_itemsadder}/${tree}/" "${mirror_staging_dir}/${tree}/"
    if [[ -n "$("${rsync_bin}" -anic --delete \
      "${mirror_source_itemsadder}/${tree}/" "${mirror_staging_dir}/${tree}/")" ]]; then
      die "Staged ItemsAdder ${tree} failed checksum verification"
    fi
  done
  log "Prepared verified spawn → survival mirror for contents and storage"
}

activate_itemsadder_mirror() {
  [[ "${IA_MIRROR_ENABLED}" == "1" ]] || return 0
  [[ -n "${mirror_staging_dir}" && -d "${mirror_staging_dir}" ]] ||
    die "ItemsAdder mirror staging directory is unavailable"

  local backup_id backup_dir target_log before_lines before_inode current_inode current_lines
  local tmux_bin deadline reload_complete tree installed
  local -a moved_trees=()
  local -a installed_trees=()
  backup_id="$(date +%Y%m%d-%H%M%S)-$$"
  backup_dir="${mirror_backup_root}/${backup_id}"
  mkdir -p -- "${backup_dir}"

  for tree in contents storage; do
    if mv -- "${mirror_target_itemsadder}/${tree}" "${backup_dir}/${tree}"; then
      moved_trees+=("${tree}")
    else
      for installed in "${moved_trees[@]}"; do
        mv -- "${backup_dir}/${installed}" "${mirror_target_itemsadder}/${installed}" || true
      done
      die "Unable to back up survival ItemsAdder ${tree}; restored the previous trees"
    fi
  done
  for tree in contents storage; do
    if mv -- "${mirror_staging_dir}/${tree}" "${mirror_target_itemsadder}/${tree}"; then
      installed_trees+=("${tree}")
    else
      for installed in "${installed_trees[@]}"; do
        mv -- "${mirror_target_itemsadder}/${installed}" \
          "${backup_dir}/failed-${installed}" || true
      done
      for installed in "${moved_trees[@]}"; do
        mv -- "${backup_dir}/${installed}" "${mirror_target_itemsadder}/${installed}" || true
      done
      die "Unable to activate mirrored ItemsAdder ${tree}; restored the previous trees"
    fi
  done
  rmdir -- "${mirror_staging_dir}"
  mirror_staging_dir=""
  log "Activated ItemsAdder mirror; previous survival trees saved in ${backup_dir}"

  tmux_bin="${IA_MIRROR_TMUX_CLI:-tmux}"
  command -v "${tmux_bin}" >/dev/null 2>&1 || die "Missing required command: ${tmux_bin}"
  "${tmux_bin}" list-sessions -F '#{session_name}' 2>/dev/null |
    grep -Fxq "${IA_MIRROR_TARGET_SESSION}" ||
    die "Missing target tmux session: ${IA_MIRROR_TARGET_SESSION}"

  target_log="${mirror_target_server}/logs/latest.log"
  [[ -f "${target_log}" ]] || die "Missing target server log: ${target_log}"
  before_lines="$(wc -l < "${target_log}" | tr -d '[:space:]')"
  before_inode="$(stat -c %i "${target_log}" 2>/dev/null || stat -f %i "${target_log}")"
  "${tmux_bin}" send-keys -t "${IA_MIRROR_TARGET_SESSION}" "iareload" Enter
  log "Sent iareload to ${IA_MIRROR_TARGET_SESSION}; waiting for completion"

  deadline=$((SECONDS + IA_MIRROR_RELOAD_TIMEOUT_SECONDS))
  reload_complete=0
  while (( SECONDS < deadline )); do
    current_inode="$(stat -c %i "${target_log}" 2>/dev/null || stat -f %i "${target_log}")"
    current_lines="$(wc -l < "${target_log}" | tr -d '[:space:]')"
    if [[ "${current_inode}" != "${before_inode}" ]] || (( current_lines < before_lines )); then
      before_inode="${current_inode}"
      before_lines=0
    fi
    if tail -n "+$((before_lines + 1))" "${target_log}" 2>/dev/null |
      grep -Fq "Reload completed."; then
      reload_complete=1
      break
    fi
    sleep 1
  done
  [[ "${reload_complete}" == "1" ]] ||
    die "ItemsAdder reload did not complete on ${IA_MIRROR_TARGET_SESSION} within ${IA_MIRROR_RELOAD_TIMEOUT_SECONDS}s"

  for tree in contents storage; do
    if [[ -n "$("${IA_MIRROR_RSYNC_CLI:-rsync}" -anic --delete \
      "${mirror_source_itemsadder}/${tree}/" "${mirror_target_itemsadder}/${tree}/")" ]]; then
      die "Survival ItemsAdder ${tree} drifted during reload"
    fi
  done
  log "ItemsAdder reload completed on ${IA_MIRROR_TARGET_SESSION}"

  python3 - "${mirror_backup_root}" "${IA_MIRROR_BACKUP_KEEP}" <<'PY'
import shutil
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
keep = int(sys.argv[2])
backups = sorted(
    (
        path
        for path in root.iterdir()
        if path.is_dir() and not path.is_symlink() and path.name != ".lock"
    ),
    key=lambda path: path.stat().st_mtime_ns,
    reverse=True,
)
for path in backups[keep:]:
    if path.parent != root:
        raise RuntimeError(f"refusing to remove backup outside {root}: {path}")
    shutil.rmtree(path)
PY
  rmdir -- "${mirror_lock_dir}" 2>/dev/null || true
  mirror_lock_dir=""
}

staging_dir="$(mktemp -d)"
upload_path="${staging_dir}/${RP_UPLOAD_NAME}"
cp "${RP_SOURCE}" "${upload_path}"

prepare_itemsadder_mirror

# Minecraft 1.21.9+ reads min_format, max_format, and supported_formats from the
# pack object. ItemsAdder 4.0.17 still emits supported_formats at the JSON root,
# so normalize only the staged metadata before publication.
metadata_entries="$(unzip -Z1 "${upload_path}" | awk '$0 == "pack.mcmeta" { count++ } END { print count + 0 }')"
[[ "${metadata_entries}" == "1" ]] || die "Expected exactly one root pack.mcmeta, found ${metadata_entries}"

metadata_path="${staging_dir}/pack.mcmeta"
unzip -p "${upload_path}" pack.mcmeta > "${metadata_path}" || die "Unable to read root pack.mcmeta"

if ! metadata_status="$(python3 - "${metadata_path}" <<'PY'
import json
import os
import sys
from pathlib import Path


def version_major(value):
    if isinstance(value, bool):
        raise ValueError("boolean is not a pack version")
    if isinstance(value, int):
        return value
    if isinstance(value, list) and value and isinstance(value[0], int):
        return value[0]
    raise ValueError(f"unsupported pack version: {value!r}")


def supported_range(value, fallback):
    if value is None:
        if fallback is None:
            raise ValueError("pack_format and supported_formats are both missing")
        return fallback, fallback
    if isinstance(value, bool):
        raise ValueError("boolean is not a supported_formats range")
    if isinstance(value, int):
        return value, value
    if isinstance(value, list) and len(value) == 2:
        minimum, maximum = value
        if any(isinstance(item, bool) or not isinstance(item, int) for item in (minimum, maximum)):
            raise ValueError(f"unsupported supported_formats range: {value!r}")
        if minimum > maximum:
            raise ValueError(f"reversed supported_formats range: {value!r}")
        return minimum, maximum
    if isinstance(value, dict) and "min_inclusive" in value and "max_inclusive" in value:
        return value["min_inclusive"], value["max_inclusive"]
    raise ValueError(f"unsupported supported_formats range: {value!r}")


metadata_path = Path(sys.argv[1])
metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
pack = metadata.get("pack")
if not isinstance(pack, dict):
    raise ValueError("pack.mcmeta has no pack object")

pack_format = pack.get("pack_format")
root_supported_formats = metadata.get("supported_formats")
pack_supported_formats = pack.get("supported_formats")
range_source = root_supported_formats if root_supported_formats is not None else pack_supported_formats
if range_source is None and pack_format is None and "min_format" in pack and "max_format" in pack:
    range_min = version_major(pack["min_format"])
    range_max = version_major(pack["max_format"])
else:
    range_min, range_max = supported_range(range_source, pack_format)

effective_min = version_major(pack.get("min_format", range_min))
effective_max = version_major(pack.get("max_format", range_max))
if effective_min > effective_max:
    raise ValueError(f"min_format {effective_min} exceeds max_format {effective_max}")

declares_modern_format = effective_max > 64
if pack_format is not None:
    declares_modern_format = declares_modern_format or version_major(pack_format) > 64

changed = False
if root_supported_formats is not None:
    metadata.pop("supported_formats")
    changed = True

if declares_modern_format:
    if "min_format" not in pack:
        pack["min_format"] = range_min
        effective_min = version_major(range_min)
        changed = True
    if "max_format" not in pack:
        pack["max_format"] = range_max
        effective_max = version_major(range_max)
        changed = True

# Formats up to 64 use the legacy integer range. Formats 65+ use min/max.
# A multi-version pack crossing that boundary must declare both representations.
if effective_min <= 64:
    legacy_range = [effective_min, min(effective_max, 64)]
    if pack.get("supported_formats") != legacy_range:
        pack["supported_formats"] = legacy_range
        changed = True

if not changed:
    print("unchanged")
    raise SystemExit(0)

metadata_path.write_text(
    json.dumps(metadata, ensure_ascii=False, separators=(",", ":")),
    encoding="utf-8",
)
# Keep the replacement entry deterministic across repeated publications.
os.utime(metadata_path, (315532800, 315532800))
print(
    "patched "
    f"min_format={pack.get('min_format')!r} "
    f"max_format={pack.get('max_format')!r} "
    f"supported_formats={pack.get('supported_formats')!r}"
)
PY
)"; then
  die "Invalid pack.mcmeta; refusing to publish"
fi

archive_patched=0
if [[ "${metadata_status}" == patched* ]]; then
  zip -q -X -j "${upload_path}" "${metadata_path}" || die "Unable to update root pack.mcmeta"
  archive_patched=1
  log "Compatibility metadata ${metadata_status}"
fi

# ItemsAdder 4.0.17 adds every vanilla entity texture to the blocks atlas on
# modern clients. Minecraft already owns those textures in dedicated entity
# atlases, producing hundreds of duplicate-sprite warnings. No custom model in
# this pack needs that blanket directory source, so remove only that exact entry.
modern_blocks_atlas="ia_overlay_modern_atlas/assets/minecraft/atlases/blocks.json"
atlas_entries="$(unzip -Z1 "${upload_path}" | awk -v target="${modern_blocks_atlas}" '$0 == target { count++ } END { print count + 0 }')"
[[ "${atlas_entries}" -le 1 ]] || die "Expected at most one ${modern_blocks_atlas}, found ${atlas_entries}"

if [[ "${atlas_entries}" == "1" ]]; then
  atlas_path="${staging_dir}/${modern_blocks_atlas}"
  mkdir -p "$(dirname "${atlas_path}")"
  unzip -p "${upload_path}" "${modern_blocks_atlas}" > "${atlas_path}" ||
    die "Unable to read ${modern_blocks_atlas}"

  if ! atlas_status="$(python3 - "${atlas_path}" "${upload_path}" <<'PY'
import json
import os
import sys
import zipfile
from pathlib import Path


atlas_path = Path(sys.argv[1])
archive_path = Path(sys.argv[2])
atlas = json.loads(atlas_path.read_text(encoding="utf-8"))
sources = atlas.get("sources")
if not isinstance(sources, list):
    raise ValueError("modern blocks atlas has no sources list")


def is_duplicate_entity_directory(source):
    return (
        isinstance(source, dict)
        and source.get("type") in {"directory", "minecraft:directory"}
        and source.get("source") == "entity"
        and source.get("prefix") == "entity/"
    )


removed = sum(1 for source in sources if is_duplicate_entity_directory(source))
if removed == 0:
    print("unchanged")
    raise SystemExit(0)

with zipfile.ZipFile(archive_path) as archive:
    for entry in archive.infolist():
        if entry.is_dir() or "/models/" not in entry.filename or not entry.filename.endswith(".json"):
            continue
        model = archive.read(entry)
        if b"minecraft:entity/" in model or b'"entity/' in model:
            print(f"unchanged guarded_by_model={entry.filename}")
            raise SystemExit(0)

filtered = [source for source in sources if not is_duplicate_entity_directory(source)]
atlas["sources"] = filtered
atlas_path.write_text(
    json.dumps(atlas, ensure_ascii=False, separators=(",", ":")),
    encoding="utf-8",
)
os.utime(atlas_path, (315532800, 315532800))
print(f"patched removed_entity_directory_sources={removed}")
PY
  )"; then
    die "Invalid ${modern_blocks_atlas}; refusing to publish"
  fi

  if [[ "${atlas_status}" == patched* ]]; then
    (cd "${staging_dir}" && zip -q -X "${upload_path}" "${modern_blocks_atlas}") ||
      die "Unable to update ${modern_blocks_atlas}"
    archive_patched=1
    log "Compatibility atlas ${atlas_status}"
  fi
fi

unzip -tq "${upload_path}" >/dev/null || die "Resource pack failed ZIP integrity check"

local_sha="$(sha256sum "${upload_path}" | awk '{print $1}')"
remote_sha=""
if remote_sha="$("${AWS}" s3 cp "s3://${S3_BUCKET}/${S3_MANIFEST_KEY}" - \
  --endpoint-url "${S3_ENDPOINT}" 2>/dev/null | awk '{print $1}')"; then
  :
else
  remote_sha=""
fi

if [[ "${local_sha}" == "${remote_sha}" && "${FORCE_UPLOAD:-0}" != "1" ]]; then
  log "Unchanged (sha256 ${local_sha:0:12}…), skip upload"
  activate_itemsadder_mirror
  exit 0
fi

archive_key="${ARCHIVE_PREFIX}/$(date +%Y%m%d-%H%M%S)-${RP_UPLOAD_NAME}"
log "Archive → s3://${S3_BUCKET}/${archive_key}"
"${AWS}" s3 cp "${upload_path}" "s3://${S3_BUCKET}/${archive_key}" \
  --endpoint-url "${S3_ENDPOINT}" \
  --content-type "application/zip"

log "Uploading $(du -h "${upload_path}" | cut -f1) as ${RP_UPLOAD_NAME} → s3://${S3_BUCKET}/${S3_KEY}"
"${AWS}" s3 cp "${upload_path}" "s3://${S3_BUCKET}/${S3_KEY}" \
  --endpoint-url "${S3_ENDPOINT}" \
  --content-type "application/zip"

if [[ "${RP_NOTIFY_ENABLED:-1}" == "1" ]]; then
  : "${REDIS_HOST:?REDIS_HOST not set}"
  : "${REDIS_PORT:?REDIS_PORT not set}"
  : "${REDIS_SERVER_NAME:?REDIS_SERVER_NAME not set}"
  : "${REDIS_WIRE_DELIMITER:?REDIS_WIRE_DELIMITER not set}"
  : "${RP_PUBLISHED_CHANNEL:?RP_PUBLISHED_CHANNEL not set}"
  : "${RP_PUBLISHED_ACK_KEY:?RP_PUBLISHED_ACK_KEY not set}"

  redis_args=(--raw --no-auth-warning -h "${REDIS_HOST}" -p "${REDIS_PORT}")
  if [[ -n "${REDIS_USERNAME:-}" ]]; then
    redis_args+=(--user "${REDIS_USERNAME}")
  fi
  request_id="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
  notification="${REDIS_SERVER_NAME}${REDIS_WIRE_DELIMITER}v1:${local_sha}:${request_id}"
  subscribers="$("${REDIS}" "${redis_args[@]}" PUBLISH "${RP_PUBLISHED_CHANNEL}" "${notification}")" ||
    die "Unable to notify Velocity about the published resource pack"
  [[ "${subscribers}" =~ ^[1-9][0-9]*$ ]] ||
    die "Velocity resource-pack notification had no subscribers"

  acknowledged=""
  expected_ack="v1:${request_id}:${local_sha}"
  for ((attempt = 1; attempt <= 30; attempt++)); do
    acknowledged="$("${REDIS}" "${redis_args[@]}" HGET "${RP_PUBLISHED_ACK_KEY}" "${REDIS_SERVER_NAME}" 2>/dev/null || true)"
    [[ "${acknowledged}" == "${expected_ack}" ]] && break
    sleep 1
  done
  [[ "${acknowledged}" == "${expected_ack}" ]] ||
    die "Velocity did not acknowledge the resource-pack hash refresh"
  log "Velocity hash refresh acknowledged (subscribers=${subscribers})"
fi

printf '%s  %s\n' "${local_sha}" "${RP_UPLOAD_NAME}" | "${AWS}" s3 cp - "s3://${S3_BUCKET}/${S3_MANIFEST_KEY}" \
  --endpoint-url "${S3_ENDPOINT}" \
  --content-type "text/plain"

activate_itemsadder_mirror
log "Done. sha256=${local_sha}"
