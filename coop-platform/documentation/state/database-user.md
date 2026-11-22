# Database user

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Database user**: the app runs as the limited MySQL user `coop_app` (SELECT everywhere; UPDATE on `client`; INSERT on `ecis_audit_log`; INSERT/UPDATE on `ecis_member_change` and `ecis_user_credential`; INSERT/DELETE on `ecis_user_role`; column-level UPDATE on 119 non-secret columns of `access` and the 19 permission columns of `access_trans`, never the password or one-time-code columns).
User account setup can be edited by administrators from the Users screen (not their own account; changing an administrator or a level needs a SUPERADMIN), with an audit row per field.
Its password is in `db.txt` as `app_password`.
Root is for administration only.
The MySQL server's `read_only` was switched OFF at runtime so the app can write; it reverts on a MySQL restart unless `my.ini` is changed.
New tables are in `coop-platform/database/migrations` (V001 to V003), applied by hand with the `mysql` client.

Since PostgreSQL V032 (MySQL V069, not applied) the app may also INSERT into `access` (with its AccessID sequence) and `access_trans`, to create users (Settings > Users > New user); it still never reads or writes the password or one-time-code columns.
