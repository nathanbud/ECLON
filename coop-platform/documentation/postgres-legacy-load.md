# Loading the legacy MySQL data into PostgreSQL

The PostgreSQL migration is deferred for the running app, but the data is being copied ahead of time so the new design can be tried against real rows.

## Target

- Container `eclon-postgres` (`infrastructure/docker/docker-compose.yml`), PostgreSQL 18, host port 5440, database `coop`, admin user `coop_admin`.
- Schema `legacy`: the old tables, names lowercased, nothing else changed. Created by the Flyway scripts in `database/postgres/migrations` (V001 to V005). `coop_app` gets SELECT on it (V005).
- Schema `core`: the new design (see `database/postgres/NAMING.md`). Not touched by the load.

## Source

MySQL 5.6 `iaccsbsucmpc01` on `localhost:3311`, read with the limited `coop_app` user. The password is in `db.txt`; pass it as `MYSQL_PWD` and never write it into a file.

## How the load works

`scripts/load-legacy-to-postgres.py <table> [...]` (needs `pymysql`; no PostgreSQL driver, it pipes into `docker exec -i eclon-postgres psql`):

1. Reads the column list from MySQL and truncates `legacy.<table>`.
2. Streams `SELECT *` with a server-side cursor, 20,000 rows at a time, into `\copy ... (format csv)`.
3. NULL is written as the token `~~NULL~~`; blobs as `\x` hex; MySQL zero dates (`0000-00-00...`) become NULL; NUL bytes in text are dropped.
4. Prints `table rowcount`, or `FAIL table error` and goes on to the next table.

Run it as a foreground or Claude background command, not with `nohup ... &`; a detached child is killed when the shell exits and the load stops partway.

```
export MYSQL_PWD="<coop_app password from db.txt>"
python -I scripts/load-legacy-to-postgres.py client access sldtl > load.log
```

## Verify

Row counts must match MySQL exactly, per table. `pg_stat_user_tables.n_live_tup` is not reliable right after a load; use `count(*)`. On the big tables (`sldtl` 51M, `transdt` 41M, `gltrans` 15M) a count takes tens of seconds.

## State

Checked on 2026-10-21: the exact `count(*)` of all 380 base tables in `legacy` equals MySQL (the other 5 of the 385 are `ecis_*`/extra tables that are not in the source list or were created by the app).

- Not loaded: `bsucmpc_client_financial_snapshot_hourly` (about 154M rows, a derived reporting table). Load it only if needed.
- The `ecis_*` tables were copied as plain data; the app's own writes still go to MySQL.

## Problems met (and how to avoid them)

- **Trust the PostgreSQL count, not the loader's printed count.** An interrupted `docker exec ... psql` can leave the in-container `COPY` waiting forever (`pg_stat_activity` shows `ClientRead`), so a table can print its full count yet hold 0 rows (`gltrans` did). Look for stale sessions and `pg_terminate_backend` them before reloading.
- **Run each table once.** A run killed partway and restarted loads the tables it already did a second time if the truncate is skipped; the loader truncates first, but a stuck session can still double rows (`denomsum` was doubled). The per-table count check catches this.
- **Column names are not always lowercase.** `branches."2024INVOICE_ACTIVATION"` kept its case in PostgreSQL; the loader now maps MySQL names to the actual PostgreSQL column names.

## Known gaps

- MySQL column types were converted by the table definitions already in `legacy`; the loader does not create tables.
- Indexes and foreign keys are not part of the load. Add the ones the app needs after loading.
