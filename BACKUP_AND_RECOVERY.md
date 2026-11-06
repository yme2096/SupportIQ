# Backup and recovery

No production database exists yet, so these procedures describe the first production deployment. The local Compose database and its named volume remain separate and unchanged.

## Render recovery options

Render's point-in-time recovery and retention depend on the database plan/workspace. Current Render documentation states that paid PostgreSQL plans support PITR, with a 3-day recovery window in Hobby workspaces and 7 days in Pro and higher workspaces; Free databases do not include PITR. On-demand exports are retained for 7 days. Confirm current eligibility and retention in the Render dashboard before relying on them. See [Render PostgreSQL backups](https://render.com/docs/postgresql-backups).

A point-in-time recovery creates a new database. Restore it alongside the original, verify it, update the backend and worker database references together, then deploy and smoke test. Keep the original until the recovered service is verified and the rollback window is over. Never overwrite or delete the only known-good database during recovery.

## Encrypted logical export

From a trusted admin workstation with PostgreSQL client tools installed, obtain the external connection URL securely from the Render dashboard and enter it into the masked password field of the credential prompt below. Do not put a credential in the command itself, a script, terminal transcript, or chat. Restrict access to the resulting dump and store it in durable encrypted storage outside Render.

```powershell
$env:PGDATABASE_URL = (Get-Credential -UserName 'database URL' -Message 'Enter the secured PostgreSQL URL in the password field').GetNetworkCredential().Password
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
pg_dump --format=custom --no-owner --no-acl --dbname=$env:PGDATABASE_URL --file="supportiq-$stamp.dump"
pg_restore --list "supportiq-$stamp.dump" | Select-Object -First 20
Remove-Item Env:PGDATABASE_URL
```

The external URL is only for this deliberate administrative export and may require a temporary trusted IP allow-list entry. Remove it after the export. Verify the dump with `pg_restore --list`; encrypt it at rest and retain according to your policy.

## Restore into a new database

1. Create a separate empty recovery database in the same region, with PostgreSQL 16 and pgvector available. Do not restore over the original.
2. Securely set `PGTARGET_URL` from the recovery database's connection details; do not echo it.
3. Restore the custom archive without source ownership/privilege assumptions:

```powershell
$env:PGTARGET_URL = (Get-Credential -UserName 'target database URL' -Message 'Enter the secured recovery URL in the password field').GetNetworkCredential().Password
pg_restore --no-owner --no-privileges --dbname=$env:PGTARGET_URL 'supportiq-<timestamp>.dump'
Remove-Item Env:PGTARGET_URL
```

4. Verify Flyway history, `vector` extension, company/document/conversation counts, and `/api/v1/health` against the recovery database. Exercise sign-in, a representative chat, and a document download before cutover.
5. Update both backend and worker to the recovered database, redeploy, and monitor logs, health, and worker backlog. Keep the prior database and dump until acceptance.

Perform a restore drill at least quarterly and after major database changes. A backup is not proven until a restore has been tested. Do not use `docker compose down -v`; that targets the local named volume.
