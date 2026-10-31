#!/usr/bin/env bash
#
# ItemsAdder generated.zip → Yandex Object Storage (ruscraftinresources).
# Bundled in ARC and extracted to plugins/ARC/scripts/resourcepack_sync.sh.
#
set -euo pipefail

LOG_TAG="resourcepack_sync"

log() { echo "[$(date -Iseconds)] ${LOG_TAG}: $*"; }
die() { log "ERROR: $*"; exit 1; }

: "${RP_SOURCE:?RP_SOURCE not set}"

S3_ENDPOINT="${S3_ENDPOINT:-https://storage.yandexcloud.net}"
S3_BUCKET="${S3_BUCKET:-ruscraftinresources}"
RP_UPLOAD_NAME="${RP_UPLOAD_NAME:-RusCraftingResource.zip}"

AWS="${AWS_CLI:-aws}"
S3_KEY="${S3_RP_KEY:-${RP_UPLOAD_NAME}}"
S3_MANIFEST_KEY="${S3_RP_MANIFEST_KEY:-${RP_UPLOAD_NAME}.sha256}"
IA_MIRROR_ENABLED="${IA_MIRROR_ENABLED:-0}"
IA_MIRROR_ONLY="${IA_MIRROR_ONLY:-0}"
IA_MIRROR_SOURCE_SERVER="${IA_MIRROR_SOURCE_SERVER:-classic}"
IA_MIRROR_TARGETS="${IA_MIRROR_TARGETS:-}"
IA_MIRROR_TARGET_SERVER="${IA_MIRROR_TARGET_SERVER:-classic_survival}"
IA_MIRROR_TARGET_SESSION="${IA_MIRROR_TARGET_SESSION:-survival}"
IA_MIRROR_BACKUP_KEEP="${IA_MIRROR_BACKUP_KEEP:-3}"
IA_MIRROR_RELOAD_TIMEOUT_SECONDS="${IA_MIRROR_RELOAD_TIMEOUT_SECONDS:-120}"

[[ "${IA_MIRROR_ONLY}" == "0" || "${IA_MIRROR_ONLY}" == "1" ]] ||
  die "IA_MIRROR_ONLY must be 0 or 1"
if [[ "${IA_MIRROR_ONLY}" == "1" ]]; then
  [[ "${IA_MIRROR_ENABLED}" == "1" ]] || die "IA_MIRROR_ONLY requires IA_MIRROR_ENABLED=1"
else
  : "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID not set}"
  : "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY not set}"
  [[ -f "${RP_SOURCE}" ]] || die "Missing ${RP_SOURCE} — regenerate ItemsAdder pack first"
fi

REDIS="${REDIS_CLI:-redis-cli}"

command -v python3 >/dev/null 2>&1 || die "Missing required command: python3"
if [[ "${IA_MIRROR_ONLY}" == "0" ]]; then
  for required_command in unzip zip; do
    command -v "${required_command}" >/dev/null 2>&1 || die "Missing required command: ${required_command}"
  done
  if [[ "${RP_NOTIFY_ENABLED:-1}" == "1" ]]; then
    command -v "${REDIS}" >/dev/null 2>&1 || die "Missing required command: ${REDIS}"
  fi
fi

mirror_staging_dirs=()
mirror_lock_dir=""
mirror_source_itemsadder=""
mirror_backup_root=""
mirror_target_servers=()
mirror_target_itemsadders=()
mirror_target_sessions=()
mirror_target_names=()
mirror_target_count=0
mirror_cache_files=(
  custom_fires_ids_cache.yml
  font_images_unicode_cache.yml
  items_ids_cache.yml
  real_blocks_ids_cache.yml
  real_blocks_note_ids_cache.yml
  real_transparent_blocks_ids_cache.yml
  real_wire_ids_cache.yml
)

cleanup() {
  local mirror_staging_dir
  for mirror_staging_dir in "${mirror_staging_dirs[@]:-}"; do
    if [[ -n "${mirror_staging_dir}" && -d "${mirror_staging_dir}" ]]; then
      rm -rf -- "${mirror_staging_dir}"
    fi
  done
  if [[ -n "${mirror_lock_dir}" && -d "${mirror_lock_dir}" ]]; then
    rmdir -- "${mirror_lock_dir}" 2>/dev/null || true
  fi
  if [[ -n "${staging_dir:-}" && -d "${staging_dir}" ]]; then
    rm -rf -- "${staging_dir}"
  fi
}
trap cleanup EXIT

verify_itemsadder_contents() {
  local changes
  changes="$("${IA_MIRROR_RSYNC_CLI:-rsync}" -rlcni --delete \
    "${mirror_source_itemsadder}/contents/" "$1/contents/")" ||
    die "Unable to verify ItemsAdder contents in $1"
  [[ -z "${changes}" ]]
}

verify_itemsadder_cache_bytes() {
  local cache
  for cache in "${mirror_cache_files[@]}"; do
    cmp -s "${mirror_source_itemsadder}/storage/${cache}" "$1/storage/${cache}" || return 1
  done
}

prepare_itemsadder_mirror() {
  [[ "${IA_MIRROR_ENABLED}" == "1" ]] || return 0
  [[ "${IA_MIRROR_SOURCE_SERVER}" =~ ^[A-Za-z0-9_-]+$ ]] ||
    die "IA_MIRROR_SOURCE_SERVER must be a server directory name"
  [[ "${IA_MIRROR_BACKUP_KEEP}" =~ ^[1-9][0-9]*$ ]] &&
    (( IA_MIRROR_BACKUP_KEEP <= 20 )) ||
    die "IA_MIRROR_BACKUP_KEEP must be between 1 and 20"
  [[ "${IA_MIRROR_RELOAD_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]] &&
    (( IA_MIRROR_RELOAD_TIMEOUT_SECONDS <= 600 )) ||
    die "IA_MIRROR_RELOAD_TIMEOUT_SECONDS must be between 1 and 600"

  local source_output source_server network_root target_server target_plugins target_itemsadder
  local target_name target_session entry index previous tree cache rsync_bin tmux_sessions lock_dir staging_path
  source_output="$(cd "$(dirname "${RP_SOURCE}")" && pwd -P)"
  mirror_source_itemsadder="$(cd "${source_output}/.." && pwd -P)"
  source_server="$(cd "${mirror_source_itemsadder}/../.." && pwd -P)"
  network_root="$(cd "${source_server}/.." && pwd -P)"

  [[ "$(basename "${source_server}")" == "${IA_MIRROR_SOURCE_SERVER}" ]] ||
    die "ItemsAdder mirror can run only from ${IA_MIRROR_SOURCE_SERVER}, got ${source_server}"

  for tree in contents storage; do
    [[ -d "${mirror_source_itemsadder}/${tree}" && ! -L "${mirror_source_itemsadder}/${tree}" ]] ||
      die "Missing source ItemsAdder tree: ${mirror_source_itemsadder}/${tree}"
  done
  for cache in "${mirror_cache_files[@]}"; do
    [[ -f "${mirror_source_itemsadder}/storage/${cache}" && ! -L "${mirror_source_itemsadder}/storage/${cache}" ]] ||
      die "Missing regular source ItemsAdder ID metadata: ${cache}"
  done

  if [[ -z "${IA_MIRROR_TARGETS}" ]]; then
    IA_MIRROR_TARGETS="${IA_MIRROR_TARGET_SERVER}=${IA_MIRROR_TARGET_SESSION}"
  fi
  while IFS= read -r entry || [[ -n "${entry}" ]]; do
    [[ "${entry}" =~ ^([A-Za-z0-9_-]+)=([A-Za-z0-9_-]+)$ ]] ||
      die "ItemsAdder mirror target must be a server-directory=tmux-session pair: ${entry}"
    target_name="${BASH_REMATCH[1]}"
    target_session="${BASH_REMATCH[2]}"
    target_server="${network_root}/${target_name}"
    [[ -d "${target_server}" ]] || die "Missing mirror target server directory: ${target_server}"
    target_server="$(cd "${target_server}" && pwd -P)"
    [[ "$(dirname "${target_server}")" == "${network_root}" ]] ||
      die "Mirror target server escapes the network root: ${target_server}"
    [[ -d "${target_server}/plugins" ]] || die "Missing mirror target plugins directory: ${target_server}/plugins"
    target_plugins="$(cd "${target_server}/plugins" && pwd -P)"
    target_itemsadder="${target_plugins}/ItemsAdder"
    if [[ -e "${target_itemsadder}" || -L "${target_itemsadder}" ]]; then
      [[ -d "${target_itemsadder}" ]] || die "Invalid mirror target ItemsAdder directory: ${target_itemsadder}"
      target_itemsadder="$(cd "${target_itemsadder}" && pwd -P)"
    else
      [[ "${IA_MIRROR_ONLY}" == "1" ]] || die "Missing mirror target ItemsAdder directory: ${target_itemsadder}"
    fi
    case "${target_itemsadder}/" in
      "${target_server}/"*) ;;
      *) die "Mirror target ItemsAdder directory escapes ${target_server}" ;;
    esac
    [[ "${mirror_source_itemsadder}" != "${target_itemsadder}" ]] ||
      die "ItemsAdder mirror source and target resolve to the same directory"
    for ((previous = 0; previous < mirror_target_count; previous++)); do
      [[ "${mirror_target_servers[$previous]}" != "${target_server}" ]] ||
        die "Duplicate ItemsAdder mirror target server: ${target_name}"
      [[ "${mirror_target_sessions[$previous]}" != "${target_session}" ]] ||
        die "Duplicate ItemsAdder mirror target tmux session: ${target_session}"
    done
    for tree in contents storage; do
      if [[ -e "${target_itemsadder}/${tree}" || -L "${target_itemsadder}/${tree}" ]]; then
        [[ -d "${target_itemsadder}/${tree}" && ! -L "${target_itemsadder}/${tree}" ]] ||
          die "Invalid target ItemsAdder tree: ${target_itemsadder}/${tree}"
      else
        [[ "${IA_MIRROR_ONLY}" == "1" ]] || die "Missing target ItemsAdder tree: ${target_itemsadder}/${tree}"
      fi
    done
    for cache in "${mirror_cache_files[@]}"; do
      if [[ -e "${target_itemsadder}/storage/${cache}" || -L "${target_itemsadder}/storage/${cache}" ]]; then
        [[ -f "${target_itemsadder}/storage/${cache}" && ! -L "${target_itemsadder}/storage/${cache}" ]] ||
          die "Invalid target ItemsAdder ID metadata: ${target_itemsadder}/storage/${cache}"
      fi
    done
    mirror_target_servers+=("${target_server}")
    mirror_target_itemsadders+=("${target_itemsadder}")
    mirror_target_sessions+=("${target_session}")
    mirror_target_names+=("${target_name}")
    mirror_target_count=$((mirror_target_count + 1))
  done <<< "${IA_MIRROR_TARGETS}"

  if [[ "${IA_MIRROR_ONLY}" == "0" ]]; then
    command -v "${IA_MIRROR_TMUX_CLI:-tmux}" >/dev/null 2>&1 ||
      die "Missing required command: ${IA_MIRROR_TMUX_CLI:-tmux}"
    tmux_sessions="$("${IA_MIRROR_TMUX_CLI:-tmux}" list-sessions -F '#{session_name}')" ||
      die "Unable to list target tmux sessions"
    for ((index = 0; index < mirror_target_count; index++)); do
      grep -Fxq -- "${mirror_target_sessions[$index]}" <<< "${tmux_sessions}" ||
        die "Missing target tmux session: ${mirror_target_sessions[$index]}"
      [[ -f "${mirror_target_servers[$index]}/logs/latest.log" ]] ||
        die "Missing target server log: ${mirror_target_servers[$index]}/logs/latest.log"
    done
  fi

  rsync_bin="${IA_MIRROR_RSYNC_CLI:-rsync}"
  command -v "${rsync_bin}" >/dev/null 2>&1 || die "Missing required command: ${rsync_bin}"

  mirror_backup_root="${network_root}/.mc-ops/itemsadder-mirror"
  [[ ! -L "${network_root}/.mc-ops" ]] || die "Refusing symlinked backup parent: ${network_root}/.mc-ops"
  [[ ! -L "${mirror_backup_root}" ]] || die "Refusing symlinked backup root: ${mirror_backup_root}"
  for ((index = 0; index < mirror_target_count; index++)); do
    [[ ! -L "${mirror_backup_root}/${mirror_target_names[$index]}" ]] ||
      die "Refusing symlinked target backup root: ${mirror_target_names[$index]}"
    [[ ! -e "${mirror_backup_root}/${mirror_target_names[$index]}" || -d "${mirror_backup_root}/${mirror_target_names[$index]}" ]] ||
      die "Invalid target backup root: ${mirror_target_names[$index]}"
  done
  mkdir -p -- "${mirror_backup_root}"
  chmod 700 "${mirror_backup_root}"
  lock_dir="${mirror_backup_root}/.lock"
  mkdir -- "${lock_dir}" 2>/dev/null || die "Another ItemsAdder mirror is already running (${lock_dir})"
  mirror_lock_dir="${lock_dir}"

  for ((index = 0; index < mirror_target_count; index++)); do
    target_itemsadder="${mirror_target_itemsadders[$index]}"
    mkdir -p -- "${target_itemsadder}"
    staging_path="$(mktemp -d "${target_itemsadder}/.arc-mirror-staging.XXXXXX")"
    mirror_staging_dirs+=("${staging_path}")
    mkdir -p -- "${mirror_staging_dirs[$index]}/contents" "${mirror_staging_dirs[$index]}/storage"
    "${rsync_bin}" -a --delete \
      "${mirror_source_itemsadder}/contents/" "${mirror_staging_dirs[$index]}/contents/"
    for cache in "${mirror_cache_files[@]}"; do
      cp -p -- "${mirror_source_itemsadder}/storage/${cache}" "${mirror_staging_dirs[$index]}/storage/${cache}"
    done
    verify_itemsadder_contents "${mirror_staging_dirs[$index]}" || die "Staged ItemsAdder contents failed checksum verification"
    verify_itemsadder_cache_bytes "${mirror_staging_dirs[$index]}" || die "Staged ItemsAdder ID metadata failed checksum verification"
    log "Prepared verified ${IA_MIRROR_SOURCE_SERVER} → ${mirror_target_names[$index]} mirror for contents and seven ID metadata files"
  done
}

activate_itemsadder_target() {
  local index="$1"
  local target_itemsadder="${mirror_target_itemsadders[$index]}"
  local target_name="${mirror_target_names[$index]}"
  local target_session="${mirror_target_sessions[$index]}"
  local mirror_staging_dir="${mirror_staging_dirs[$index]}"
  local backup_id backup_dir target_log before_lines before_inode current_inode current_lines
  local tmux_bin deadline reload_complete path installed cache
  local -a paths=(contents)
  local -a moved_paths=()
  local -a installed_paths=()
  for cache in "${mirror_cache_files[@]}"; do paths+=("storage/${cache}"); done
  backup_id="$(date +%Y%m%d-%H%M%S)-$$"
  backup_dir="${mirror_backup_root}/${target_name}/${backup_id}"
  mkdir -p -- "${backup_dir}/storage" "${backup_dir}/failed/storage" "${target_itemsadder}/storage"

  for path in "${paths[@]}"; do
    [[ -e "${target_itemsadder}/${path}" ]] || continue
    if mv -- "${target_itemsadder}/${path}" "${backup_dir}/${path}"; then
      moved_paths+=("${path}")
    else
      for installed in "${moved_paths[@]:-}"; do
        [[ -n "${installed}" ]] || continue
        mv -- "${backup_dir}/${installed}" "${target_itemsadder}/${installed}" || true
      done
      die "Unable to back up ${target_name} ItemsAdder ${path}; restored the previous paths from ${backup_dir}"
    fi
  done
  for path in "${paths[@]}"; do
    if mv -- "${mirror_staging_dir}/${path}" "${target_itemsadder}/${path}"; then
      installed_paths+=("${path}")
    else
      for installed in "${installed_paths[@]:-}"; do
        [[ -n "${installed}" ]] || continue
        mv -- "${target_itemsadder}/${installed}" "${backup_dir}/failed/${installed}" || true
      done
      for installed in "${moved_paths[@]:-}"; do
        [[ -n "${installed}" ]] || continue
        mv -- "${backup_dir}/${installed}" "${target_itemsadder}/${installed}" || true
      done
      die "Unable to activate ${target_name} ItemsAdder ${path}; restored the previous paths from ${backup_dir}"
    fi
  done
  rmdir -- "${mirror_staging_dir}/storage" "${mirror_staging_dir}"
  log "Activated ItemsAdder mirror on ${target_name}; previous contents and ID metadata saved in ${backup_dir}"

  if [[ "${IA_MIRROR_ONLY}" == "1" ]]; then
    verify_itemsadder_contents "${target_itemsadder}" || die "Seeded ItemsAdder contents differ on ${target_name}"
    verify_itemsadder_cache_bytes "${target_itemsadder}" || die "Seeded ItemsAdder ID metadata differ on ${target_name}"
    log "Seeded ${target_name} without resource-pack publication or ItemsAdder reload"
    return 0
  fi

  tmux_bin="${IA_MIRROR_TMUX_CLI:-tmux}"
  target_log="${mirror_target_servers[$index]}/logs/latest.log"
  before_lines="$(wc -l < "${target_log}" | tr -d '[:space:]')"
  before_inode="$(stat -c %i "${target_log}" 2>/dev/null || stat -f %i "${target_log}")"
  "${tmux_bin}" send-keys -t "${target_session}" "iareload" Enter
  log "Sent iareload to ${target_session}; waiting for completion"

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
    die "ItemsAdder reload did not complete on ${target_session} within ${IA_MIRROR_RELOAD_TIMEOUT_SECONDS}s; backup=${backup_dir}"

  verify_itemsadder_contents "${target_itemsadder}" || die "ItemsAdder contents drifted during reload on ${target_name}"
  if ! verify_itemsadder_cache_bytes "${target_itemsadder}"; then
    if ! python3 - "${mirror_source_itemsadder}/storage" "${target_itemsadder}/storage" "${mirror_cache_files[@]}" <<'PY'
import sys
from pathlib import Path

try:
    import yaml
except ImportError as exc:
    raise RuntimeError("PyYAML is required to verify reordered ItemsAdder caches") from exc

source_root = Path(sys.argv[1]).resolve()
target_root = Path(sys.argv[2]).resolve()


for filename in sys.argv[3:]:
    source = source_root / filename
    target = target_root / filename
    if any(path.is_symlink() or not path.is_file() for path in (source, target)):
        raise RuntimeError(f"ItemsAdder ID metadata is not a regular file: {filename}")
    source_value = yaml.safe_load(source.read_text(encoding="utf-8"))
    target_value = yaml.safe_load(target.read_text(encoding="utf-8"))
    if source_value is None:
        source_value = {}
    if target_value is None:
        target_value = {}
    if source_value != target_value:
        raise RuntimeError(f"ItemsAdder YAML cache differs: {filename}")
PY
    then
      die "ItemsAdder ID metadata drifted semantically during reload on ${target_name}; backup=${backup_dir}"
    fi
    log "ItemsAdder ID metadata was reordered during reload on ${target_name}; semantic mappings remain identical"
  fi
  log "ItemsAdder reload completed on ${target_session}"
}

activate_itemsadder_mirror() {
  [[ "${IA_MIRROR_ENABLED}" == "1" ]] || return 0
  local index
  # Check the complete staged set again before replacing the first consumer.
  for ((index = 0; index < mirror_target_count; index++)); do
    [[ -d "${mirror_staging_dirs[$index]}" ]] || die "ItemsAdder mirror staging directory is unavailable"
    verify_itemsadder_contents "${mirror_staging_dirs[$index]}" || die "Source ItemsAdder contents changed after staging"
    verify_itemsadder_cache_bytes "${mirror_staging_dirs[$index]}" || die "Source ItemsAdder ID metadata changed after staging"
  done
  for ((index = 0; index < mirror_target_count; index++)); do
    activate_itemsadder_target "${index}"

    python3 - "${mirror_backup_root}/${mirror_target_names[$index]}" "${IA_MIRROR_BACKUP_KEEP}" <<'PY'
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
  done
  rmdir -- "${mirror_lock_dir}" 2>/dev/null || true
  mirror_lock_dir=""
}

prepare_itemsadder_mirror
if [[ "${IA_MIRROR_ONLY}" == "1" ]]; then
  activate_itemsadder_mirror
  exit 0
fi

staging_dir="$(mktemp -d)"
upload_path="${staging_dir}/${RP_UPLOAD_NAME}"
cp "${RP_SOURCE}" "${upload_path}"

# Java clients do not consume the bundled Bedrock pack or editor/system files.
# Filter the publication copy so plugin exporters cannot reintroduce them.
excluded_files="${staging_dir}/excluded-files.txt"
unzip -Z1 "${upload_path}" | awk '
  /(^|\/)rspm_bedrock_pack\// || tolower($0) ~ /(^|\/)desktop\.ini$/ || tolower($0) ~ /\.aseprite$/
' > "${excluded_files}"
if [[ -s "${excluded_files}" ]]; then
  zip -q -d "${upload_path}" -@ < "${excluded_files}" || die "Unable to remove non-Java pack files"
  log "Excluded non-Java/editor files=$(wc -l < "${excluded_files}" | tr -d ' ')"
fi

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

publish_paths=("${upload_path}")
if [[ "${RP_VARIANTS_ENABLED:-1}" == "1" ]]; then
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  [[ "${RP_UPLOAD_NAME}" =~ ^[A-Za-z0-9_.-]+\.zip$ && "${S3_KEY}" == *.zip ]] ||
    die "Versioned packs require a simple .zip upload name and .zip S3 key"
  variants_dir="${staging_dir}/variants"
  python3 "${script_dir}/resourcepack_variants.py" \
    --input "${upload_path}" --output-dir "${variants_dir}" \
    --catalog "${script_dir}/resourcepack_clients.json" --upload-name "${RP_UPLOAD_NAME}" ||
    die "Resource pack optimization failed; nothing uploaded"
  python3 - "${variants_dir}/variants.json" > "${staging_dir}/publish-files.txt" <<'PY'
import json
import re
import sys
for pack in json.load(open(sys.argv[1], encoding="utf-8"))["packs"]:
    name = pack["file"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+\.zip", name):
        raise ValueError("invalid candidate filename")
    print(name)
PY
  publish_paths=()
  while IFS= read -r name; do
    publish_paths+=("${variants_dir}/${name}")
  done < "${staging_dir}/publish-files.txt"
  [[ "${#publish_paths[@]}" -gt 1 ]] || die "No versioned resource packs were built"
  upload_path="${variants_dir}/${RP_UPLOAD_NAME}"
fi

# One manifest covers the complete set. Keep its first line compatible with
# consumers that read the universal ZIP checksum. Commit it only after the
# whole set is uploaded and Velocity acknowledges its hash refresh.
manifest_path="${staging_dir}/publication.sha256"
publish_keys=()
for candidate in "${publish_paths[@]}"; do
  candidate_name="$(basename "${candidate}")"
  object_key="${S3_KEY}"
  if [[ "${candidate_name}" != "${RP_UPLOAD_NAME}" ]]; then
    suffix="${candidate_name#"${RP_UPLOAD_NAME%.zip}"}"
    object_key="${S3_KEY%.zip}${suffix}"
  fi
  publish_keys+=("${object_key}")
  printf '%s  %s\n' "$(sha256sum "${candidate}" | awk '{print $1}')" "$(basename "${candidate}")"
done > "${manifest_path}"
local_sha="$(sha256sum "${upload_path}" | awk '{print $1}')"
publication_sha="$(sha256sum "${manifest_path}" | awk '{print $1}')"
remote_manifest=""
if remote_manifest="$("${AWS}" s3 cp "s3://${S3_BUCKET}/${S3_MANIFEST_KEY}" - \
  --endpoint-url "${S3_ENDPOINT}" 2>/dev/null)"; then
  :
else
  remote_manifest=""
fi

unchanged=0
if [[ "$(cat "${manifest_path}")" == "${remote_manifest}" && "${FORCE_UPLOAD:-0}" != "1" ]]; then
  unchanged=1
  if [[ "${RP_VARIANTS_ENABLED:-1}" == "1" ]]; then
    for index in "${!publish_paths[@]}"; do
      candidate="${publish_paths[$index]}"
      expected_sha="$(sha256sum "${candidate}" | awk '{print $1}')"
      expected_size="$(wc -c < "${candidate}" | tr -d '[:space:]')"
      if ! object_info="$("${AWS}" s3api head-object --bucket "${S3_BUCKET}" \
        --key "${publish_keys[$index]}" --endpoint-url "${S3_ENDPOINT}" \
        --query '[ContentLength,Metadata.sha256]' --output text 2>/dev/null)"; then
        unchanged=0
        break
      fi
      read -r object_size object_sha <<< "${object_info}"
      if [[ "${object_size}" != "${expected_size}" || "${object_sha}" != "${expected_sha}" ]]; then
        unchanged=0
        break
      fi
    done
  fi
fi
if [[ "${unchanged}" == "1" ]]; then
  log "Unchanged (sha256 ${local_sha:0:12}…), skip upload"
  activate_itemsadder_mirror
  exit 0
fi

for index in "${!publish_paths[@]}"; do
  candidate="${publish_paths[$index]}"
  candidate_name="$(basename "${candidate}")"
  object_key="${publish_keys[$index]}"
  candidate_sha="$(sha256sum "${candidate}" | awk '{print $1}')"
  log "Uploading $(du -h "${candidate}" | cut -f1) as ${candidate_name} → s3://${S3_BUCKET}/${object_key}"
  "${AWS}" s3 cp "${candidate}" "s3://${S3_BUCKET}/${object_key}" \
    --endpoint-url "${S3_ENDPOINT}" \
    --content-type "application/zip" --metadata "sha256=${candidate_sha}"
done

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
  notification="${REDIS_SERVER_NAME}${REDIS_WIRE_DELIMITER}v1:${publication_sha}:${request_id}"
  subscribers="$("${REDIS}" "${redis_args[@]}" PUBLISH "${RP_PUBLISHED_CHANNEL}" "${notification}")" ||
    die "Unable to notify Velocity about the published resource pack"
  [[ "${subscribers}" =~ ^[1-9][0-9]*$ ]] ||
    die "Velocity resource-pack notification had no subscribers"

  acknowledged=""
  expected_ack="v1:${request_id}:${publication_sha}"
  for ((attempt = 1; attempt <= 30; attempt++)); do
    acknowledged="$("${REDIS}" "${redis_args[@]}" HGET "${RP_PUBLISHED_ACK_KEY}" "${REDIS_SERVER_NAME}" 2>/dev/null || true)"
    [[ "${acknowledged}" == "${expected_ack}" ]] && break
    sleep 1
  done
  [[ "${acknowledged}" == "${expected_ack}" ]] ||
    die "Velocity did not acknowledge the resource-pack hash refresh"
  log "Velocity hash refresh acknowledged (subscribers=${subscribers})"
fi

cat "${manifest_path}" | "${AWS}" s3 cp - "s3://${S3_BUCKET}/${S3_MANIFEST_KEY}" \
  --endpoint-url "${S3_ENDPOINT}" \
  --content-type "text/plain"

activate_itemsadder_mirror
log "Done. sha256=${local_sha}"
