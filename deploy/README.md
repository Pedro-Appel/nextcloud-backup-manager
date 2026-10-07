# Deployment

## Manual installation

Build the self-contained JAR and install it with the configuration and systemd units. The service expects this layout:

```text
/opt/backup/
├── nextcloud-backup-manager.jar
└── config/backup.conf
```

Install the files and enable the timer:

```bash
./gradlew shadowJar
sudo install -d -m 0755 /opt/backup/config
sudo install -m 0644 build/libs/nextcloud-backup-manager-1.0.0-all.jar \
  /opt/backup/nextcloud-backup-manager.jar
sudo install -m 0600 config/backup.conf /opt/backup/config/backup.conf
sudo install -m 0644 deploy/nextcloud-backup.service /etc/systemd/system/
sudo install -m 0644 deploy/nextcloud-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now nextcloud-backup.timer
```

The service runs as root. The timer starts it every Sunday at 03:00 local time. `Persistent=false` means a missed activation is not run later. The service sets `RESTIC_PROGRESS_FPS=0.1`, which requests progress updates about every 10 seconds and overrides the config value.

Check the next activation with `systemctl list-timers nextcloud-backup.timer` and inspect logs with `journalctl -u nextcloud-backup.service`. To follow a run live:

```bash
journalctl -u nextcloud-backup.service -f
```

After editing the units, reload systemd. Use `systemd-analyze verify /etc/systemd/system/nextcloud-backup.service /etc/systemd/system/nextcloud-backup.timer` to check them on the target host.

## Jenkins deployment

The root `Jenkinsfile` expects an agent with Java 21, Git, `ssh`, and `scp`, along with Jenkins Pipeline, Git, Credentials Binding, and SSH Agent plugins. Configure the `staging-deploy` SSH alias and trusted host key. Add Git credential `git-checkout-key`, SSH credential `staging-ssh-key`, and protected Secret file credential `backup-env-file` containing the staging `backup.conf`.

The pipeline validates and dry-run checks `dev`, creates and validates a merge candidate on `staging`, deploys the JAR/config/units to the staging host, runs the staged JAR with `--dry-run`, then publishes the staging merge. After the production approval, it runs the privileged helper on the target host and publishes the merge to `main`.

The SSH account must be able to write the staging directory and run `/usr/local/sbin/deploy-nextcloud-backup` through non-interactive sudo. The helper at [`deploy-nextcloud-backup.sh`](deploy-nextcloud-backup.sh) is the deployment implementation: it refuses to run during an active backup, installs the staged JAR and config under `/opt/backup`, verifies and installs the systemd units, and enables/restarts the timer. It currently expects the deployment account home to be `/home/deploy` and the staged files under `/home/deploy/staging/backup-manager/app`; update those paths in the helper and Jenkins configuration together if the account or staging path changes.

Install the helper on the target host as a root-owned executable:

```bash
sudo install -o root -g root -m 0755 deploy/deploy-nextcloud-backup.sh \
  /usr/local/sbin/deploy-nextcloud-backup
sudo install -d -o root -g root -m 0755 /opt/backup/config
```

Allow only that helper through sudo. Replace `deploy` with the actual SSH account if needed:

```sudoers
deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-nextcloud-backup
```

Validate the sudoers file with `visudo -c`. The helper copies the config with mode `0600`; the staging credential and directory should also be protected.
