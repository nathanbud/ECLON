# Starts a second backend on this PC connected to a Neon test database (documentation/guides/neon-test-database.md), beside the usual one:
# it does not stop or rebuild the backend on port 8090, and it never reads db.txt. The passwords are asked for when it starts and kept only in this run.
#   -NeonHost   the Neon direct host (ep-....aws.neon.tech, without -pooler)
#   -Database   the database on Neon (default coop_test)
#   -Port       where this backend answers (default 8092); in the desktop app's Connection window add http://localhost:<Port>
# Stop it by closing this window or with Ctrl+C.
param([Parameter(Mandatory = $true)][string]$NeonHost, [string]$Database = 'coop_test', [int]$Port = 8092)
$ErrorActionPreference = 'Stop'
if ($NeonHost -match '-pooler') { throw 'Use the direct host (without -pooler): in the Neon console, Connect, switch Connection pooling off and copy the host again.' }
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$core = Join-Path $root 'coop-platform\backend\coop-core'
$jar = Join-Path $core 'target\coop-core-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw "No backend build found at $jar. Build it first (mvn -q -DskipTests package in coop-platform\backend\coop-core)." }

function Read-Secret([string]$Prompt) {
  $s = Read-Host -AsSecureString $Prompt
  $p = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
  try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p) }
}

$env:DB_HOST = $NeonHost
$env:DB_PORT = '5432'
$env:DB_NAME = $Database
$env:DB_USER = 'coop_app'
$env:DB_PASSWORD = Read-Secret "Password of coop_app on Neon"
$env:DB_SSLMODE = 'require'
$env:DEV_API_USER = Read-Host 'Bootstrap user name for this test database'
$env:DEV_API_PASSWORD = Read-Secret 'Bootstrap password for this test database'
$env:SERVER_PORT = "$Port"
$env:COOP_EDIT_ENABLED = 'true'
# Never follow the saved default connection (Settings > System > Database connection): it would connect to another database.
$env:COOP_CONNECTIONS_FILE = Join-Path $env:TEMP 'coop-neon-no-connections.json'
# Its own log, documents and error journal, apart from the usual backend's.
$data = Join-Path $env:TEMP 'coop-neon'
New-Item -ItemType Directory -Force $data | Out-Null
$env:LOGGING_FILE_NAME = Join-Path $data 'backend.log'
$env:COOP_DOCUMENTS_ROOT = Join-Path $data 'documents'
$env:COOP_BIOMETRIC_KEY_FILE = Join-Path $data 'biometric.key'
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '5'

Write-Host "Connecting to $Database on $NeonHost; this backend will answer on http://localhost:$Port (log: $env:LOGGING_FILE_NAME)"
Write-Host 'The first start can take a minute (Neon may be waking up). Press Ctrl+C to stop.'
Set-Location $core
& java -Xmx1g -jar $jar
