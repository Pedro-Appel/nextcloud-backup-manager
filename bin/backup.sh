#!/usr/bin/env bash

export BACKUP_BASE_DIR="/Users/irattiz/Documents/projects/backup-manager"
source ${BACKUP_BASE_DIR}/lib/common.sh

main() {
  common_init

  log_info "Backup service is alive (Phase 1 scaffold)"
  log_success "Initialization completed successfully"
}

main "$@"