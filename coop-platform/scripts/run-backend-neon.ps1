# Starts a second backend on this PC connected to a Neon test database (documentation/guides/neon-test-database.md), beside the usual one on port 8090.
# One command does everything:
#   - builds the backend first when the code changed since the last build (if the usual backend is running from that build, it is rebuilt and
#     restarted through run-backend.ps1, which it needed anyway);
#   - stops an earlier Neon backend still running;
#   - runs its own copy of the build (%LOCALAPPDATA%\coop-neon\coop-neon.jar), so run-backend.ps1 can rebuild while this one runs and does not stop it;
#   - never reads db.txt.
#
#   -NeonHost   the Neon direct host (ep-....aws.neon.tech, without -pooler); remembered after the first run
#   -Database   the database on Neon (default coop_test); remembered after the first run
#   -Port       where this backend answers (default 8092); in the desktop app's Connection window add http://localhost:<Port>
#   -Remember   keep the coop_app password and the bootstrap sign-in for the next runs, encrypted for this Windows account only (Windows DPAPI), in
#               %LOCALAPPDATA%\coop-neon\sign-in.xml; without it they are asked every time and kept only in this run
#   -Forget     delete what -Remember kept, then ask again
#   -NoBuild    start the last build as it is
#
# After the first run (.\run-backend-neon.ps1 -NeonHost ep-....neon.tech -Database coop_bgempc -Remember) it is just: .\run-backend-neon.ps1
# Stop it by closing this window or with Ctrl+C.
param([string]$NeonHost, [string]$Database, [int]$Port = 8092, [switch]$Remember, [switch]$Forget, [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$core = Join-Path $root 'coop-platform\backend\coop-core'
$jar = Join-Path $core 'target\coop-core-0.1.0-SNAPSHOT.jar'
$home_ = Join-Path $env:LOCALAPPDATA 'coop-neon'
$settingsFile = Join-Path $home_ 'settings.json'
$secretFile = Join-Path $home_ 'sign-in.xml'
New-Item -ItemType Directory -Force $home_ | Out-Null

# 1. Where to connect: given, else remembered from the last run.
$saved = if (Test-Path $settingsFile) { Get-Content $settingsFile -Raw | ConvertFrom-Json } else { $null }
if (-not $NeonHost -and $saved) { $NeonHost = $saved.host }
if (-not $Database) { $Database = if ($saved -and $saved.database) { $saved.database } else { 'coop_test' } }
if (-not $NeonHost) { throw 'Give the Neon host the first time: .\run-backend-neon.ps1 -NeonHost ep-....aws.neon.tech -Database <database>' }
if ($NeonHost -match '-pooler') { throw 'Use the direct host (without -pooler): in the Neon console, Connect, switch Connection pooling off and copy the host again.' }
@{ host = $NeonHost; database = $Database } | ConvertTo-Json | Set-Content -Encoding UTF8 $settingsFile

# 2. Stop an earlier Neon backend (it runs coop-neon.jar; the usual backend runs from target and is left alone).
#    Also a backend already answering on this port (one started by an older copy of this script, from target).
$onPort = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | ForEach-Object OwningProcess)
$old = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*coop-neon.jar*' -or $_.ProcessId -in $onPort }
foreach ($p in $old) { Write-Host "Stopping the earlier Neon backend (process $($p.ProcessId))..."; Stop-Process -Id $p.ProcessId -Force }
if ($old) { Start-Sleep 2 }

# 3. Build when the code is newer than the last build.
if (-not $NoBuild) {
  $sources = @((Join-Path $core 'src\main'), (Join-Path $core 'pom.xml'), (Join-Path $root 'coop-platform\database\postgres\migrations'))
  $newest = $sources | ForEach-Object { Get-ChildItem $_ -Recurse -File } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $stale = -not (Test-Path $jar) -or $newest.LastWriteTime -gt (Get-Item $jar).LastWriteTime
  if ($stale) {
    $usual = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*coop-core-0.1.0-SNAPSHOT.jar*' }
    if ($usual) {
      Write-Host 'The code changed since the last build, and the usual backend runs from that build: rebuilding and restarting it (run-backend.ps1)...'
      & (Join-Path $PSScriptRoot 'run-backend.ps1') | Out-Host
    } else {
      Write-Host 'The code changed since the last build: building...'
      Push-Location $core
      $env:MAVEN_OPTS = '--enable-native-access=ALL-UNNAMED'
      $ErrorActionPreference = 'Continue'
      mvn -q -DskipTests package
      $built = $LASTEXITCODE
      $ErrorActionPreference = 'Stop'
      Pop-Location
      if ($built -ne 0) { throw 'The build failed; the messages above say why.' }
    }
  }
}
if (-not (Test-Path $jar)) { throw "No backend build found at $jar." }
$runJar = Join-Path $home_ 'coop-neon.jar'
Copy-Item $jar $runJar -Force

# 4. The passwords: remembered (encrypted for this Windows account), else asked.
function Read-Secret([string]$Prompt) {
  $s = Read-Host -AsSecureString $Prompt
  $p = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
  try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p) }
}
function Get-Plain([Security.SecureString]$s) {
  $p = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
  try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p) }
}
if ($Forget -and (Test-Path $secretFile)) { Remove-Item $secretFile -Force; Write-Host 'The remembered passwords were deleted.' }
$kept = $null
if (Test-Path $secretFile) {
  try { $kept = Import-Clixml $secretFile } catch { Write-Warning 'The remembered passwords could not be read (another Windows account?); asking again.' }
}
if ($kept -and $kept.Host -eq $NeonHost -and $kept.Database -eq $Database) {
  $env:DB_PASSWORD = Get-Plain $kept.AppPassword
  $env:DEV_API_USER = $kept.BootstrapUser
  $env:DEV_API_PASSWORD = Get-Plain $kept.BootstrapPassword
  Write-Host "Using the remembered passwords (bootstrap sign-in: $($kept.BootstrapUser)). Run with -Forget to type new ones."
} else {
  $env:DB_PASSWORD = Read-Secret "Password of coop_app on Neon"
  $env:DEV_API_USER = Read-Host 'Bootstrap user name for this test database (you choose it)'
  $env:DEV_API_PASSWORD = Read-Secret 'Bootstrap password for this test database (you choose it)'
  if ($Remember) {
    [pscustomobject]@{
      Host = $NeonHost; Database = $Database; BootstrapUser = $env:DEV_API_USER
      AppPassword = ConvertTo-SecureString $env:DB_PASSWORD -AsPlainText -Force
      BootstrapPassword = ConvertTo-SecureString $env:DEV_API_PASSWORD -AsPlainText -Force
    } | Export-Clixml $secretFile
    Write-Host "Remembered for next time (encrypted for this Windows account): $secretFile"
  }
}

# 5. The rest of the backend's settings.
$env:DB_HOST = $NeonHost
$env:DB_PORT = '5432'
$env:DB_NAME = $Database
$env:DB_USER = 'coop_app'
$env:DB_SSLMODE = 'require'
$env:SERVER_PORT = "$Port"
$env:COOP_EDIT_ENABLED = 'true'
Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
# Never follow the saved default connection (Settings > System > Database connection): it would connect to another database.
$env:COOP_CONNECTIONS_FILE = Join-Path $env:TEMP 'coop-neon-no-connections.json'
# Its own log, documents and error journal, apart from the usual backend's.
$data = Join-Path $env:TEMP 'coop-neon'
New-Item -ItemType Directory -Force $data | Out-Null
$env:LOGGING_FILE_NAME = Join-Path $data 'backend.log'
$env:COOP_DOCUMENTS_ROOT = Join-Path $data 'documents'
$env:COOP_BIOMETRIC_KEY_FILE = Join-Path $data 'biometric.key'
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '10'

Write-Host "Connecting to $Database on $NeonHost; this backend will answer on http://localhost:$Port (log: $env:LOGGING_FILE_NAME)"
Write-Host 'The first start can take a minute (Neon may be waking up). Press Ctrl+C to stop.'
Set-Location $core
& java -Xmx1g -jar $runJar
