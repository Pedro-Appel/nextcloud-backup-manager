#!/usr/bin/env bash

# ==========================================
# Backup Service - Nextcloud Module
# ==========================================

NEXTCLOUD_OCC="${NEXTCLOUD_OCC:-/snap/bin/nextcloud.occ}"
NEXTCLOUD_OCC_TIMEOUT="${NEXTCLOUD_OCC_TIMEOUT:-30}"

#
# Verify Nextcloud OCC exists
#
nextcloud_check() {
    [[ -x "$NEXTCLOUD_OCC" ]] || {
        log_error "nextcloud.occ not found: $NEXTCLOUD_OCC"
        return 1
    }
}

#
# Execute an OCC command with timeout
#
nextcloud_occ() {
    local output
    local rc

    log_debug "Running: nextcloud.occ $*"

    output=$(
        timeout "$NEXTCLOUD_OCC_TIMEOUT" \
            "$NEXTCLOUD_OCC" "$@" 2>&1
    )

    rc=$?

    case "$rc" in
        0)
            [[ -n "$output" ]] && log_debug "$output"
            printf '%s\n' "$output"
            return 0
            ;;

        124)
            log_error "nextcloud.occ timed out after ${NEXTCLOUD_OCC_TIMEOUT}s"
            return 124
            ;;

        *)
            log_error "nextcloud.occ failed"
            [[ -n "$output" ]] && log_error "$output"
            return "$rc"
            ;;
    esac
}

#
# Enable maintenance mode
#
nextcloud_maintenance_enable() {
    log_info "Enabling Nextcloud maintenance mode..."

    nextcloud_occ maintenance:mode --on >/dev/null ||
        return 1

    log_success "Maintenance mode enabled"
}

#
# Disable maintenance mode
#
nextcloud_maintenance_disable() {
    log_info "Disabling Nextcloud maintenance mode..."

    nextcloud_occ maintenance:mode --off >/dev/null ||
        return 1

    log_success "Maintenance mode disabled"
}

#
# Check maintenance mode
#
nextcloud_is_maintenance() {
    nextcloud_occ maintenance:mode 2>/dev/null |
        grep -q "enabled"
}

#
# Get status
#
nextcloud_status() {
    nextcloud_occ status
}

#
# Get version
#
nextcloud_version() {
    nextcloud_occ status |
        awk -F': ' '/version:/ {print $2}'
}

#
# Get configured data directory
#
nextcloud_data_directory() {
    nextcloud_occ config:system:get datadirectory
}

#
# Cleanup handler
#
nextcloud_cleanup() {
    if nextcloud_is_maintenance; then
        log_warn "Maintenance mode still enabled. Disabling..."

        if ! nextcloud_maintenance_disable; then
            log_error "Unable to disable maintenance mode"
        fi
    fi
}