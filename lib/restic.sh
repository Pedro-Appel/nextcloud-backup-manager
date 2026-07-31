#!/usr/bin/env bash

restic_init() {
    export RESTIC_REPOSITORY
    export RESTIC_PASSWORD_FILE
}

restic_check() {
    require_command restic

    [[ -f "$RESTIC_PASSWORD_FILE" ]] || {
        log_error "Missing Restic password file"
        return 1
    }
}

restic_repository_exists() {
    restic snapshots >/dev/null 2>&1
}

restic_repository_init() {
    if restic_repository_exists; then
        log_info "Restic repository already initialized"
        return 0
    fi

    log_info "Initializing Restic repository..."

    restic init

    log_info "Repository initialized"
}

restic_unlock() {
    log_info "Unlocking repository..."

    restic unlock

    log_info "Repository unlocked"
}

restic_backup() {
    local args=()

    if is_dry_run; then
        args+=(--dry-run)
    fi

    (( $# > 0 )) || {
        log_error "No backup paths provided"
        return 1
    }

    log_info "Starting Restic backup..."

    restic backup "${args[@]}" "$@"

    log_info "Backup completed"
}

restic_check_repository() {
    log_info "Checking repository..."

    restic check

    log_info "Repository OK"
}

restic_retention() {
    log_info "Applying retention policy..."

    if is_dry_run; then
        log_info "[DRY-RUN] Would start restic forget --prune"
        return 0
    fi
    restic forget \
        --prune \
        --cache-dir "$RESTIC_CACHE_DIR" \
        --keep-daily "$RESTIC_RETENTION_DAILY" \
        --keep-weekly "$RESTIC_RETENTION_WEEKLY" \
        --keep-monthly "$RESTIC_RETENTION_MONTHLY"

    log_info "Retention complete"
}

restic_snapshots() {
    restic snapshots
}

restic_get_latest_snapshot() {
    restic snapshots --latest 1 --json \
        | jq -r '.[0].short_id'
}

restic_get_stats() {
    log_info $(restic stats latest)
}
