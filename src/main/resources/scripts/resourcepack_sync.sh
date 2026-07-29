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

[[ -f "${RP_SOURCE}" ]] || die "Missing ${RP_SOURCE} — regenerate ItemsAdder pack first"

staging_dir="$(mktemp -d)"
trap 'rm -rf "${staging_dir}"' EXIT
upload_path="${staging_dir}/${RP_UPLOAD_NAME}"
cp "${RP_SOURCE}" "${upload_path}"

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
  exit 0
fi

log "Uploading $(du -h "${upload_path}" | cut -f1) as ${RP_UPLOAD_NAME} → s3://${S3_BUCKET}/${S3_KEY}"
"${AWS}" s3 cp "${upload_path}" "s3://${S3_BUCKET}/${S3_KEY}" \
  --endpoint-url "${S3_ENDPOINT}" \
  --content-type "application/zip"

printf '%s  %s\n' "${local_sha}" "${RP_UPLOAD_NAME}" | "${AWS}" s3 cp - "s3://${S3_BUCKET}/${S3_MANIFEST_KEY}" \
  --endpoint-url "${S3_ENDPOINT}" \
  --content-type "text/plain"

archive_key="${ARCHIVE_PREFIX}/$(date +%Y%m%d-%H%M%S)-${RP_UPLOAD_NAME}"
log "Archive → s3://${S3_BUCKET}/${archive_key}"
"${AWS}" s3 cp "${upload_path}" "s3://${S3_BUCKET}/${archive_key}" \
  --endpoint-url "${S3_ENDPOINT}" \
  --content-type "application/zip"

log "Done. sha256=${local_sha}"
