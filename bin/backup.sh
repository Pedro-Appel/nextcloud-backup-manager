#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_BASE_DIR="$(dirname "$SCRIPT_DIR")"

source "${BACKUP_BASE_DIR}/lib/common.sh"

main() {
  START_TIME=$(date +%s)
  SNAPSHOT_ID=""
  notifier_start
  common_init

  trap nextcloud_cleanup EXIT
  log_banner
  
  log_section "Environment Validation"
  nextcloud_check
  drive_validate
  log_phase_change "Environment Validation"

  log_section "Consistency boundary"
  nextcloud_maintenance_enable
  log_phase_change "Consistency boundary"

  log_section "Database export (Snap)"
  nextcloud_backup_db
  log_phase_change "Database export (Snap)"

  log_section "Restic backup"
  restic_repository_init
  restic_unlock

  BACKUP_PATHS=(
    "$NEXTCLOUD_DATA_DIR"
    "$NEXTCLOUD_CONFIG_DIR"
    "$NEXTCLOUD_BACKUP_DIR"
  )
  
  restic_backup "${BACKUP_PATHS[@]}"
  log_phase_change "Restic backup"

  log_section "Retention policy"
  restic_retention
  log_phase_change "Retention policy"

  log_section "Cleanup Snap exports ONLY"
  nextcloud_cleanup_exports
  log_phase_change "Cleanup Snap exports ONLY"

  log_section "End consistency boundary"
  nextcloud_maintenance_disable
  log_phase_change "End consistency boundary"

  export END_TIME=$(date +%s)
  export DURATION=$((END_TIME - START_TIME))
  export SNAPSHOT_ID="$(restic_get_latest_snapshot)"
  notifier_success "Backup completed successfully" "$SNAPSHOT_ID" "$DURATION"
  log_summary
  restic_get_stats
}

main "$@"
