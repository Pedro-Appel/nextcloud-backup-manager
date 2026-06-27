# Backup Service (Home LAB) Implementation Plan

## Goal

Build a modular backup service for the Ubuntu Server hosting Nextcloud
that is reliable, testable and fully automatable with systemd timers.

## Objectives

- External drive management and monitoring
- Nextcloud maintenance mode management
- PostgreSQL dump
- Restic backup management
- Incremental snapshots only
- Backup verification
- Structured logging

## Future Enhancements

- Nextcloud health checks
- Tailscale health checks
- Notifications
- Offsite replication
- Restore validation

## Directory Layout

```text
/opt/backup-service/
├── bin/
├── lib/
└── README.md

/etc/backup-service/
└── backup.conf

/var/log/backup-service/

/var/lib/backup-service/
```

## Modules

### logging.sh

Responsibilities: - Log levels - File logging - Journal integration

### drive.sh

Responsibilities: - Mount by UUID - Verify mount - Verify writable -
Check free space - SMART health

### nextcloud.sh

Responsibilities: - Enable maintenance mode - Disable maintenance mode -
Verify installation

### postgres.sh

Responsibilities: - Dump database - Compress dump - Verify dump -
Cleanup temporary files

### restic.sh

Responsibilities: - Initialize repository - Backup - Retention -
Snapshot verification - Repository check - Restore helpers

## Backup Workflow

1.  Load configuration
2.  Initialize logging
3.  Verify external drive
4.  Verify free space
5.  Verify drive health
6.  Enable Nextcloud maintenance
7.  Dump PostgreSQL
8.  Run Restic backup
9.  Apply retention policy
10. Verify snapshot
11. Disable maintenance mode
12. Cleanup
13. Finish

A cleanup trap should always disable maintenance mode and remove
temporary files.

## Configuration

Configuration will be stored in `/etc/backup-service/backup.conf`.

Items include: - Mount point - Drive UUID - Restic repository - Restic
password file - Nextcloud path - PostgreSQL settings - Retention
policy - Log directory

## Milestones

### Phase 1

Project scaffolding and logging

### Phase 2

External drive mounting and monitoring

### Phase 3

Nextcloud maintenance module

### Phase 4

PostgreSQL backup module

### Phase 5

Restic module

### Phase 6

Main orchestrator

### Phase 7

Systemd service and timer

### Phase 8

Health routines and enhancements

## Phase 2 Detailed Tasks

- Identify external drive
- Record UUID
- Format if required
- Create mount point
- Configure `/etc/fstab`
- Test automatic mounting
- Verify permissions
- Verify SMART
- Test read/write
- Simulate missing drive

## Testing Strategy

Each module must be tested independently before integration.

After integration: - Successful backup - Failed backup - Missing drive -
Database failure - Restic failure - Automatic cleanup

## Logging

Every operation should include: - Timestamp - Level - Module - Message

## Success Criteria

The project is complete when: - A daily unattended backup runs
successfully. - Only incremental snapshots are stored. - Logs clearly
show every stage. - Failures leave the system in a consistent state. - A
full restore procedure has been documented and tested.
