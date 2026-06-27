#!/usr/bin/env bash

# Base paths
export BACKUP_BASE_DIR="."
export BACKUP_LOG_DIR="."
export BACKUP_TMP_DIR="."

# Nextcloud
export NEXTCLOUD_DATA_DIR="."
export NEXTCLOUD_CONFIG_DIR="."

# Database
export POSTGRES_HOST="localhost"
export POSTGRES_DB="nextcloud"
export POSTGRES_USER="nextcloud"

# Backup
export RESTIC_REPOSITORY="."
export RESTIC_PASSWORD_FILE="."

# Retention
export BACKUP_RETENTION_DAYS=7
export BACKUP_RETENTION_WEEKS=4
export BACKUP_RETENTION_MONTHS=6