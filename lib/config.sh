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
  config_require_var "NEXTCLOUD_PATH"
  config_require_var "RESTIC_REPOSITORY"

  # Ensure paths are absolute
  [[ "$BACKUP_MOUNT" == /* ]] || {
    echo "[ERROR] BACKUP_MOUNT must be absolute path"
    return 1
  }

  [[ "$NEXTCLOUD_PATH" == /* ]] || {
    echo "[ERROR] NEXTCLOUD_PATH must be absolute path"
    return 1
  }
}

# ----------------------------
# Public init
# ----------------------------
config_init() {
  config_load
  config_validate
}