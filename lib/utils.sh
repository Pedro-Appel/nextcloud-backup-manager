#!/usr/bin/env bash

# ==========================================
# Backup Service - Utility Functions
# ==========================================

#
# Check if a command exists
#
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

#
# Require a command to exist
#
require_command() {
    local cmd="$1"

    if ! command_exists "$cmd"; then
        log_error "Required command not found: $cmd"
        return 1
    fi
}

#
# Require root privileges
#
require_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "This script must be run as root"
        return 1
    fi
}

#
# Ensure a file exists
#
require_file() {
    local file="$1"

    [[ -f "$file" ]] || {
        log_error "File not found: $file"
        return 1
    }
}

#
# Ensure a directory exists
#
require_directory() {
    local dir="$1"

    [[ -d "$dir" ]] || {
        log_error "Directory not found: $dir"
        return 1
    }
}

#
# Create a directory if it doesn't exist
#
create_directory() {
    local dir="$1"

    mkdir -p "$dir" || {
        log_error "Failed to create directory: $dir"
        return 1
    }
}

is_dry_run() {
    [[ "${DRY_RUN:-false}" == "true" ]]
}

run() {
    if is_dry_run; then
        log_info "[DRY-RUN] $*"
        return 0
    fi

    "$@"
}