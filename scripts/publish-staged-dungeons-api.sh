#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

dry_run=false
if [[ "${1:-}" == "--dry-run" ]]; then dry_run=true; shift; fi

[[ $# -ge 2 ]] || fail "Usage: publish-staged-maven.sh STAGING_ROOT RELATIVE_ARTIFACT_PATH..."
staging_root="$1"
shift
repo_url="https://repo.rus-crafting.ru/grocermc"
auth_url="https://repo.rus-crafting.ru/api/auth/me"
work_dir="$(mktemp -d)"
password=""
trap 'unset password; rm -rf "$work_dir"' EXIT
chmod 700 "$work_dir"

for relative in "$@"; do
  case "$relative" in
    ru/ruscrafting/arc/arc-onboarding-api/*|ru/ruscrafting/arc/arc-dungeons-api/*|ru/ruscrafting/arc/arc-aurora-api/*|ru/ruscrafting/arc/arc-aurora/*|ru/ruscrafting/arc/arc-ranks-api/*|ru/ruscrafting/arc/arc-crate-api/*) ;;
    *) fail "Only the declared ARC integration artifacts may be published by this helper" ;;
  esac
  case "$relative" in
    /*|*../*|../*|*\\*) fail "Artifact paths must be relative Maven repository paths" ;;
    *.jar|*.pom|*.module) ;;
    *) fail "Unsupported staged artifact type: $relative" ;;
  esac
  [[ "$relative" != *$'\n'* && "$relative" != *$'\r'* && "$relative" != *' '* ]] ||
    fail "Artifact paths cannot contain whitespace"
  [[ -s "$staging_root/$relative" ]] || fail "Staged artifact is missing or empty: $relative"
done

# Compare every coordinate anonymously before the first upload. Existing files
# are accepted only when their bytes already match the staged release.
missing_files=()
for relative in "$@"; do
  remote_file="$work_dir/remote"
  if ! status="$(curl --location --silent --show-error --output "$remote_file" --write-out '%{http_code}' --max-time 30 "${repo_url%/}/$relative")"; then
    fail "Could not inspect public artifact: $relative"
  fi
  case "$status" in
    200)
      local_sha="$(shasum -a 256 "$staging_root/$relative" | awk '{print $1}')"
      remote_sha="$(shasum -a 256 "$remote_file" | awk '{print $1}')"
      [[ "$local_sha" == "$remote_sha" ]] || fail "Immutable coordinate conflict: $relative"
      ;;
    404)
      missing_files+=("$relative")
      ;;
    *)
      fail "Could not inspect public artifact $relative (HTTP $status)"
      ;;
  esac
done

if $dry_run; then
  printf 'Validated %s immutable API files; %s missing. No credentials or uploads.\n' "$#" "${#missing_files[@]}"
  exit 0
fi

if [[ ${#missing_files[@]} -gt 0 ]]; then
  username="${REPOSILITE_PUBLISH_USERNAME:-arc-publisher}"
  password="${REPOSILITE_PUBLISH_PASSWORD:-}"
  if [[ -z "$password" ]] && command -v security >/dev/null 2>&1; then
    password="$(security find-generic-password -a "$username" -s ru.ruscrafting.reposilite.publisher -w 2>/dev/null || true)"
  fi
  [[ -n "$password" ]] || fail "Publisher credential is unavailable in the environment or macOS Keychain"
  case "$username$password" in
    *$'\n'*|*$'\r'*|*' '*) fail "Publisher credentials cannot contain spaces or newlines" ;;
  esac

  netrc_file="$work_dir/netrc"
  printf 'machine repo.rus-crafting.ru\nlogin %s\npassword %s\n' "$username" "$password" > "$netrc_file"
  chmod 600 "$netrc_file"
  unset password

  curl --fail --silent --show-error --max-time 30 --netrc-file "$netrc_file" "$auth_url" -o /dev/null ||
    fail "Reposilite publisher credential was rejected"

  for relative in "${missing_files[@]}"; do
    curl --fail --silent --show-error --max-time 120 --netrc-file "$netrc_file" \
      --upload-file "$staging_root/$relative" "${repo_url%/}/$relative"
  done
fi

# Verify anonymous reads of all versioned artifacts, including existing matches.
for relative in "$@"; do
  remote_file="$work_dir/verified"
  curl --fail --silent --show-error --location --max-time 120 \
    "${repo_url%/}/$relative" -o "$remote_file"
  local_sha="$(shasum -a 256 "$staging_root/$relative" | awk '{print $1}')"
  remote_sha="$(shasum -a 256 "$remote_file" | awk '{print $1}')"
  [[ "$local_sha" == "$remote_sha" ]] || fail "Anonymous readback checksum mismatch: $relative"
done

printf 'Published and verified %s immutable Maven files.\n' "$#"
