#!/usr/bin/env bash

# ==========================================
# Backup Service - Common Initialization
# ==========================================

# Strict mode (IMPORTANT for production scripts)
set -Eeo pipefail

# ----------------------------
# Project base paths
# ----------------------------
today=$(date +%F)

export BACKUP_LIB_DIR="${BACKUP_BASE_DIR}/lib"
export BACKUP_BIN_DIR="${BACKUP_BASE_DIR}/bin"
export BACKUP_TEST_DIR="${BACKUP_BASE_DIR}/tests"

export BACKUP_CONFIG_DIR="${BACKUP_BASE_DIR}/config"
export BACKUP_CONFIG_FILE="${BACKUP_CONFIG_DIR}/backup.conf"

export BACKUP_LOG_DIR="${BACKUP_BASE_DIR}/log"
export BACKUP_LOG_FILE="${BACKUP_LOG_DIR}/backup-${today}.log"
export LOG_LEVEL="${LOG_LEVEL:-INFO}"

export BACKUP_STATE_DIR="."
# ----------------------------
# Load libraries (order matters)
# ----------------------------

# Logging must be first so everything can log safely
export LOG_LEVEL=INFO
source "${BACKUP_LIB_DIR}/utils.sh"
source "${BACKUP_LIB_DIR}/logging.sh"
source "${BACKUP_LIB_DIR}/config.sh"
source "${BACKUP_LIB_DIR}/drive.sh"
source "${BACKUP_LIB_DIR}/nextcloud.sh"
source "${BACKUP_LIB_DIR}/nextcloud_backup.sh"
source "${BACKUP_LIB_DIR}/restic.sh"

# ----------------------------
# Initialize system directories
# ----------------------------
common_init_directories() {
  create_directory "$BACKUP_LOG_DIR"
  create_directory "$BACKUP_STATE_DIR"

  # Ensure log file exists
  touch "$BACKUP_LOG_FILE"
}

# ----------------------------
# Initialize logging
# ----------------------------
common_init_logging() {
  export LOG_FILE="$BACKUP_LOG_FILE"
  export LOG_LEVEL="${LOG_LEVEL:-INFO}"

  log_init
}

notifier_init() {
  source "${BACKUP_LIB_DIR}/notifier.sh"
}

# ----------------------------
# Main initializer
# ----------------------------
common_init() {
  
  common_init_directories
  config_init
  log_section "Configuration"
  log_debug "Base: $BACKUP_BASE_DIR"
  common_init_logging
  log_section "Logger"
  log_debug "Log file: $LOG_FILE"
  log_debug "Log level: $LOG_LEVEL"
  log_debug "Config loaded: $CONFIG_FILE"
  log_debug "Backup mount: $BACKUP_MOUNT"
  log_debug "Loading notifier..."
  log_section "Initialization"
  nextcloud_backup_init
  log_debug "NextCloud Backup initiated"
  log_section "Restic"
  restic_init
  restic_check
  log_debug "Restic Initiated"
}
