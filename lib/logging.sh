#!/usr/bin/env bash

# =========================
# Backup Service Logger
# =========================

# Default log file (can be overridden by environment or config later)
LOG_FILE="${LOG_FILE:-/var/log/backup-service/backup.log}"
LOG_LEVEL="${LOG_LEVEL:-INFO}"

# -------------------------
# Log level priorities
# -------------------------
declare -a LOG_PRIORITIES
LOG_PRIORITIES=(
  [DEBUG]=0
  [INFO]=1
  [SUCCESS]=2
  [WARN]=3
  [ERROR]=4
)

# -------------------------
# Colors (disabled in non-interactive shells)
# -------------------------
if [[ -t 1 ]]; then
  COLOR_RESET="\e[0m"
  COLOR_DEBUG="\e[90m"
  COLOR_INFO="\e[34m"
  COLOR_SUCCESS="\e[32m"
  COLOR_WARN="\e[33m"
  COLOR_ERROR="\e[31m"
else
  COLOR_RESET=""
  COLOR_DEBUG=""
  COLOR_INFO=""
  COLOR_SUCCESS=""
  COLOR_WARN=""
  COLOR_ERROR=""
fi

# -------------------------
# Internal: timestamp
# -------------------------
_log_timestamp() {
  date +"%Y-%m-%d %H:%M:%S"
}

# -------------------------
# Internal: write log
# -------------------------
_log_write() {
  local level="$1"
  local message="$2"

  echo "$(_log_timestamp) [$level] $message" >> "$LOG_FILE"
}

# -------------------------
# Internal: check level
# -------------------------
_log_should_log() {
  local level="$1"

  local current_priority="${LOG_PRIORITIES[${LOG_LEVEL:-INFO}]:-1}"
  local msg_priority=${LOG_PRIORITIES[$level]:-1}

  [[ $msg_priority -ge $current_priority ]]
}

# -------------------------
# Core logger
# -------------------------
_log() {
  local level="$1"
  local message="$2"

  if ! _log_should_log "$level"; then
    return 0
  fi

  local color=""
  case "$level" in
    DEBUG) color="$COLOR_DEBUG" ;;
    INFO) color="$COLOR_INFO" ;;
    SUCCESS) color="$COLOR_SUCCESS" ;;
    WARN) color="$COLOR_WARN" ;;
    ERROR) color="$COLOR_ERROR" ;;
  esac

  # Console output
  echo -e "${color}$(_log_timestamp) [$level] $message${COLOR_RESET}"

  # File output (no colors)
  _log_write "$level" "$message"
}

# -------------------------
# Public API
# -------------------------
log_debug()   { _log "DEBUG" "$*"; }
log_info()    { _log "INFO" "$*"; }
log_success() { _log "SUCCESS" "$*"; }
log_warn()    { _log "WARN" "$*"; }
log_error()   { _log "ERROR" "$*"; }

# -------------------------
# Initialize logger
# -------------------------
log_init() {
  mkdir -p "$(dirname "$LOG_FILE")"

  if [[ ! -f "$LOG_FILE" ]]; then
    touch "$LOG_FILE"
  fi

  log_info "Logger initialized"
  log_info "Log file: $LOG_FILE"
  log_info "Log level: $LOG_LEVEL"
}