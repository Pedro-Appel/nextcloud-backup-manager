#!/usr/bin/env bash

# ==========================================
# Backup Service - Common Initialization
# ==========================================

# Strict mode (IMPORTANT for production scripts)
set -Eeo pipefail

# ----------------------------
# Project base paths
# ----------------------------
# export BACKUP_BASE_DIR="/opt/backup-service"
export BACKUP_BASE_DIR="/Users/irattiz/Documents/projects/backup-manager"
export BACKUP_LIB_DIR="${BACKUP_BASE_DIR}/lib"
export BACKUP_BIN_DIR="${BACKUP_BASE_DIR}/bin"
export BACKUP_TEST_DIR="${BACKUP_BASE_DIR}/tests"

# export BACKUP_CONFIG_DIR="/etc/backup-service"
export BACKUP_CONFIG_DIR=${BACKUP_BASE_DIR}
export BACKUP_CONFIG_FILE="${BACKUP_CONFIG_DIR}/backup.conf"

# export BACKUP_LOG_DIR="/var/log/backup-service"
export BACKUP_LOG_DIR="."
export BACKUP_LOG_FILE="${BACKUP_LOG_DIR}/backup.log"

# export BACKUP_STATE_DIR="/var/lib/backup-service"
export BACKUP_STATE_DIR="."
# ----------------------------
# Load libraries (order matters)
# ----------------------------

# Logging must be first so everything can log safely
export LOG_LEVEL=INFO
source "${BACKUP_LIB_DIR}/logging.sh"
source "${BACKUP_LIB_DIR}/config.sh"
# Configuration (will evolve in next step)
# source "${BACKUP_LIB_DIR}/config.sh"

# Utilities (placeholder for now)
# source "${BACKUP_LIB_DIR}/utils.sh"

# ----------------------------
# Initialize system directories
# ----------------------------
common_init_directories() {
  mkdir -p "$BACKUP_LOG_DIR"
  mkdir -p "$BACKUP_STATE_DIR"

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

# ----------------------------
# Main initializer
# ----------------------------
common_init() {
  common_init_directories
  config_init
  log_info "======================================="
  log_info " Backup Service starting"
  log_info " Base: $BACKUP_BASE_DIR"
  log_info " Log : $BACKUP_LOG_FILE"
  log_info "======================================="

  common_init_logging
  log_info "Config loaded: $CONFIG_FILE"
  log_info "Backup mount: $BACKUP_MOUNT"
}