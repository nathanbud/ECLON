# Prepares the PostgreSQL copy (container eclon-postgres, database coop) so the application can run on it (SPRING_PROFILES_ACTIVE=postgres):
#   1. regenerates database/postgres/migrations V009 to V011 from the live MySQL schema (the app's ecis_ tables and the limited user's grants)
#   2. creates the ecis_ tables, copies their rows from MySQL, adds their foreign keys
#   3. applies the rest of the PostgreSQL migrations (grants, trigger, boolean flags, sequences, row locks, case-insensitive names)
#   4. sets the coop_app password to the app_password in db.txt and refreshes the planner statistics
# The old tables must already be in schema legacy (scripts/load-legacy-to-postgres.py, documentation/postgres-legacy-load.md, migrations V001 to V008).
# Safe to run again: it drops and recreates the ecis_ tables from MySQL, so run it when MySQL holds the current data and nothing is writing to PostgreSQL.
# Passwords are read from db.txt at run time and are not stored anywhere.
#   -SkipGenerate   use the migration files as they are (do not read MySQL's schema again)
param([switch]$SkipGenerate)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$dir = Join-Path $root 'coop-platform\database\postgres\migrations'
$db = Get-Content (Join-Path $root 'db.txt')
$appPw = (($db | Where-Object { $_ -match '^app_password' }) -replace '^app_password:\s*', '').Trim()

function Psql([string]$file) {
  Write-Host "Applying $(Split-Path $file -Leaf)"
  Get-Content $file -Raw | docker exec -i eclon-postgres psql -U coop_admin -d coop -v ON_ERROR_STOP=1 -q 2>&1 | Where-Object { "$_" -notmatch '^(NOTICE|psql:.*NOTICE)' }
  if ($LASTEXITCODE -ne 0) { throw "$file failed" }
}

if (-not $SkipGenerate) { python -I (Join-Path $PSScriptRoot 'gen-app-postgres.py'); if ($LASTEXITCODE -ne 0) { throw 'generating failed' } }

Psql (Join-Path $dir 'V009__app_tables.sql')
$tables = docker exec eclon-postgres psql -U coop_admin -d coop -Atc "select tablename from pg_tables where schemaname='legacy' and tablename like 'ecis%' order by 1"
$env:MYSQL_PWD = $appPw
$out = python -I (Join-Path $PSScriptRoot 'load-legacy-to-postgres.py') @($tables)
$out | Where-Object { $_ -match '^FAIL' } | ForEach-Object { throw $_ }
Write-Host "Copied $(@($tables).Count) ecis_ tables"
Remove-Item Env:\MYSQL_PWD

foreach ($f in 'V010__app_foreign_keys.sql', 'V011__app_grants.sql', 'V012__td_certificate_trigger.sql', 'V013__boolean_columns_to_smallint.sql', 'V014__sequences_after_load.sql', 'V015__row_locks_on_insert_only_tables.sql', 'V016__case_insensitive_names.sql') {
  Psql (Join-Path $dir $f)
}
"ALTER ROLE coop_app PASSWORD '$($appPw -replace "'", "''")';" | docker exec -i eclon-postgres psql -U coop_admin -d coop -q
docker exec eclon-postgres psql -U coop_admin -d coop -q -c 'ANALYZE'
Write-Host 'Done. Start the backend with SPRING_PROFILES_ACTIVE=postgres (DB_USER=coop_app, DB_PASSWORD from db.txt app_password).'
