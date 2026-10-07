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
