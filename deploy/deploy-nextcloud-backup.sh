#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
    echo "This command accepts no arguments" >&2
    exit 2
fi

# Refuse deployment while a backup is running.
if /usr/bin/systemctl is-active --quiet nextcloud-backup.service; then
    echo "Backup is currently running; refusing deployment" >&2
    exit 1
fi

stage=/home/deploy/staging/backup-manager/app
service="$stage/nextcloud-backup.service"
timer="$stage/nextcloud-backup.timer"

for file in "$service" "$timer"; do
    [ -f "$file" ] || {
        echo "Missing regular file: $file" >&2
        exit 1
    }
    [ ! -L "$file" ] || {
        echo "Refusing symbolic link: $file" >&2
        exit 1
    }
done

/usr/bin/install -m 0644 \
    "$stage/nextcloud-backup-manager.jar" \
    /opt/backup/.nextcloud-backup-manager.jar.new

/usr/bin/install -m 0600 \
    "$stage/config/backup.conf" \
    /opt/backup/config/.backup.conf.new

/usr/bin/mv -f \
    /opt/backup/.nextcloud-backup-manager.jar.new \
    /opt/backup/nextcloud-backup-manager.jar

/usr/bin/mv -f \
    /opt/backup/config/.backup.conf.new \
    /opt/backup/config/backup.conf

/usr/bin/systemd-analyze verify "$service" "$timer"

/usr/bin/install -o root -g root -m 0644 \
    "$service" /etc/systemd/system/nextcloud-backup.service
/usr/bin/install -o root -g root -m 0644 \
    "$timer" /etc/systemd/system/nextcloud-backup.timer

/usr/bin/systemctl daemon-reload
/usr/bin/systemctl enable nextcloud-backup.timer
/usr/bin/systemctl restart nextcloud-backup.timer
/usr/bin/systemctl --no-pager list-timers nextcloud-backup.timer