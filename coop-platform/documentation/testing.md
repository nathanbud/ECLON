# Testing and project conventions

## The test tiers

| Tier | What it needs | How to run |
|---|---|---|
| Rule and unit tests (backend) | nothing | `mvn test` without `DB_PASSWORD`: the database tests skip themselves |
| Screen tests (Vitest + Testing Library) | nothing | `npm test` in `apps/desktop` |
| Type-check and lint | nothing | `npx tsc -b`, `npm run lint` (errors fail, warnings do not); `npm run check` runs all three |
| Database flow tests | the MySQL schema and the `coop_app` user | `scripts/test-all.ps1` (reads the password from `db.txt`); they post real entries and **roll back** |
| Real-data tests | the full legacy data | tagged `realdata`; skip with `mvn test -DexcludedGroups=realdata` |
| Database flow tests on PostgreSQL | the PostgreSQL copy set up by `scripts/setup-postgres-app.ps1` | `SPRING_PROFILES_ACTIVE=postgres` with the same variables, see `documentation/state/postgresql-later.md` |
| Browser tests (Playwright) | backend on :8090 and `E2E_USER` / `E2E_PASSWORD` | `scripts/test-all.ps1 -E2e` |

`scripts/test-all.ps1` sets `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3`: every distinct Spring test context opens its own connection pool, and with the default size a full run exhausted the server's 151 connections ("Too many connections"). Keep the cap when running the suite by hand.

CI (`.github/workflows/ci.yml`) runs everything that needs no database: type-check, lint, screen tests, a production build, and the backend rule tests.

## Why the database tests still run on the working schema

`iaccs_sim` is not usable as a test copy: it is a 0.2 GB sample (5 clients, 15 loans, no general ledger) with none of the `ecis_` tables, while the tests look for real members, loans and balances and the real-data tests fit rules against the whole history (about 71 GB). **Blocked today by disk space:** the machine has about 16 GB free of 953 GB and the clone needs at least 75 GB, so free space (or an external drive) comes first. Isolating the tests needs a **full clone** of `iaccsbsucmpc01` (a data-directory copy while MySQL is stopped, or `mysqldump --single-transaction`), then `DB_NAME=<clone> scripts/migrate.ps1 -Schema <clone>`-style setup and `coop_app` grants on it. Until then every database test is `@Transactional` and rolls back; do not add a test that commits.

## Conventions

- **Audit rows** go through `ph.coop.core.audit.AuditLog.write(...)`, never a hand-written `INSERT INTO ecis_audit_log`. Never put secrets (passwords, fingerprint data) in a value.
- **Errors** thrown from controllers use `ph.coop.core.web.ApiErrors` (`bad`, `forbidden`, `notFound`, `conflict`): a message fit to show the user and the right status.
- **Migrations** are `database/migrations/V###_name.sql`, applied with `scripts/migrate.ps1` (it records each version in `ecis_schema_migration` and warns when an applied file changes). `-DryRun` lists what is pending; `-Baseline` records files as applied without running them. A migration is never edited after it is applied: add the next number.
- **Calling a controller from another bean:** a controller with `@PreAuthorize` is a proxy; share logic in a plain `@Component` and call only public methods across beans.
- **Screens:** server calls live in `src/api/<topic>.ts` (import from `./api`); amounts are formatted with `src/ui/format.ts`; shared dialogs in `src/ui/dialogs.tsx`; the member profile is `src/ClientProfilePage.tsx` plus `src/profile/*`; light-theme colours come from `LIGHT` in `glass.ts`, tile colours from `tint`/`tileFill` in `theme.tsx` (no new hard-coded hex).
- **Line endings** are LF in the repository (`.gitattributes`).
- **Versions** follow Semantic Versioning (MAJOR.MINOR.PATCH; 0.x while the system is pre-release). One number lives in four files (`package.json`, `tauri.conf.json`, `Cargo.toml`, and the backend's `pom.xml` as `<version>-SNAPSHOT` between releases): change them together with `scripts/bump-version.ps1 -Version x.y.z`, commit, then tag `vx.y.z`. The sign-in screen shows `Version x.y.z (build <commit>)`; the commit is read from git when the screen is built, and a trailing `+` means the build includes uncommitted changes (the build day is in the tooltip). Quote it when reporting a problem.
