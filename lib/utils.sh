#!/usr/bin/env bash

function generate_correlation_id() {
    echo "bkp-$(date +%s)-$RANDOM"
}

function require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Missing required command: $1"
        exit 1
    fi
}

function ensure_dir() {
    local dir="$1"
    mkdir -p "$dir"
}