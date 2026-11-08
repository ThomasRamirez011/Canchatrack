#!/usr/bin/env bash
# Verifica el esquema SIN Maven ni Docker: aplica las migraciones Flyway en orden de versión sobre una base
# temporal y ejecuta src/test/resources/db/schema-invariants.sql dentro de una transacción que se revierte.
#
# Requiere un PostgreSQL >= 15 accesible mediante las variables estándar PGHOST, PGPORT, PGUSER, PGPASSWORD.
# Uso:  PGHOST=localhost PGUSER=canchatrack PGPASSWORD=... ./scripts/check-schema.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="canchatrack_schema_check_$$"
PSQL=(psql -X -q -v ON_ERROR_STOP=1)

"${PSQL[@]}" -d postgres -c "CREATE DATABASE ${DB}"
trap '"${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS ${DB}" >/dev/null' EXIT

for file in $(ls "${ROOT}"/src/main/resources/db/migration/V*.sql | sort -V); do
  echo "-> $(basename "${file}")"
  "${PSQL[@]}" -d "${DB}" --single-transaction -f "${file}"
done

echo "-> schema-invariants.sql (se revierte al final)"
{
  echo "BEGIN;"
  cat "${ROOT}/src/test/resources/db/schema-invariants.sql"
  echo "ROLLBACK;"
} | "${PSQL[@]}" -d "${DB}"

echo "OK: migraciones e invariantes verificadas"
