#!/usr/bin/env bash

# Base paths
export BACKUP_BASE_DIR="/opt/backup-service"
export BACKUP_LOG_DIR="/var/log/backup-service"
export BACKUP_TMP_DIR="/tmp/backup-service"

# Nextcloud
export NEXTCLOUD_DATA_DIR="/var/www/nextcloud/data"
export NEXTCLOUD_CONFIG_DIR="/var/www/nextcloud/config"

# Database
export POSTGRES_HOST="localhost"
export POSTGRES_DB="nextcloud"
export POSTGRES_USER="nextcloud"

# Backup
export RESTIC_REPOSITORY="/mnt/backup/restic"
export RESTIC_PASSWORD_FILE="/etc/backup-service/restic.pass"

# Retention
export BACKUP_RETENTION_DAYS=7
export BACKUP_RETENTION_WEEKS=4
export BACKUP_RETENTION_MONTHS=6