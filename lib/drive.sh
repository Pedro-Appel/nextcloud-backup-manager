#!/usr/bin/env bash

# ==========================================
# Backup Service - Drive Module
# ==========================================

drive_is_mounted() {
  mountpoint -q "$BACKUP_MOUNT"
}

drive_mount() {
  if drive_is_mounted; then
    log_info "Drive already mounted: $BACKUP_MOUNT"
    return 0
  fi

  log_warn "Drive not mounted: $BACKUP_MOUNT"
  log_info "Attempting mount..."

  mkdir -p "$BACKUP_MOUNT"

  if mount "$BACKUP_MOUNT"; then
    log_success "Drive mounted: $BACKUP_MOUNT"
  else
    log_error "Failed to mount drive: $BACKUP_MOUNT"
    return 1
  fi
}

drive_check_writable() {
  local test_file="$BACKUP_MOUNT/.backup_write_test"

  if touch "$test_file" 2>/dev/null; then
    rm -f "$test_file"
    log_info "Drive is writable"
  else
    log_error "Drive is NOT writable"
    return 1
  fi
}

drive_check_space() {
  local available_kb
  available_kb=$(df -k "$BACKUP_MOUNT" | awk 'NR==2 {print $4}')

  # require at least 5GB free (adjust later if needed)
  local min_kb=$((5 * 1024 * 1024))

  if (( available_kb < min_kb )); then
    log_error "Insufficient space on backup drive"
    log_error "Available: ${available_kb}KB, Required: ${min_kb}KB"
    return 1
  fi

  log_info "Sufficient disk space available"
}

drive_validate() {
  log_info "Validating backup drive..."

  if ! drive_is_mounted; then
    drive_mount || return 1
  fi

  drive_check_writable || return 1
  drive_check_space || return 1

  log_success "Drive validation passed"
}