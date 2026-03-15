#!/bin/bash
# ============================================================
# RentAxis — Fix Database Table Permissions
# ============================================================
# Run this after Liquibase migrations create new tables.
# Grants all privileges to the app DB user on all tables.
#
# Usage:
#   chmod +x infra/fix-db-permissions.sh
#   ./infra/fix-db-permissions.sh              # local dev (postgres/postgres)
#   ./infra/fix-db-permissions.sh prod         # production
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV="${1:-dev}"

if [ "$ENV" = "prod" ] && [ -f "$SCRIPT_DIR/envs/prod.env" ]; then
    set -a
    source "$SCRIPT_DIR/envs/prod.env"
    set +a
    DB_HOST="${DB_HOST:-postgres}"
    DB_PORT="${DB_PORT:-5432}"
    DB_NAME="${POSTGRES_DB:-rentaxis}"
    DB_USER="${POSTGRES_USER:-rentaxis}"
    DB_SUPERUSER="${DB_SUPERUSER:-postgres}"
    echo "Environment: PRODUCTION"
else
    DB_HOST="localhost"
    DB_PORT="5432"
    DB_NAME="rentaxis"
    DB_USER="postgres"
    DB_SUPERUSER="postgres"
    echo "Environment: LOCAL DEV"
fi

echo "================================================"
echo "  RentAxis — Fix Database Permissions"
echo "================================================"
echo ""
echo "Host:       $DB_HOST:$DB_PORT"
echo "Database:   $DB_NAME"
echo "App User:   $DB_USER"
echo "Superuser:  $DB_SUPERUSER"
echo ""

# Run as superuser to grant permissions
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_SUPERUSER" -d "$DB_NAME" <<SQL
-- Grant all current table/sequence privileges to the app user
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $DB_USER;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO $DB_USER;

-- Set default privileges so future tables also get correct permissions
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO $DB_USER;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO $DB_USER;

-- Also grant to superuser if different from app user
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $DB_SUPERUSER;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO $DB_SUPERUSER;

SELECT 'Permissions fixed for user: $DB_USER' AS result;
SQL

echo ""
echo "✓ Database permissions fixed."
echo "  All existing and future tables are accessible by '$DB_USER'."
echo ""
