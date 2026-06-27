#!/usr/bin/env bash

# ==========================================
# Backup Service - Database Module
# ==========================================

DATABASE_DUMP_DIR="${DATABASE_DUMP_DIR:-$BACKUP_STATE_DIR/database}"

database_load_config() {
    DB_TYPE="$(nextcloud_occ config:system:get dbtype)"
    DB_NAME="$(nextcloud_occ config:system:get dbname)"
    DB_USER="$(nextcloud_occ config:system:get dbuser)"
    DB_PASSWORD="$(nextcloud_occ config:system:get dbpassword)"
    DB_HOST="$(nextcloud_occ config:system:get dbhost)"

    if [[ "$DB_HOST" == *:* ]]; then
        DB_HOSTNAME="${DB_HOST%%:*}"
        DB_SOCKET="${DB_HOST#*:}"
    else
        DB_HOSTNAME="$DB_HOST"
        DB_SOCKET=""
    fi
}

database_init() {
    mkdir -p "$DATABASE_DUMP_DIR"
}

database_dump_file() {
    printf "%s/%s_%s.sql.gz" \
        "$DATABASE_DUMP_DIR" \
        "$DB_NAME" \
        "$(date +%Y%m%d_%H%M%S)"
}

database_dump() {
    local dump_file
    dump_file="$(database_dump_file)"

    local cmd=(
        /snap/nextcloud/current/bin/mysqldump
        --single-transaction
        --quick
        --lock-tables=false
        -u "$DB_USER"
        "-p$DB_PASSWORD"
        "$DB_NAME"
    )

    if [[ -n "$DB_SOCKET" ]]; then
        cmd+=(--socket "$DB_SOCKET")
    else
        cmd+=(-h "$DB_HOSTNAME")
    fi

    log_info "Creating MySQL dump..."

    if "${cmd[@]}" | gzip > "$dump_file"; then
        [[ -s "$dump_file" ]] || {
            log_error "Database dump is empty"
            return 1
        }

        DATABASE_LAST_DUMP="$dump_file"
        export DATABASE_LAST_DUMP

        log_success "Database dumped to $dump_file"
        return 0
    fi

    log_error "Database dump failed"
    return 1
}

database_cleanup() {
    [[ -n "${POSTGRES_LAST_DUMP:-}" ]] && rm -f "$POSTGRES_LAST_DUMP"
}