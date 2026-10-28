# Applies the database migrations in coop-platform/database/migrations that have not been applied yet, in order, and records each one in
# ecis_schema_migration, so a script is never run twice and a forgotten one shows up. Until Flyway can be used (it does not support MySQL 5.6).
#   (no switch)  list what is pending and apply it
#   -DryRun      only list what is pending
#   -Baseline    record every script found as already applied, without running it (do this once on a database that was migrated by hand)
#   -Schema      the schema to use (default iaccsbsucmpc01)
# Uses the MySQL root account, because migrations create tables and grants. The root password is read from db.txt at run time and is not stored anywhere.
param([switch]$DryRun, [switch]$Baseline, [string]$Schema = 'iaccsbsucmpc01')
# The mysql client prints a password warning on stderr; Windows PowerShell 5.1 would treat that as a failure, so only exit codes count here.
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$dir = Join-Path $root 'coop-platform\database\migrations'
$mysql = 'C:\Program Files\MySQL\MySQL Server 5.6\bin\mysql.exe'
$pw = ((Get-Content (Join-Path $root 'db.txt') | Where-Object { $_ -match '^password' } | Select-Object -First 1) -replace '^password:\s*', '').Trim()
$args0 = @('-h127.0.0.1', '-P3311', '-uroot', "-p$pw", $Schema)

function Run-Sql([string]$sql) { $sql | & $mysql @args0 --batch --skip-column-names 2>&1 | Where-Object { $_ -is [string] -and $_ -notmatch 'Using a password' } }

Run-Sql 'CREATE TABLE IF NOT EXISTS ecis_schema_migration (version VARCHAR(20) NOT NULL PRIMARY KEY, file_name VARCHAR(200) NOT NULL, applied_at DATETIME NOT NULL, checksum CHAR(64) NOT NULL, how VARCHAR(10) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8' | Out-Null
$done = @{}
Run-Sql 'SELECT version, checksum FROM ecis_schema_migration' | ForEach-Object { $p = $_ -split "`t"; if ($p.Count -ge 2) { $done[$p[0]] = $p[1] } }

$files = Get-ChildItem $dir -Filter 'V*.sql' | Sort-Object { [int](($_.Name -replace '^V(\d+)_.*', '$1')) }
$pending = @()
foreach ($f in $files) {
  $version = ($f.Name -replace '^(V\d+)_.*', '$1')
  $sum = (Get-FileHash $f.FullName -Algorithm SHA256).Hash
  if ($done.ContainsKey($version)) {
    if ($done[$version] -ne $sum) { Write-Warning "$($f.Name) has changed since it was applied." }
  } else { $pending += [pscustomobject]@{ Version = $version; File = $f; Sum = $sum } }
}

if ($pending.Count -eq 0) { 'Nothing to apply: every migration is recorded.'; exit 0 }
"Pending ($($pending.Count)): " + (($pending | ForEach-Object { $_.File.Name }) -join ', ')
if ($DryRun) { exit 0 }

foreach ($m in $pending) {
  if (-not $Baseline) {
    "Applying $($m.File.Name) ..."
    $out = Get-Content $m.File.FullName -Raw | & $mysql @args0 2>&1
    $code = $LASTEXITCODE
    $out | Where-Object { "$_" -notmatch 'Using a password' } | ForEach-Object { "$_" }
    if ($code -ne 0) { throw "$($m.File.Name) failed. Nothing after it was applied." }
  }
  $how = if ($Baseline) { 'BASELINE' } else { 'APPLIED' }
  Run-Sql "INSERT INTO ecis_schema_migration (version, file_name, applied_at, checksum, how) VALUES ('$($m.Version)', '$($m.File.Name)', NOW(), '$($m.Sum)', '$how')" | Out-Null
}
if ($Baseline) { "Recorded $($pending.Count) migration(s) as already applied." } else { "Applied $($pending.Count) migration(s)." }
