#!/usr/bin/env bash
source ${BACKUP_BASE_DIR}/lib/common.sh
source ${BACKUP_BASE_DIR}/lib/nextcloud.sh
source ${BACKUP_BASE_DIR}/lib/database.sh

common_init

database_load_config

echo "DB_TYPE     = $DB_TYPE"
echo "DB_HOST     = $DB_HOST"
echo "DB_HOSTNAME = $DB_HOSTNAME"
echo "DB_SOCKET   = $DB_SOCKET"
echo "DB_NAME     = $DB_NAME"
echo "DB_USER     = $DB_USER"