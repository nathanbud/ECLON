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
Start-Process -FilePath java -ArgumentList '-jar', 'target/coop-core-0.1.0-SNAPSHOT.jar' -WorkingDirectory $core -WindowStyle Hidden
Start-Sleep 25
$h = (Invoke-WebRequest http://localhost:8090/actuator/health -UseBasicParsing).Content
if ($h -is [byte[]]) { $h = [Text.Encoding]::UTF8.GetString($h) }
$h
