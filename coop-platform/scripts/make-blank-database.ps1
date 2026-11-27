# Makes the blank database file a new cooperative starts from (coop-blank.dump): every table, index, trigger and grant the application uses, the record of
# database changes, the rows those changes add for every cooperative, and one branch. No members, entries, users, products or settings of any cooperative.
# It is restored with pg_restore on a new server (documentation/guides/windows-server-setup.md, Part 5) or a test database (guides/neon-test-database.md).
#
# How: the structure of the up-to-date database coop (Docker container eclon-postgres) is dumped WITHOUT data into a scratch database in the same container,
# the rows below are added, the result is checked to hold no cooperative's data, dumped in pg_dump's custom format, and the scratch database is dropped.
# Nothing in coop is changed. Reached as the container's admin account through docker exec, no password.
#
#   -Out         the file to write (default backups\blank\coop-blank-<yyyyMMdd>.dump under the repository; backups\ is git-ignored)
#   -BranchCode  the branch's number (default 1)
#   -BranchName  the branch's name (default "Main office"; changeable later)
#   -Sample      a TEST cooperative instead (coop-sample-<yyyyMMdd>.dump): the blank database plus the standard lists of Starter setup and
#                database/sample/sample-cooperative.sql (member types, office, department, member defaults, share capital and regular savings with their chart
#                accounts and links, account opening), so members can be registered and approved at once. Branch 1. Never for a real cooperative.
#
# The rows added are the ones the database changes insert for every cooperative (V004 roles, V028 passbook layout, V029 distribution policy per branch,
# V042 closing settings); the ones that depend on a cooperative's own setup (V031, V037, V043) add nothing on a blank database. A newer change that inserts
# rows is reported, so it can be added here.
param([string]$Out, [int]$BranchCode = 1, [string]$BranchName = 'Main office', [switch]$Sample)
$ErrorActionPreference = 'Continue'
$env:PGCLIENTENCODING = 'UTF8'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$migrations = Join-Path $root 'coop-platform\database\postgres\migrations'
$starter = Join-Path $root 'coop-platform\backend\coop-core\src\main\resources\starter'
$sampleSql = Join-Path $root 'coop-platform\database\sample\sample-cooperative.sql'
if ($Sample -and $BranchCode -ne 1) { throw 'The sample cooperative is set up for branch 1 only.' }
$kind = if ($Sample) { 'sample' } else { 'blank' }
if (-not $Out) { $Out = Join-Path $root ("backups\blank\coop-$kind-" + (Get-Date -Format 'yyyyMMdd') + '.dump') }
$KNOWN_SEEDS_UP_TO = 46
$container = 'eclon-postgres'
$build = 'coop_blank_build'

function Invoke-Pg([string[]]$PgArgs, [string]$What) {
  $o = docker exec $container @PgArgs 2>&1
  if ($LASTEXITCODE -ne 0) { $o | Select-Object -Last 15 | ForEach-Object { Write-Host "  $_" }; throw "$What failed." }
  return $o
}
function Invoke-Sql([string]$Db, [string]$Sql, [string]$What) {
  $o = $Sql | docker exec -i $container psql -U coop_admin -d $Db -v ON_ERROR_STOP=1 -1 -q -At 2>&1
  if ($LASTEXITCODE -ne 0) { $o | Select-Object -Last 15 | ForEach-Object { Write-Host "  $_" }; throw "$What failed." }
  return $o
}

$running = docker inspect -f '{{.State.Running}}' $container 2>$null
if ($running -ne 'true') { throw "The container $container is not running. Open Docker Desktop and start it (docker start $container), then run this again." }

# 1. coop must have every database change in this repository, or the blank file would be missing some.
$files = Get-ChildItem $migrations -Filter 'V*__*.sql' | Sort-Object Name
$logged = Invoke-Pg @('psql', '-U', 'coop_admin', '-d', 'coop', '-At', '-c', 'SELECT version FROM legacy.ecis_pg_migration') 'Reading the change record of coop'
$missing = $files | Where-Object { ($_.Name -split '__')[0] -notin $logged }
if ($missing) {
  throw ("coop does not have these database changes yet: " + (($missing | ForEach-Object Name) -join ', ') + ". Apply them first (scripts\migrate-postgres.ps1 -Database coop), then run this again.")
}
$newSeeds = $files | Where-Object { [int](($_.Name -split '__')[0].Substring(1)) -gt $KNOWN_SEEDS_UP_TO -and (Select-String -Path $_.FullName -Pattern 'insert\s+into' -Quiet) }
foreach ($f in $newSeeds) { Write-Warning "$($f.Name) inserts rows. Check whether they belong in a blank database and add them to this script; then raise KNOWN_SEEDS_UP_TO." }

# 2. The structure of coop, without data, into a scratch database.
Write-Host 'Copying the structure of coop (no data)...'
Invoke-Pg @('sh', '-c', "rm -f /tmp/blank-schema.dump /tmp/blank.dump; dropdb -U coop_admin --if-exists $build") 'Clearing an earlier scratch database' | Out-Null
Invoke-Pg @('pg_dump', '-U', 'coop_admin', '-d', 'coop', '--schema-only', '--no-owner', '-Fc', '-f', '/tmp/blank-schema.dump') 'Dumping the structure of coop' | Out-Null
try {
  Invoke-Pg @('createdb', '-U', 'coop_admin', '-O', 'coop_admin', '-E', 'UTF8', '-T', 'template0', $build) 'Creating the scratch database' | Out-Null
  Invoke-Pg @('pg_restore', '-U', 'coop_admin', '-d', $build, '--no-owner', '--role=coop_admin', '--exit-on-error', '/tmp/blank-schema.dump') 'Restoring the structure' | Out-Null

  # 3. The change record and the roles, copied as they are in coop.
  Write-Host 'Adding the change record, the roles, the branch and the starting settings...'
  Invoke-Pg @('sh', '-c', "pg_dump -U coop_admin -d coop --data-only -t legacy.ecis_pg_migration -t core.role | psql -U coop_admin -d $build -v ON_ERROR_STOP=1 -1 -q") 'Copying the change record and roles' | Out-Null

  # 4. The branch and the rows the changes add for every cooperative (values as in V028, V029 and V042).
  $name = $BranchName.Replace("'", "''")
  if ($name.Length -gt 45) { throw 'The branch name can have at most 45 characters.' }
  $seed = @"
INSERT INTO legacy.branches (branchesid, branchesdesc, address, manager, po_contactname, contactnumbers, tin, pos_serialnumber, pos_min, emailaddress)
VALUES ($BranchCode, '$name', '', '', '', '', '', '', '', '');
INSERT INTO legacy.ecis_passbook_layout VALUES (22, 5.5, 8, 31, 0.55, 0.2, 16, 0.35, 8, 0.15, 0.85, 2.65, 3.6, 4.65, 4.75, 5.1, 0, 0, 'setup', now());
INSERT INTO legacy.ecis_distribution_policy (branch_code, patronage_events, updated_by, updated_at) VALUES ($BranchCode, '23,24,31', 'setup', now());
INSERT INTO legacy.ecis_setting (setting_key, setting_value, updated_by, updated_at)
VALUES ('accounting.monthly_close', 'false', 'system', CURRENT_TIMESTAMP), ('accounting.year_end_due_days', '120', 'system', CURRENT_TIMESTAMP);
"@
  Invoke-Sql $build $seed 'Adding the branch and starting rows' | Out-Null
  $allowed = @('legacy.ecis_pg_migration', 'core.role', 'legacy.branches', 'legacy.ecis_passbook_layout', 'legacy.ecis_distribution_policy', 'legacy.ecis_setting')

  # 4b. The sample cooperative: Starter setup's standard lists (in its load order, as Settings > System > Starter setup adds them to a blank database),
  #     then the sample setup.
  if ($Sample) {
    Write-Host 'Adding the standard lists and the sample setup...'
    $lists = Get-Content (Join-Path $starter 'lists.txt') | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { ($_ -split '\|')[0].Trim() }
    Invoke-Pg @('sh', '-c', 'rm -rf /tmp/starter && mkdir -p /tmp/starter') 'Preparing the lists' | Out-Null
    docker cp (Join-Path $starter 'lists\.') "${container}:/tmp/starter" | Out-Null
    docker cp $sampleSql "${container}:/tmp/starter/sample-cooperative.sql" | Out-Null
    $copy = ($lists | ForEach-Object { "\copy legacy.$_ FROM '/tmp/starter/$_.csv' WITH (FORMAT csv, HEADER)" }) -join "`n"
    Invoke-Sql $build $copy 'Adding the standard lists' | Out-Null
    Invoke-Pg @('psql', '-U', 'coop_admin', '-d', $build, '-v', 'ON_ERROR_STOP=1', '-1', '-q', '-f', '/tmp/starter/sample-cooperative.sql') 'Adding the sample setup' | Out-Null
    $allowed += $lists | ForEach-Object { "legacy.$_" }
    $allowed += @('legacy.clienttype', 'legacy.restrik', 'legacy.dept', 'legacy.coa', 'legacy.sltype', 'legacy.glcontrol', 'legacy.ecis_sl_control')
  }

  # 5. Check: only the tables filled above hold rows (counted exactly, every table of every schema).
  $check = @"
DO `$`$
DECLARE t record; n bigint; bad text := '';
BEGIN
  FOR t IN SELECT schemaname, tablename FROM pg_tables WHERE schemaname IN ('legacy', 'core', 'public') LOOP
    EXECUTE format('SELECT count(*) FROM %I.%I', t.schemaname, t.tablename) INTO n;
    IF n > 0 AND (t.schemaname || '.' || t.tablename) NOT IN ('$($allowed -join "','")') THEN bad := bad || ' ' || t.schemaname || '.' || t.tablename || '=' || n; END IF;
  END LOOP;
  IF bad <> '' THEN RAISE EXCEPTION 'tables with rows that should be empty:%', bad; END IF;
END `$`$;
SELECT count(*) FROM legacy.ecis_pg_migration;
SELECT count(*) FROM pg_tables WHERE schemaname IN ('legacy', 'core');
"@
  $counts = @(Invoke-Sql $build $check 'Checking that the blank database holds no cooperative data')

  # 6. The file.
  New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
  Invoke-Pg @('pg_dump', '-U', 'coop_admin', '-d', $build, '--no-owner', '-Fc', '-f', '/tmp/blank.dump') 'Dumping the blank database' | Out-Null
  docker cp "${container}:/tmp/blank.dump" $Out | Out-Null
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Out)) { throw 'Copying the file out of the container failed.' }
} finally {
  docker exec $container sh -c "rm -rf /tmp/blank-schema.dump /tmp/blank.dump /tmp/starter; dropdb -U coop_admin --if-exists $build" 2>&1 | Out-Null
}

$kb = [math]::Round((Get-Item $Out).Length / 1KB)
$what = if ($Sample) { 'Sample (test) cooperative' } else { 'Blank database' }
"$what written: $Out ($kb KB; $($counts[-1]) tables, $($counts[-2]) database changes recorded, branch $BranchCode '$BranchName')."
'Restore it as coop_admin, after creating the roles coop_admin and coop_app:'
'  pg_restore -h <host> -U coop_admin -d <database> --no-owner --role=coop_admin --exit-on-error <file>'
