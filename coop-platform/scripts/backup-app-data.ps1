# Backs up everything the new application owns: every ecis_* table (members' requests, audit log, journals, procurement, inventory, POS, assets, documents index,
# fingerprints, settings and the migration record) plus the biometric key file, into a dated folder under backups/ (git-ignored).
# The legacy tables are NOT included: they are the old system's data (about 71 GB) and are backed up as the database itself.
#   -Schema  the schema to back up (default iaccsbsucmpc01)
#   -Keep    how many dated backups to keep (default 14; older ones are deleted)
# The biometric key and the database dump must be kept TOGETHER and stored somewhere other than this machine: without the key, every stored fingerprint is unreadable.
# Uses the MySQL root account (read only here); the password is read from db.txt at run time and is not stored anywhere.
param([string]$Schema = 'iaccsbsucmpc01', [int]$Keep = 14)
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$bin = 'C:\Program Files\MySQL\MySQL Server 5.6\bin'
$pw = ((Get-Content (Join-Path $root 'db.txt') | Where-Object { $_ -match '^password' } | Select-Object -First 1) -replace '^password:\s*', '').Trim()
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$dir = Join-Path $root "backups\$stamp"
New-Item -ItemType Directory -Force $dir | Out-Null

$tables = & "$bin\mysql.exe" --host=127.0.0.1 --port=3311 --user=root "--password=$pw" -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$Schema' AND LEFT(table_name, 5) = 'ecis_'" 2>$null
if (-not $tables) { throw 'No ecis_ tables were found. Nothing to back up.' }
$dump = Join-Path $dir "ecis-$Schema.sql"
& "$bin\mysqldump.exe" --host=127.0.0.1 --port=3311 --user=root "--password=$pw" --single-transaction --routines --triggers --default-character-set=utf8 --result-file="$dump" $Schema @($tables) 2>$null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $dump) -or (Get-Item $dump).Length -lt 1024) { throw 'The dump failed or is empty.' }

$key = Join-Path $root 'coop-platform\backend\coop-core\data\biometric.key'
if (Test-Path $key) { Copy-Item $key (Join-Path $dir 'biometric.key') } else { Write-Warning 'No biometric key file found (a key set in the environment is not backed up here).' }

# Older backups beyond the number to keep
Get-ChildItem (Join-Path $root 'backups') -Directory | Sort-Object Name -Descending | Select-Object -Skip $Keep | Remove-Item -Recurse -Force

$mb = [math]::Round((Get-Item $dump).Length / 1MB, 2)
"Backed up $(@($tables).Count) tables ($mb MB) to $dir"
"Keep the dump and biometric.key together, and copy them off this machine."
