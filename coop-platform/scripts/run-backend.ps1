# Rebuilds and (re)starts the backend on port 8090 as the limited database user, with profile editing on.
# Passwords are read at run time from db.txt and apps/desktop/.env.local; nothing is stored here.
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$core = Join-Path $root 'coop-platform\backend\coop-core'

$old = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*coop-core*' }
foreach ($p in $old) { Stop-Process -Id $p.ProcessId -Force }
if ($old) { Start-Sleep 2 }

Set-Location $core
# Maven on JDK 25 prints a warning on stderr; Windows PowerShell would treat that as a failure under 'Stop', so only the exit code counts.
$env:MAVEN_OPTS = '--enable-native-access=ALL-UNNAMED'
$ErrorActionPreference = 'Continue'
mvn -q -DskipTests package
$built = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($built -ne 0) { throw 'Build failed' }

$env:DB_USER = 'coop_app'
$env:DB_PASSWORD = ((Get-Content (Join-Path $root 'db.txt') | Where-Object { $_ -match '^app_password' }) -replace '^app_password:\s*', '').Trim()
$env:SERVER_PORT = '8090'
# The app itself defaults to PostgreSQL (spring.profiles.default in application.yml; container eclon-postgres, port 5440).
# To use the old MySQL database instead, run $env:SPRING_PROFILES_ACTIVE = 'mysql' before this script.
Write-Host ("Database profile: " + $(if ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE } else { 'default (postgres)' }))
$env:COOP_EDIT_ENABLED = 'true'
foreach ($l in Get-Content (Join-Path $root 'coop-platform\apps\desktop\.env.local')) {
  if ($l -match '^VITE_DEV_API_USER=(.*)$') { $env:DEV_API_USER = $Matches[1].Trim() }
  if ($l -match '^VITE_DEV_API_PASSWORD=(.*)$') { $env:DEV_API_PASSWORD = $Matches[1].Trim() }
}
# The backend runs hidden, so it always writes a log: logs\backend.log beside the backend (git-ignored), rolled at 10 MB, kept 14 days and 200 MB at most.
# Errors behind "Something went wrong on the server" are found there. Set LOGGING_FILE_NAME before this script to write it somewhere else.
# The log can hold member details from failed requests (a refused value, a record number); it stays on this computer and is never committed.
if (-not $env:LOGGING_FILE_NAME) { $env:LOGGING_FILE_NAME = Join-Path $core 'logs\backend.log' }
New-Item -ItemType Directory -Force (Split-Path $env:LOGGING_FILE_NAME) | Out-Null
$env:LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE = '10MB'
$env:LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY = '14'
$env:LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP = '200MB'
Write-Host "Log: $env:LOGGING_FILE_NAME"
$started = Get-Date
Start-Process -FilePath java -ArgumentList '-jar', 'target/coop-core-0.1.0-SNAPSHOT.jar' -WorkingDirectory $core -WindowStyle Hidden

# Wait until it answers (usually 15 to 30 seconds), up to two minutes; if it never does, show the end of this start's log.
$h = $null
while (-not $h -and ((Get-Date) - $started).TotalSeconds -lt 120) {
  Start-Sleep 3
  try { $h = (Invoke-WebRequest http://localhost:8090/actuator/health -UseBasicParsing -TimeoutSec 5).Content } catch { }
}
if (-not $h) {
  Write-Host 'The backend did not answer within two minutes. The end of its log:'
  if (Test-Path $env:LOGGING_FILE_NAME) { Get-Content $env:LOGGING_FILE_NAME -Tail 40 }
  throw 'Backend did not start'
}
if ($h -is [byte[]]) { $h = [Text.Encoding]::UTF8.GetString($h) }
$h
