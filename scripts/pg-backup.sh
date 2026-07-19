#!/usr/bin/env bash
#
# pg-backup.sh — logical backup of the Factory PostgreSQL database (DATA-2 / backup-dr.md).
#
# A portable, restore-testable safety net that complements (does NOT replace) PITR/WAL
# archiving on the primary. A starting point — wire it into your scheduler/secrets manager
# and ship the output to versioned, access-controlled, off-box object storage.
#
# Reads the same FACTORY_DB_* env vars the application uses. The password is passed via
# PGPASSWORD for the child pg_dump only; it is never logged or written to disk.
#
# Usage:
#   FACTORY_DB_URL=jdbc:postgresql://host:5432/factory \
#   FACTORY_DB_USER=factory FACTORY_DB_PASSWORD=... \
#   scripts/pg-backup.sh [output_dir]
#
# Restore (into an EMPTY database — never over a live prod DB):
#   createdb factory_restore
#   pg_restore --no-owner --clean --if-exists -d factory_restore <dump-file>
#   # then start the app with FACTORY_DB_URL pointing at factory_restore and verify
#   # Flyway reports no pending migrations (see doc/backup-dr.md restore drill).

set -euo pipefail

OUT_DIR="${1:-./backups}"

# Parse host/port/db out of the JDBC URL (jdbc:postgresql://host:port/db[?params]),
# falling back to the app defaults.
JDBC_URL="${FACTORY_DB_URL:-jdbc:postgresql://localhost:5432/factory}"
hostport_db="${JDBC_URL#jdbc:postgresql://}"
hostport_db="${hostport_db%%\?*}"
HOSTPORT="${hostport_db%%/*}"
DB_NAME="${hostport_db##*/}"
DB_HOST="${HOSTPORT%%:*}"
DB_PORT="${HOSTPORT##*:}"
[ "$DB_PORT" = "$DB_HOST" ] && DB_PORT=5432   # no explicit port in the URL

DB_USER="${FACTORY_DB_USER:-factory}"

command -v pg_dump >/dev/null 2>&1 || { echo "ERROR: pg_dump not found on PATH" >&2; exit 1; }

mkdir -p "$OUT_DIR"
# Timestamp is intentionally UTC and second-precision so filenames sort chronologically.
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_FILE="${OUT_DIR}/factory-${DB_NAME}-${STAMP}.dump"

echo "Backing up ${DB_NAME} on ${DB_HOST}:${DB_PORT} as ${DB_USER} -> ${OUT_FILE}"

# -Fc = custom format (compressed, restorable with pg_restore, allows selective restore).
PGPASSWORD="${FACTORY_DB_PASSWORD:-factory}" pg_dump \
  --host="$DB_HOST" --port="$DB_PORT" --username="$DB_USER" \
  --format=custom --no-owner --file="$OUT_FILE" "$DB_NAME"

echo "Backup complete: $(du -h "$OUT_FILE" | cut -f1) -> ${OUT_FILE}"
echo "Next: ship ${OUT_FILE} to off-box, access-controlled storage (see doc/backup-dr.md)."
