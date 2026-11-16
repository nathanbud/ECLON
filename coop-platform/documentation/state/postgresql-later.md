# PostgreSQL: the app can run on it

The backend runs on MySQL by default and on PostgreSQL with `SPRING_PROFILES_ACTIVE=postgres` (`application-postgres.yml`: container `eclon-postgres`, port 5440, database `coop`, user `coop_app` with the same password as the MySQL `app_password` in `db.txt`).
Same code, same SQL; the engine is chosen only by the address. The Settings > System > Database connection screen builds addresses for whichever engine the server started on (`ConnectionRules.engineOf`).

## Where things are in PostgreSQL

- Everything is in schema **`legacy`**: the old tables (copied by `scripts/load-legacy-to-postgres.py`, see `documentation/postgres-legacy-load.md`) and the app's own `ecis_*` tables, as in MySQL where they share one schema. The address sets `currentSchema=legacy`, so unqualified names work. Names are lowercase; PostgreSQL folds the mixed-case names in the SQL (`ClientIDBrCode`) to match, and result sets are read case-insensitively.
- The JDBC address also sets `stringtype=unspecified` (a text value sent for a date or number column is accepted, as MySQL does) and the session time zone `Asia/Manila`.
- The old Spring/Hibernate settings are unchanged: `ddl-auto: none`, standard naming, dialect `PostgreSQLDialect` in the profile.

## Setting it up (or refreshing it)

`scripts/setup-postgres-app.ps1` does it all and can be repeated: it regenerates `database/postgres/migrations` V009 to V011 from the live MySQL schema (`scripts/gen-app-postgres.py`: the 84 `ecis_*` tables with keys and indexes, foreign keys, and the limited user's grants), copies the `ecis_*` rows, applies V012 to V016, sets the `coop_app` password and runs `ANALYZE`. The old tables must already be loaded (V001 to V008).
**A new MySQL migration needs the same treatment**: apply it to MySQL as usual, then rerun the setup script (it rebuilds the `ecis_*` side from MySQL, so do it while nothing writes to PostgreSQL).

| Migration | What it does |
|---|---|
| V009, V010 | the `ecis_*` tables (tinyint to smallint, datetime to timestamp, enum to varchar, AUTO_INCREMENT to identity) and their foreign keys, generated |
| V011 | the limited user's grants, generated from `SHOW GRANTS` of both MySQL accounts (`coop_app@%` and `@localhost`); `INSERT, UPDATE (cols)` is INSERT on the table and UPDATE on those columns |
| V012 | the `update_td_certno` trigger (certificate number = reference number) |
| V013 | the old tables' flags were copied as PostgreSQL `boolean`; the code reads and writes 0/1 numbers (`COALESCE(flag, 0) = 0`, `getInt`), so they are `smallint` again |
| V014 | every sequence continues after the highest id (the copy left them at 1, so the first insert collided) |
| V015 | row locks on insert-only tables (see below) |
| V016 | case-insensitive names (see below) |

## Differences from MySQL that the code now deals with

- **Row locks need UPDATE.** `SELECT ... FOR UPDATE` (used to hand out the next control or reference number) needs the UPDATE privilege in PostgreSQL, not in MySQL. The posted ledgers are insert-only for the app, so for `transsum`, `slmastersum`, `sc`, `sd`, `td`, `loan`, `sldtl`, `osl`, `hold` and `ecis_inventory_txn` it gets UPDATE plus a `deny_update` statement trigger that turns any real UPDATE into a permission error (SQLSTATE 42501). Locking works; changing posted rows still does not. A new insert-only table that is locked with `FOR UPDATE` must be added to the list in V015 (and the audit tables must never be).
- **No lock through an outer join.** `FOR UPDATE` on a query with a `LEFT JOIN` is refused, so those loads lock the row alone first (`AssetDisposalController`, `PurchaseOrderController`, `LoanApplicationWorkflowController`).
- **Case.** MySQL compares these columns without regard to case; PostgreSQL does not. `client.lname/fname/mname/sname/accountname` and `access.accessusername` have the nondeterministic collation `legacy.ci` (still `varchar`, so reads are unchanged; `LIKE` needs PostgreSQL 18). Other text comparisons are exact; the front office search lowercases both sides.
- **Bare parameters.** `(? IS NULL OR col = ?)` is written `(CAST(? AS CHAR) IS NULL OR col = ?)` because PostgreSQL cannot type a bare null parameter; `CONCAT_WS(.., ?)` was replaced by joining in Java.
- **Row order.** MySQL returns rows in primary-key order, PostgreSQL in whatever order they sit; a read that needs an order must say `ORDER BY` (one was missing in `GoodsReceiptController`).
- `Db` helpers cover the rest: generated keys (the column name is lowercased), table and column checks, privilege checks (`to_regclass` first, so an unknown table is "no"), `textColumnLengths` (case-insensitive map).
- MySQL-only SQL still lives only in `ph.coop.core.db.Db` (`PortableSqlTest`). The tests' own SQL was made portable too (no `REGEXP`, `DATABASE()`, `CURDATE()`, `HAVING` on an alias).

## Running the tests on PostgreSQL

```
DB_USER=coop_app DB_PASSWORD=<app_password from db.txt> SPRING_PROFILES_ACTIVE=postgres SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3 DEV_API_USER=dev DEV_API_PASSWORD=dev mvn test -DexcludedGroups=realdata
```

## Still open before PostgreSQL becomes the system of record

- **The old tables are a copy**: counts matched MySQL on 2026-11-06 for every table except a few ledger rows (`sldtl` 2, `transdt` 4, `gltrans` 4, `actvtlog` 13 behind). Do a full reload (`documentation/postgres-legacy-load.md`) at cutover. `LOAD_DELETE=1` refreshes a small table that other tables point at, without dropping its keys. `client.Height/Weight/BloodType` are not in PostgreSQL (V008).
- **Member name search is a scan** in PostgreSQL (the prefix `LIKE` cannot use a btree with the case-insensitive collation): fine at 100k members, add a trigram or `lower()` index if it slows down. The MySQL `idx_client_fname` has no PostgreSQL twin yet.
- Some reads may still lack an `ORDER BY` that MySQL gave for free; the test suite found one. The two MySQL routines `number_to_words` and `RemoveSpecialCharacters` are not ported (no Java code calls them).
- Migrations and backups now work on PostgreSQL: `scripts/migrate-postgres.ps1` (the main path; V017 onward; V001 to V016 are baselined; records in `legacy.ecis_pg_migration`) and `scripts/backup-app-data.ps1` (PostgreSQL by default, `-Engine mysql` for the old one). `migrate.ps1` is MySQL only. Flyway is still not used.
- **Member name search** was timed on 2026-11-08: about 30 ms warm on 100,614 members (0.9 s the first, cold time), so no index was added; revisit if membership grows a lot.
- Browser tests: `API_PROXY=http://localhost:9097 npx playwright test` (in `apps/desktop`, backend started with the postgres profile on 9097) passed the 2 sign-in tests; the 13 signed-in tests need `E2E_USER` / `E2E_PASSWORD` and have not been run on PostgreSQL. A hand check of the screens has not been done.
