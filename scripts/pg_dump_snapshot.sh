#!/usr/bin/env bash
set -Eeuo pipefail

COMPOSE_SERVICE="${COMPOSE_SERVICE:-postgres}"
DB_NAME="${DB_NAME:-minecraft_builds}"
DB_USER="${DB_USER:-minecraft}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-snapshots/postgres}"

usage() {
  cat <<EOF
Usage: $0 [output.dump]

Create a custom-format pg_dump snapshot of the local Docker Compose Postgres DB.

Environment overrides:
  COMPOSE_SERVICE  Compose service name (default: postgres)
  DB_NAME          Database name (default: minecraft_builds)
  DB_USER          Database user (default: minecraft)
  SNAPSHOT_DIR     Default output directory (default: snapshots/postgres)
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 2
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
output_path="${1:-${SNAPSHOT_DIR}/${DB_NAME}-${timestamp}.dump}"
output_dir="$(dirname "$output_path")"

mkdir -p "$output_dir"

echo "Writing Postgres snapshot to ${output_path}"
docker compose exec -T "$COMPOSE_SERVICE" pg_dump \
  -U "$DB_USER" \
  -d "$DB_NAME" \
  -Fc \
  --no-owner \
  --no-privileges \
  > "$output_path"

bytes="$(wc -c < "$output_path" | tr -d ' ')"
echo "Snapshot complete: ${output_path} (${bytes} bytes)"
