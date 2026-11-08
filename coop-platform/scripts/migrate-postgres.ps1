# The main way to change the PostgreSQL database. Applies the scripts in coop-platform/database/postgres/migrations that have not been applied yet, in order,
# each in ONE transaction (a script that fails changes nothing), and records each in legacy.ecis_pg_migration so a script is never run twice and a changed one shows up.
#   (no switch)  list what is pending and apply it
#   -DryRun      only list what is pending
#   -Baseline    record every pending script as already applied, without running it
#   -Dir         the folder of scripts (default database/postgres/migrations)
# The first run records V001 to V016 as already applied (they were applied by setup-postgres-app.ps1 and the first load). V009 to V011 are regenerated from MySQL whenever
# that setup script runs, so their checksums are not compared.
# Runs as the container's admin account (coop_admin, through docker exec), because migrations create tables and grants. No password is read or stored here.
# New work: write the next number here (V017, V018, ...) as a PostgreSQL script first. A MySQL script in database/migrations is only needed while MySQL is still in use.
# setup-postgres-app.ps1 rebuilds the ecis_ tables from MySQL; use it for the one-time move, not once people work on PostgreSQL.
param([switch]$DryRun, [switch]$Baseline, [string]$Dir)
# psql prints NOTICE lines on stderr, which Windows PowerShell 5.1 would treat as a failure under 'Stop', so only exit codes count here.
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
if (-not $Dir) { $Dir = Join-Path $root 'coop-platform\database\postgres\migrations' }
$FIRST_NEW = 17
$REGENERATED = @('V009', 'V010', 'V011')

function Psql([string]$sql) {
  $out = $sql | docker exec -i eclon-postgres psql -U coop_admin -d coop -At -F '|' -v ON_ERROR_STOP=1 -q 2>&1
  if ($LASTEXITCODE -ne 0) { throw ("psql failed: " + ($out -join ' ')) }
  $out | Where-Object { "$_" -notmatch 'NOTICE' }
}

docker exec eclon-postgres pg_isready -U coop_admin -d coop | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL (container eclon-postgres) is not running. Start Docker Desktop and the container first.' }

$fresh = (Psql "select to_regclass('legacy.ecis_pg_migration') is null") -eq 't'
if (-not $DryRun) { Psql 'create table if not exists legacy.ecis_pg_migration (version varchar(20) primary key, file_name varchar(200) not null, applied_at timestamp not null default now(), checksum char(64) not null, how varchar(10) not null)' | Out-Null }

$files = Get-ChildItem $Dir -Filter 'V*.sql' | Sort-Object { [int](($_.Name -replace '^V(\d+)_.*', '$1')) }
$done = @{}
if (-not ($fresh -and $DryRun)) { Psql 'select version, checksum, how from legacy.ecis_pg_migration' | ForEach-Object { $p = "$_" -split '\|'; if ($p.Count -ge 3) { $done[$p[0]] = @{ Sum = $p[1]; How = $p[2] } } } }

function Record($version, $file, $sum, $how) {
  Psql "insert into legacy.ecis_pg_migration (version, file_name, checksum, how) values ('$version', '$($file -replace "'", "''")', '$sum', '$how')" | Out-Null
}

if ($fresh -and -not $DryRun) {
  foreach ($f in $files) {
    $n = [int]($f.Name -replace '^V(\d+)_.*', '$1')
    if ($n -lt $FIRST_NEW) { Record ($f.Name -replace '^(V\d+)_.*', '$1') $f.Name (Get-FileHash $f.FullName -Algorithm SHA256).Hash 'BASELINE'; $done[($f.Name -replace '^(V\d+)_.*', '$1')] = @{ Sum = ''; How = 'BASELINE' } }
  }
  Write-Host 'First run: V001 to V016 recorded as already applied.'
}

$pending = @()
foreach ($f in $files) {
  $version = ($f.Name -replace '^(V\d+)_.*', '$1')
  $sum = (Get-FileHash $f.FullName -Algorithm SHA256).Hash
  if ($done.ContainsKey($version)) {
    $d = $done[$version]
    if ($d.How -ne 'BASELINE' -and $REGENERATED -notcontains $version -and $d.Sum -ne $sum) { Write-Warning "$($f.Name) has changed since it was applied." }
  } elseif ($fresh -and $DryRun -and [int]($version -replace 'V', '') -lt $FIRST_NEW) {
    continue
  } else { $pending += [pscustomobject]@{ Version = $version; File = $f; Sum = $sum } }
}

if ($pending.Count -eq 0) { 'Nothing to apply: every PostgreSQL migration is recorded.'; exit 0 }
"Pending ($($pending.Count)): " + (($pending | ForEach-Object { $_.File.Name }) -join ', ')
if ($DryRun) { exit 0 }

foreach ($m in $pending) {
  if (-not $Baseline) {
    "Applying $($m.File.Name) ..."
    $out = Get-Content $m.File.FullName -Raw | docker exec -i eclon-postgres psql -U coop_admin -d coop -v ON_ERROR_STOP=1 --single-transaction -q 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$($m.File.Name) failed and was rolled back. Nothing after it was applied. $($out -join ' ')" }
    $out | Where-Object { "$_" -notmatch 'NOTICE' } | ForEach-Object { "$_" }
  }
  Record $m.Version $m.File.Name $m.Sum $(if ($Baseline) { 'BASELINE' } else { 'APPLIED' })
}
if ($Baseline) { "Recorded $($pending.Count) migration(s) as already applied." } else { "Applied $($pending.Count) migration(s)." }
