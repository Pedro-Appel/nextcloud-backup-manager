#!/usr/bin/env bash

# =========================
# Backup Service Logger
# =========================

# Default log file (can be overridden by environment or config later)
LOG_FILE="${BACKUP_LOG_FILE:-${BACKUP_LOG_DIR}/backup-$(date +%F).log}"
LOG_LEVEL="${LOG_LEVEL:-INFO}"

# -------------------------
# Log level priorities
# -------------------------
declare -A LOG_PRIORITIES=(
    [DEBUG]=0
    [INFO]=1
    [SUCCESS]=2
    [WARN]=3
    [ERROR]=4
)
declare -A LOG_PHASE_START

# -------------------------
# Colors (disabled in non-interactive shells)
# -------------------------
if [[ -t 2 ]]; then
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

  # echo "$(_log_timestamp) [$level] $message" >> "$LOG_FILE"

  printf "%s [%-7s] %s\n" \
      "$(_log_timestamp)" \
      "$level" \
      "$message" >> "$LOG_FILE"
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
_log_blank() {
    printf "\n" >&2
    printf "\n" >> "$LOG_FILE"
}
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
  # echo -e "${color}$(_log_timestamp) [$level] $message${COLOR_RESET}" >&2
  printf "%b%s [%-9s] %s%b\n" \
    "$color" \
    "$(_log_timestamp)" \
    "$level" \
    "$message" \
    "$COLOR_RESET" >&2

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
# Visual separators
# -------------------------
log_banner() {
  echo "    ___    ____  ____  ________ "
  echo "   /   |  / __ \/ __ \/ ____/ / "
  echo "  / /| | / /_/ / /_/ / __/ / /  "
  echo " / ___ |/ ____/ ____/ /___/ /___"
  echo "/_/  |_/_/   /_/   /_____/_____/"
  _log_blank
}

log_section() {
  local title="$1"

  LOG_PHASE_START["$title"]="$(date +%s)"
  _log_blank
  log_info "============================================================"
  log_info "$title"
  log_info "============================================================"
}

log_summary() {
  _log_blank
  log_info "============================================================"
  log_info "Backup completed successfully"
  log_info "Snapshot : ${SNAPSHOT_ID}"
  log_info "Duration : ${DURATION}s"
  log_info "Host     : $(hostname)"
  log_info "Finished : $(date)"
  log_info "============================================================"
}

log_phase_change() {
  local title="$1"

  local end
  end=$(date +%s)

  local start="${LOG_PHASE_START[$title]:-}"

  if [[ -z "$start" ]]; then
      log_warn "Phase '$title' was never started."
      return
  fi

  local duration=$((end - start))

  log_debug "$title took: (${duration}s)"
}

# -------------------------
# Initialize logger
# -------------------------
log_init() {
  create_directory "$(dirname "$LOG_FILE")"

  if [[ ! -f "$LOG_FILE" ]]; then
    touch "$LOG_FILE"
  fi
}
