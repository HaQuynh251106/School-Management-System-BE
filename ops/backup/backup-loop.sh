#!/bin/sh
set -eu

interval="${BACKUP_INTERVAL_SECONDS:-86400}"
retention="${BACKUP_RETENTION_DAYS:-30}"

case "$interval" in *[!0-9]*|'') echo "BACKUP_INTERVAL_SECONDS must be an integer" >&2; exit 2;; esac
case "$retention" in *[!0-9]*|'') echo "BACKUP_RETENTION_DAYS must be an integer" >&2; exit 2;; esac

mkdir -p /backups

while true; do
  stamp="$(date -u +%Y%m%d-%H%M%S)"
  file="/backups/${PGDATABASE}-${stamp}.dump"
  tmp="${file}.partial"

  echo "[$(date -u +%FT%TZ)] creating PostgreSQL backup ${file}"
  if pg_dump --format=custom --compress=9 --no-owner --no-privileges --file="$tmp"; then
    pg_restore --list "$tmp" >/dev/null
    mv "$tmp" "$file"
    sha256sum "$file" >"${file}.sha256"
    printf '{"database":"%s","createdAt":"%s","verified":true,"sizeBytes":%s}\n' \
      "$PGDATABASE" "$(date -u +%FT%TZ)" "$(wc -c <"$file")" >"${file}.json"
    find /backups -type f -mtime "+$retention" \( -name '*.dump' -o -name '*.sha256' -o -name '*.json' \) -delete
    echo "[$(date -u +%FT%TZ)] backup verified"
  else
    rm -f "$tmp"
    echo "[$(date -u +%FT%TZ)] backup failed" >&2
  fi

  sleep "$interval"
done
