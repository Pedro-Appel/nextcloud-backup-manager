#!/usr/bin/env bash

# ==========================================
# Backup Service - Config Loader
# ==========================================

# Prevent double loading
[[ -n "${CONFIG_LOADED:-}" ]] && return 0
CONFIG_LOADED=1

# ----------------------------
# Default config file
# ----------------------------
CONFIG_FILE="${BACKUP_CONFIG_FILE:-/etc/backup-service/backup.conf}"

# ----------------------------
# Load config file safely
# ----------------------------
config_load() {
  if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "[ERROR] Config file not found: $CONFIG_FILE" >&2
    return 1
  fi

  # shellcheck disable=SC1090
  source "$CONFIG_FILE"
}

# ----------------------------
# Validation helpers
# ----------------------------
config_require_var() {
  local name="$1"

  if [[ -z "${!name:-}" ]]; then
    echo "[ERROR] Missing required config: $name" >&2
    return 1
  fi
}

# ----------------------------
# Validate config values
# ----------------------------
config_validate() {
  
  config_require_var "BACKUP_MOUNT"
  config_require_var "RESTIC_REPOSITORY"

  # Nextcloud (Snap or other)
  config_require_var "NEXTCLOUD_DATA_DIR"
  config_require_var "NEXTCLOUD_CONFIG_DIR"

  # sanity checks
  [[ "$BACKUP_MOUNT" == /* ]] || return 1
  [[ "$NEXTCLOUD_DATA_DIR" == /* ]] || return 1
  [[ "$NEXTCLOUD_CONFIG_DIR" == /* ]] || return 1
}

config_autodetect_nextcloud() {
  # Snap detection fallback (only if user didn't define manually)
  if [[ -z "${NEXTCLOUD_DATA_DIR:-}" ]] && command -v snap >/dev/null 2>&1; then
    if snap list nextcloud >/dev/null 2>&1; then
      NEXTCLOUD_DATA_DIR="/var/snap/nextcloud/common/nextcloud/data"
      NEXTCLOUD_CONFIG_DIR="/var/snap/nextcloud/current/nextcloud/config"
    fi
  fi
}

# ----------------------------
# Public init
# ----------------------------
config_init() {
  config_load
  config_autodetect_nextcloud
  config_validate
}