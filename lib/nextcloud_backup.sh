#!/usr/bin/env bash

# ==========================================
# Backup Service - Nextcloud Backup Module
# ==========================================

NEXTCLOUD_BACKUP_DIR="/var/snap/nextcloud/common/backups"
NEXTCLOUD_EXPORT_TIMEOUT="${NEXTCLOUD_EXPORT_TIMEOUT:-30}"

nextcloud_backup_init() {
    create_directory "$NEXTCLOUD_BACKUP_DIR"
}

nextcloud_backup_db() {
    if is_dry_run; then
        log_info "[DRY-RUN] Would export Nextcloud database"
        return 0
    fi
    log_info "Running Nextcloud database export..."

    local before
    before=$(ls -1 "$NEXTCLOUD_BACKUP_DIR" 2>/dev/null || true)

    if ! output=$(timeout "$NEXTCLOUD_EXPORT_TIMEOUT" nextcloud.export -b 2>&1); then
        log_error "Nextcloud DB export failed"
        return 1
    fi
    if grep -q "Waiting for MySQL" <<<"$output"; then
        log_error "MySQL did not become available during export. Try restarting with: sudo snap restart nextcloud.mysql"
        return 1
    fi

    local after new_file
    after=$(ls -1 "$NEXTCLOUD_BACKUP_DIR")

    new_file=$(comm -13 <(echo "$before") <(echo "$after") | tail -n 1)

    if [[ -z "$new_file" ]]; then
        log_warn "Could not detect new export file, using directory fallback"
        echo "$NEXTCLOUD_BACKUP_DIR"
        return 0
    fi

    NEW_NEXTCLOUD_DB_EXPORT="$NEXTCLOUD_BACKUP_DIR/$new_file"
    export NEW_NEXTCLOUD_DB_EXPORT

    log_info "Database export created: $NEW_NEXTCLOUD_DB_EXPORT"
}

nextcloud_cleanup_exports() {
    if is_dry_run; then
        log_info "[DRY-RUN] Would start Nextcloud cleaning exports"
        return 0
    fi
    log_info "Cleaning Nextcloud export files..."

    rm -rf "${NEXTCLOUD_BACKUP_DIR:?}/"*
}