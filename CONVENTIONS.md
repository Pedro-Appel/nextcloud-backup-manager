# Conventions

Coding patterns and style rules observed throughout this project. New code should follow these
conventions to stay consistent with the existing codebase.

---

## Shell Script Basics

### Interpreter and strict mode

Every file starts with:

```bash
#!/usr/bin/env bash
set -euo pipefail
```

`bin/backup.sh` and test files use `set -euo pipefail`.  
`lib/common.sh` uses `set -Eeo pipefail` (adds `-E` so `ERR` traps are inherited by subshells).  
All library files are sourced, so they rely on the caller's strict mode — do not add `set` inside
library files.

### File header comment

Every library file opens with a consistent header block:

```bash
# ==========================================
# Backup Service - Module Name
# ==========================================
```

---

## Naming

### Variables

- All caps, underscore-separated for globals and config values: `BACKUP_MOUNT`, `LOG_LEVEL`.
- Lowercase, underscore-separated for locals inside functions: `dump_file`, `available_kb`.
- Declare locals explicitly with `local`:

```bash
my_function() {
    local result
    result="$(some_command)"
}
```

### Functions

- Lowercase, underscore-separated, prefixed with the module name:
  - `drive_validate`, `drive_mount`, `drive_check_space`
  - `log_info`, `log_debug`, `log_section`
  - `restic_backup`, `restic_retention`
  - `nextcloud_occ`, `nextcloud_maintenance_enable`
- Internal (private) helpers are prefixed with an underscore: `_log`, `_log_write`,
  `_log_timestamp`, `_validate`.

---

## Error Handling

### Return codes

Functions signal failure by returning a non-zero exit code. Because `set -e` is active, callers
do not need to check `$?` unless they want to handle the failure explicitly.

```bash
drive_check_writable() {
    touch "$test_file" 2>/dev/null || return 1
}
```

### Guarded returns with `||`

The preferred idiom for aborting on a condition is:

```bash
[[ -n "$device" ]] || {
    log_error "Device not found"
    return 1
}
```

Avoid multi-line `if/fi` blocks for simple guard clauses.

### No silent failures

Every error path calls `log_error` before returning. Do not return 1 without logging.

---

## Logging

Use the module-level logging API from `logging.sh`. Never use raw `echo` in library code.

| Function | Use for |
|---|---|
| `log_debug` | Implementation detail, verbose tracing |
| `log_info` | Normal operational messages |
| `log_success` | Confirmation of a completed step |
| `log_warn` | Recoverable or unexpected conditions |
| `log_error` | Fatal or failure conditions |

Use `log_section` at the start of each major phase and `log_phase_change` at the end:

```bash
log_section "Database export (Snap)"
nextcloud_backup_db
log_phase_change "Database export (Snap)"
```

The string passed to both must match exactly — `log_phase_change` looks up the start time by name.

---

## Dry-Run Support

Any function that writes data, mounts a drive, or calls an external service must check
`is_dry_run` and skip the destructive action:

```bash
if is_dry_run; then
    log_info "[DRY-RUN] Would do X"
    return 0
fi
```

The `[DRY-RUN]` prefix in the log message is the convention for marking skipped actions.

Restic backup passes `--dry-run` directly to the CLI instead of skipping the call, so the
output is still visible in dry-run mode.

---

## Timeout Wrapping

All external CLI calls that could hang (notably `nextcloud.occ` and `nextcloud.export`) are
wrapped with `timeout`:

```bash
timeout "$NEXTCLOUD_OCC_TIMEOUT" "$NEXTCLOUD_OCC" "$@"
```

Timeout duration should be a named variable from config, not a hardcoded literal.

---

## Double-Source Prevention

Modules that should only be loaded once use a guard variable at the top:

```bash
[[ -n "${CONFIG_LOADED:-}" ]] && return 0
CONFIG_LOADED=1
```

Not all modules need this; use it when re-sourcing would have side effects (e.g., config loading).

---

## Config Loading

- All runtime values live in `config/backup.conf`.
- The config file is plain `KEY=VALUE` pairs, sourced directly by `config.sh`.
- Required variables are validated by `config_require_var`; the script aborts early if they are
  missing.
- Path variables must be absolute paths; `config_validate` enforces this with `[[ "$VAR" == /* ]]`.
- Snap-specific defaults are auto-detected by `config_autodetect_nextcloud` and only applied if
  the user has not set the variable explicitly.

---

## Library Source Order

When sourcing libraries manually (e.g., in tests), respect the dependency chain:

```
utils.sh → logging.sh → config.sh → drive.sh → nextcloud.sh → nextcloud_backup.sh → restic.sh
```

`utils.sh` and `logging.sh` have no dependencies and must come first. `common.sh` handles this
automatically for production code.

---

## Tests

Test scripts are standalone Bash scripts under `tests/`. They source the library they are
testing, set up minimal required variables, and call functions directly. There is no test
framework — tests pass or fail based on exit codes.

- Always set `BACKUP_BASE_DIR` before sourcing any library:

```bash
export BACKUP_BASE_DIR=/path/to/nextcloud-backup-manager
source "${BACKUP_BASE_DIR}/lib/common.sh"
```

- Test files follow the same shebang/strict-mode convention as production scripts.
- Tests are named `test-<module>.sh`.
