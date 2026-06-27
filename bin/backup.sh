#!/usr/bin/env bash

export BACKUP_BASE_DIR="/Users/irattiz/Documents/projects/backup-manager"
source ${BACKUP_BASE_DIR}/lib/common.sh

main() {
  common_init
  log_success "Initialization completed successfully"
  trap nextcloud_cleanup EXIT
  log_success "Maintianance mode started successfully"
  nextcloud_check
  log_success "Nextcloud verification completed"
  database_load_config
  log_success "Database configurations completed"
  database_dump
  log_success "Database dump completed"
}

main "$@"