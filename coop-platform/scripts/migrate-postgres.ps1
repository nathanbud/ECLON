# The main way to change the PostgreSQL databases. Applies the scripts in coop-platform/database/postgres/migrations that have not been applied yet, in order,
# each in ONE transaction (a script that fails changes nothing), to EVERY database the app works on, and records each in that database's own
# legacy.ecis_pg_migration, so a script is never run twice there and a changed one shows up. The databases (see CLAUDE.md):
#   coop         the Docker container eclon-postgres (port 5440): the data moved from the old MySQL system. Reached as its admin account through docker exec, no password.
#   coop_bgempc  the PostgreSQL installed on Windows (port 5432): a second cooperative. Reached as coop_admin with the password in db.txt
#                (a line "bgempc_admin_password: ...") or the environment variable COOP_BGEMPC_ADMIN_PASSWORD, read at run time and never stored or shown.
#   (no switch)  list what is pending in each database and apply it
#   -DryRun      only list what is pending (for coop_bgempc this needs only the app's own login, app_password in db.txt)
#   -Baseline    record every pending script as already applied, without running it
#   -Database    coop or coop_bgempc to work on one only (default: all)
#   -Dir         the folder of scripts (default database/postgres/migrations)
# A database that cannot be reached, or whose admin password is missing, is reported and the run ends with exit code 1, so it is not missed; the others are still done.
# coop: the first run recorded V001 to V016 as already applied (applied by setup-postgres-app.ps1 and the first load); V009 to V011 are regenerated from MySQL
# whenever that setup script runs, so their checksums are not compared. coop_bgempc: V001 to V019 were applied before its log existed, so they are not listed.
# New work: write the next number here (V034, V035, ...) as a PostgreSQL script first. A MySQL script in database/migrations is only needed while MySQL is still in use.
# setup-postgres-app.ps1 rebuilds the ecis_ tables from MySQL; use it for the one-time move, not once people work on PostgreSQL.
param([switch]$DryRun, [switch]$Baseline, [string]$Dir, [ValidateSet('all', 'coop', 'coop_bgempc')][string]$Database = 'all')
# psql prints NOTICE lines on stderr, which Windows PowerShell 5.1 would treat as a failure under 'Stop', so only exit codes count here.
$ErrorActionPreference = 'Continue'
# Migration files are read by psql itself (never piped), as UTF-8, so characters such as "·" arrive intact.
$env:PGCLIENTENCODING = 'UTF8'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
if (-not $Dir) { $Dir = Join-Path $root 'coop-platform\database\postgres\migrations' }
$REGENERATED = @('V009', 'V010', 'V011')
$LOG_TABLE = 'create table if not exists legacy.ecis_pg_migration (version varchar(20) primary key, file_name varchar(200) not null, applied_at timestamp not null default now(), checksum char(64) not null, how varchar(10) not null)'

# BaselineBelow: on a database with no log yet, scripts below this number are recorded as already applied. SkipBelow: scripts below this number are never listed.
$targets = @(
  [pscustomobject]@{ Name = 'coop'; Where = 'Docker container eclon-postgres, port 5440'; Kind = 'docker'; BaselineBelow = 17; SkipBelow = 0 },
  [pscustomobject]@{ Name = 'coop_bgempc'; Where = 'PostgreSQL on Windows, port 5432'; Kind = 'local'; BaselineBelow = 0; SkipBelow = 20 }
) | Where-Object { $Database -eq 'all' -or $_.Name -eq $Database }

$psqlExe = @('C:\Program Files\PostgreSQL\18\bin\psql.exe', (Get-Command psql -ErrorAction SilentlyContinue).Source) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

function Read-DbTxt([string]$key) {
  $f = Join-Path $root 'db.txt'
  if (-not (Test-Path $f)) { return $null }
  $line = Get-Content $f | Where-Object { $_ -match "^\s*$key\s*:" } | Select-Object -First 1
  if ($line) { ($line -replace "^\s*$key\s*:\s*", '').Trim() } else { $null }
}

# Runs SQL (or a file) on one database as the given user; returns the output lines without notices. -Fail: throw when psql fails.
function Invoke-Psql($t, [string]$sql, [string]$file, [string]$user, [switch]$Fail) {
  $common = @('-v', 'ON_ERROR_STOP=1', '-q', '-At', '-F', '|')
  if ($file) { $common += @('--single-transaction', '-f') }
  if ($t.Kind -eq 'docker') {
    # A file is copied into the container and read there: piped in, Windows PowerShell 5.1 would turn characters such as "·" into "?".
    # Short SQL from this script (the log's own statements) is plain ASCII and is piped.
    if ($file) {
      docker cp $file 'eclon-postgres:/tmp/coop-migration.sql' | Out-Null
      $out = docker exec -e PGCLIENTENCODING=UTF8 eclon-postgres psql -U coop_admin -d $t.Name @common /tmp/coop-migration.sql 2>&1
      $code = $LASTEXITCODE
      docker exec eclon-postgres rm -f /tmp/coop-migration.sql | Out-Null
      $global:LASTEXITCODE = $code
    }
    else { $out = $sql | docker exec -i eclon-postgres psql -U coop_admin -d $t.Name @common 2>&1 }
  } else {
    if ($file) { $out = & $psqlExe -h localhost -p 5432 -U $user -d $t.Name -w @common $file 2>&1 }
    else { $out = $sql | & $psqlExe -h localhost -p 5432 -U $user -d $t.Name -w @common 2>&1 }
  }
  $ok = $LASTEXITCODE -eq 0
  $lines = @($out | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'NOTICE:' })
  if (-not $ok -and $Fail) { throw ($lines -join ' ') }
  if (-not $ok) { return [pscustomobject]@{ Ok = $false; Lines = $lines } }
  if ($file) { return [pscustomobject]@{ Ok = $true; Lines = $lines } }
  $lines
}

$files = Get-ChildItem $Dir -Filter 'V*.sql' | Sort-Object { [int](($_.Name -replace '^V(\d+)_.*', '$1')) }
$summary = [ordered]@{}
$failed = $false

foreach ($t in $targets) {
  Write-Host ''
  Write-Host "==== $($t.Name) ($($t.Where))"
  $user = 'coop_admin'
  try {
    if ($t.Kind -eq 'docker') {
      docker exec eclon-postgres pg_isready -U coop_admin -d $t.Name | Out-Null
      if ($LASTEXITCODE -ne 0) { throw 'not reachable: start Docker Desktop and the container eclon-postgres first.' }
    } else {
      if (-not $psqlExe) { throw 'psql is not installed on this PC (expected C:\Program Files\PostgreSQL\18\bin\psql.exe).' }
      $pw = if ($env:COOP_BGEMPC_ADMIN_PASSWORD) { $env:COOP_BGEMPC_ADMIN_PASSWORD } else { Read-DbTxt 'bgempc_admin_password' }
      if (-not $pw -and $DryRun) { $user = 'coop_app'; $pw = Read-DbTxt 'app_password' }   # listing needs only reading
      if (-not $pw) { throw 'the coop_admin password for this server is not in db.txt (a line "bgempc_admin_password: ...") or COOP_BGEMPC_ADMIN_PASSWORD, so nothing was applied here.' }
      $env:PGPASSWORD = $pw
    }

    $fresh = (Invoke-Psql $t "select to_regclass('legacy.ecis_pg_migration') is null" -user $user -Fail) -eq 't'
    if (-not $DryRun) { Invoke-Psql $t $LOG_TABLE -user $user -Fail | Out-Null }
    $done = @{}
    if (-not $fresh) {
      Invoke-Psql $t 'select version, checksum, how from legacy.ecis_pg_migration' -user $user -Fail | ForEach-Object { $p = "$_" -split '\|'; if ($p.Count -ge 3) { $done[$p[0]] = @{ Sum = $p[1]; How = $p[2] } } }
    }
    $record = { param($version, $name, $sum, $how) "insert into legacy.ecis_pg_migration (version, file_name, checksum, how) values ('$version', '$($name -replace "'", "''")', '$sum', '$how')" }

    if ($fresh -and $t.BaselineBelow -gt 0 -and -not $DryRun) {
      foreach ($f in $files) {
        $v = $f.Name -replace '^(V\d+)_.*', '$1'
        if ([int]($v -replace 'V', '') -lt $t.BaselineBelow) { Invoke-Psql $t (& $record $v $f.Name (Get-FileHash $f.FullName -Algorithm SHA256).Hash 'BASELINE') -user $user -Fail | Out-Null; $done[$v] = @{ Sum = ''; How = 'BASELINE' } }
      }
      Write-Host ('First run: V001 to V{0:D3} recorded as already applied.' -f ($t.BaselineBelow - 1))
    }

    $pending = @()
    foreach ($f in $files) {
      $v = $f.Name -replace '^(V\d+)_.*', '$1'
      $n = [int]($v -replace 'V', '')
      if ($n -lt $t.SkipBelow) { continue }
      if ($fresh -and $DryRun -and $n -lt $t.BaselineBelow) { continue }
      $sum = (Get-FileHash $f.FullName -Algorithm SHA256).Hash
      if ($done.ContainsKey($v)) {
        $d = $done[$v]
        if ($d.How -ne 'BASELINE' -and $REGENERATED -notcontains $v -and $d.Sum -ne $sum) { Write-Warning "$($f.Name) has changed since it was applied to $($t.Name)." }
      } else { $pending += [pscustomobject]@{ Version = $v; File = $f; Sum = $sum } }
    }

    if ($pending.Count -eq 0) { Write-Host 'Nothing to apply: every migration is recorded.'; $summary[$t.Name] = 'up to date'; continue }
    Write-Host ("Pending ($($pending.Count)): " + (($pending | ForEach-Object { $_.File.Name }) -join ', '))
    if ($DryRun) { $summary[$t.Name] = "$($pending.Count) pending"; continue }

    $applied = 0
    foreach ($m in $pending) {
      if (-not $Baseline) {
        Write-Host "Applying $($m.File.Name) ..."
        $r = Invoke-Psql $t '' $m.File.FullName -user $user
        if (-not $r.Ok) { throw "$($m.File.Name) failed and was rolled back; $applied applied before it, nothing after it was tried. $($r.Lines -join ' ')" }
        $r.Lines | Where-Object { $_ } | ForEach-Object { Write-Host "  $_" }
      }
      Invoke-Psql $t (& $record $m.Version $m.File.Name $m.Sum $(if ($Baseline) { 'BASELINE' } else { 'APPLIED' })) -user $user -Fail | Out-Null
      $applied++
    }
    $summary[$t.Name] = if ($Baseline) { "recorded $applied as already applied" } else { "applied $applied" }
    Write-Host $summary[$t.Name]
  } catch {
    $failed = $true
    $summary[$t.Name] = 'NOT DONE: ' + $_.Exception.Message
    Write-Host $summary[$t.Name]
  } finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
  }
}

Write-Host ''
Write-Host '==== Summary'
$summary.GetEnumerator() | ForEach-Object { Write-Host ('{0,-12} {1}' -f $_.Key, $_.Value) }
if ($failed) { exit 1 }
