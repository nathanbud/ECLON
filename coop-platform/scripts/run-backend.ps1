# Rebuilds and (re)starts the backend on port 8090 as the limited database user, with profile editing on.
# Passwords are read at run time from db.txt and apps/desktop/.env.local; nothing is stored here.
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$core = Join-Path $root 'coop-platform\backend\coop-core'

$old = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*coop-core*' }
foreach ($p in $old) { Stop-Process -Id $p.ProcessId -Force }
if ($old) { Start-Sleep 2 }

Set-Location $core
mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

$env:DB_USER = 'coop_app'
$env:DB_PASSWORD = ((Get-Content (Join-Path $root 'db.txt') | Where-Object { $_ -match '^app_password' }) -replace '^app_password:\s*', '').Trim()
$env:SERVER_PORT = '8090'
$env:COOP_EDIT_ENABLED = 'true'
foreach ($l in Get-Content (Join-Path $root 'coop-platform\apps\desktop\.env.local')) {
  if ($l -match '^VITE_DEV_API_USER=(.*)$') { $env:DEV_API_USER = $Matches[1].Trim() }
  if ($l -match '^VITE_DEV_API_PASSWORD=(.*)$') { $env:DEV_API_PASSWORD = $Matches[1].Trim() }
}
Start-Process -FilePath java -ArgumentList '-jar', 'target/coop-core-0.1.0-SNAPSHOT.jar' -WorkingDirectory $core -WindowStyle Hidden
Start-Sleep 25
(Invoke-WebRequest http://localhost:8090/actuator/health -UseBasicParsing).Content
