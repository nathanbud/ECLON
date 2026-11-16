# Release readiness

What exists, what is still open, and what each open item needs. Review before the first real go-live.

## In place

| Area | State |
|---|---|
| Backup of the new application's data | `scripts/backup-app-data.ps1` dumps every `ecis_` table and copies the biometric key to `backups/<date>/` (git-ignored), keeps the last 14. Tested: 65 tables. The legacy tables are the old system's data and are backed up as the database. |
| Restore | Dump: `mysql <schema> < ecis-<schema>.sql` with the root account, then `scripts/migrate.ps1 -DryRun` (the migration record is in the dump, so nothing should be pending). Key: put `biometric.key` back at `backend/coop-core/data/` (or set `coop.biometric.key`). Rehearse this on a spare schema before relying on it. |
| Schema changes | `scripts/migrate.ps1` records each version and checksum in `ecis_schema_migration`. |
| Automated checks | CI runs type-check, lint, screen tests (including accessibility checks with axe), a production build and the backend rule tests; the database tests run with `scripts/test-all.ps1`. |
| Audit | Every change goes through `AuditLog`; secrets and fingerprint data never enter it. |
| Secrets | Database passwords are read from `db.txt` at run time; the biometric key comes from the environment or a key file outside git. |

## Open, needs a decision or a resource

| Item | What it needs |
|---|---|
| Tests on a copy of the database | At least 75 GB of free disk for a full clone (16 GB free now). |
| Sign-in | HTTP Basic with the credential held in the browser tab. Keycloak is planned: it needs a server, realm and role mapping (the app's roles `ADMIN`, `MEMBERSHIP_OFFICER`, the `PERM_*` flags and the ecis role table map to Keycloak roles) and a decision on single sign-on for the desktop windows. |
| Backups off this machine | Schedule `backup-app-data.ps1` (Task Scheduler, daily) and copy `backups/` somewhere else; a backup on the same disk is not a backup. |
| Encryption key | Move the biometric key from the dev key file into the environment or a key service before real fingerprints are enrolled; back it up separately from the database. |
| Real fingerprint scanner | Scanner SDK, local agent and a certified ISO/IEC 19794-2 matcher (the simulator must never enrol real members). |
| Logs | Confirm no personal data (names, IDs, fingerprints) reaches the application log at INFO; add redaction if it does. Central log shipping is not set up. |
| Monitoring | Only `/actuator/health`. Add uptime checks, error-rate and slow-query alerts. |
| Accounting decisions | Accounts for purchases, stock, POS revenue and cost, and input VAT block supplier invoices, payables and ledger entries for POS and stock. |
| PostgreSQL | The legacy data is copied; the app still uses MySQL. Switching needs the PostgreSQL branches in `Db` tested and a full-volume rehearsal. |
