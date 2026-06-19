#!/usr/bin/env bash
set -Eeuo pipefail

COMPOSE_SERVICE="${COMPOSE_SERVICE:-postgres}"
DB_NAME="${DB_NAME:-minecraft_builds}"
DB_USER="${DB_USER:-minecraft}"

usage() {
  cat <<EOF
Usage: $0 snapshot.dump --yes

Restore a custom-format pg_dump snapshot into the local Docker Compose Postgres DB.
This is destructive for objects in database '${DB_NAME}'.

Environment overrides:
  COMPOSE_SERVICE  Compose service name (default: postgres)
  DB_NAME          Database name (default: minecraft_builds)
  DB_USER          Database user (default: minecraft)
EOF
}

dump_path=""
confirmed="false"

for arg in "$@"; do
  case "$arg" in
    -h|--help)
      usage
      exit 0
      ;;
    --yes)
      confirmed="true"
      ;;
    *)
      if [[ -z "$dump_path" ]]; then
        dump_path="$arg"
      else
        usage >&2
        exit 2
      fi
      ;;
  esac
done

if [[ -z "$dump_path" ]]; then
  usage >&2
  exit 2
fi

if [[ ! -f "$dump_path" ]]; then
  echo "Snapshot file not found: ${dump_path}" >&2
  exit 1
fi

if [[ "$confirmed" != "true" ]]; then
  cat >&2 <<EOF
Refusing to restore without --yes.

This will run pg_restore --clean against database '${DB_NAME}' in Compose service '${COMPOSE_SERVICE}'.
EOF
  exit 1
fi

echo "Restoring ${dump_path} into ${DB_NAME} on Compose service ${COMPOSE_SERVICE}"
docker compose exec -T "$COMPOSE_SERVICE" pg_restore \
  -U "$DB_USER" \
  -d "$DB_NAME" \
  --clean \
  --if-exists \
  --no-owner \
  --no-privileges \
  --exit-on-error \
  < "$dump_path"

echo "Restore complete: ${dump_path}"
