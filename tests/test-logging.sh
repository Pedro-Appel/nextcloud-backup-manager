#!/usr/bin/env bash

set -Eeuo pipefail

source ./lib/logging.sh

LOG_LEVEL=DEBUG

log_init

log_debug "This is debug"
log_info "This is info"
log_success "This is success"
log_warn "This is warning"
log_error "This is error"