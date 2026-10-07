# Java systemd deployment

Build the self-contained JAR with `./gradlew shadowJar`, then install it under the stable name
expected by the service. Put the matching `config/backup.conf` in `/opt/backup/config/`.

```bash
sudo install -d /opt/backup/config
sudo install -m 0644 build/libs/nextcloud-backup-manager-1.0.0-all.jar \
  /opt/backup/nextcloud-backup-manager.jar
sudo install -m 0600 config/backup.conf /opt/backup/config/backup.conf
sudo install -m 0644 deploy/nextcloud-backup.service /etc/systemd/system/
sudo install -m 0644 deploy/nextcloud-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now nextcloud-backup.timer
```

The service runs as root at 02:00 daily. Check the next scheduled run with
`systemctl list-timers nextcloud-backup.timer` and inspect output with
`journalctl -u nextcloud-backup.service`. Follow formatted Restic progress live with:

```bash
journalctl -u nextcloud-backup.service -f
```

The service sets `RESTIC_PROGRESS_FPS=0.033333`, which requests approximately one progress
message every 30 seconds. The environment setting overrides `RESTIC_PROGRESS_FPS` from
`config/backup.conf`. For example, use `0.1` for an update every 10 seconds or `0.016666` for an
update every minute. After changing the service unit, reload systemd before the next run:

```bash
sudo systemctl daemon-reload
sudo systemctl restart nextcloud-backup.service
```

Verify the unit files on the target Ubuntu host with
`systemd-analyze verify /etc/systemd/system/nextcloud-backup.service /etc/systemd/system/nextcloud-backup.timer`.

## Jenkins setup

The root `Jenkinsfile` expects an agent with Java 21, Git, `ssh`, and `scp`, plus the Pipeline, Git,
Credentials Binding, and SSH Agent plugins. Configure the `staging-deploy` SSH alias and trusted
host key on that agent. Add the named Git and SSH private-key credentials, and add `backup-env-file`
as a protected Secret file containing the staging `backup.conf`. The SSH account used for
deployment must have non-interactive `sudo` permission for `/opt/backup` installation and the
optional systemd commands.

### Privileged deployment helper

Use a root-owned helper when the pipeline account must install updated unit files without general
sudo access. On the target host, create `/usr/local/sbin/deploy-nextcloud-backup` as an
administrator. Replace `/home/deploy` if the deployment account has a different home directory.

```sh
#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
    echo "This command accepts no arguments" >&2
    exit 2
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

/usr/bin/systemd-analyze verify "$service" "$timer"
/usr/bin/install -o root -g root -m 0644 \
    "$service" /etc/systemd/system/nextcloud-backup.service
/usr/bin/install -o root -g root -m 0644 \
    "$timer" /etc/systemd/system/nextcloud-backup.timer

/usr/bin/systemctl daemon-reload
/usr/bin/systemctl enable nextcloud-backup.timer
/usr/bin/systemctl restart nextcloud-backup.timer
/usr/bin/systemctl --no-pager list-timers nextcloud-backup.timer
```

Confirm the absolute command paths with `command -v systemctl systemd-analyze deploy-nextcloud-backup`, then make
the helper root-owned and executable:

```bash
sudo chown root:root /usr/local/sbin/deploy-nextcloud-backup
sudo chmod 0755 /usr/local/sbin/deploy-nextcloud-backup
```

Create the sudoers rule with `sudo visudo -f /etc/sudoers.d/nextcloud-backup-deploy`:

```sudoers
deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-nextcloud-backup
```

Validate the rule and invoke the helper non-interactively from the pipeline:

```bash
sudo chmod 0440 /etc/sudoers.d/nextcloud-backup-deploy
sudo visudo -c
ssh staging-deploy 'sudo -n /usr/local/sbin/deploy-nextcloud-backup'
```

Keep the helper and sudoers file writable only by root. The pipeline still controls code executed
as root through both the unit definition and the deployed JAR. For stronger privilege separation,
keep the units root-controlled and run the service as a dedicated backup account with only the
required filesystem permissions.
