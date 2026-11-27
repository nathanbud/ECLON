# Starts the cooperative backend on this PC, connected to the Neon test database, for the desktop app on this same PC (http://localhost:8092).
# Part of the folder made by scripts\make-neon-package.ps1; needs only Java 21 or newer (Eclipse Temurin 21, https://adoptium.net).
#   start-neon.cmd            double-click: starts it (the first time it asks the passwords)
#   start-neon.cmd -Forget    forget the remembered passwords and ask again
# The passwords are kept only encrypted for this Windows account (sign-in.xml beside this file); the Neon address is in settings.json.
# Stop it by closing this window or with Ctrl+C.
param([switch]$Forget)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$settings = Get-Content (Join-Path $here 'settings.json') -Raw | ConvertFrom-Json
$jar = Join-Path $here 'coop-neon.jar'
$secretFile = Join-Path $here 'sign-in.xml'
$port = if ($settings.port) { [int]$settings.port } else { 8092 }

# Java 21 or newer.
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { throw 'Java is not installed. Install Eclipse Temurin 21 (JDK or JRE) from https://adoptium.net, tick "Set JAVA_HOME" and "Add to PATH", then start this again.' }
$ErrorActionPreference = 'Continue'
$v = (& java -version 2>&1 | Out-String)
$ErrorActionPreference = 'Stop'
if ($v -match 'version "(\d+)' -and [int]$Matches[1] -lt 21) { throw "This needs Java 21 or newer; this PC has Java $($Matches[1]). Install Eclipse Temurin 21 from https://adoptium.net." }

# An earlier copy still running on the port.
$onPort = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | ForEach-Object OwningProcess)
foreach ($p in (Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.ProcessId -in $onPort })) {
  Write-Host "Stopping the backend already running on port $port (process $($p.ProcessId))..."; Stop-Process -Id $p.ProcessId -Force; Start-Sleep 2
}
if (@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count -gt 0) { throw "Another program uses port $port. Close it, or change port in settings.json." }

function Get-Plain([Security.SecureString]$s) {
  $p = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
  try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p) }
}
if ($Forget -and (Test-Path $secretFile)) { Remove-Item $secretFile -Force; Write-Host 'The remembered passwords were deleted.' }
$kept = $null
if (Test-Path $secretFile) { try { $kept = Import-Clixml $secretFile } catch { Write-Warning 'The remembered passwords could not be read (another Windows account?); asking again.' } }
if ($kept) {
  $env:DB_PASSWORD = Get-Plain $kept.AppPassword
  $env:DEV_API_USER = $kept.BootstrapUser
  $env:DEV_API_PASSWORD = Get-Plain $kept.BootstrapPassword
  Write-Host "Using the remembered passwords (bootstrap sign-in: $($kept.BootstrapUser)). Start with -Forget to type new ones."
} else {
  $a = Read-Host -AsSecureString 'Password of coop_app on Neon'
  $u = Read-Host 'Bootstrap user name (the same as on the other PC, or a new one you choose)'
  $b = Read-Host -AsSecureString 'Bootstrap password'
  $env:DB_PASSWORD = Get-Plain $a; $env:DEV_API_USER = $u; $env:DEV_API_PASSWORD = Get-Plain $b
  if ((Read-Host 'Remember these on this PC for next time? (y/n)') -match '^[yY]') {
    [pscustomobject]@{ BootstrapUser = $u; AppPassword = $a; BootstrapPassword = $b } | Export-Clixml $secretFile
    Write-Host "Remembered, encrypted for this Windows account only: $secretFile"
  }
}

$env:DB_HOST = $settings.host
$env:DB_PORT = '5432'
$env:DB_NAME = $settings.database
$env:DB_USER = 'coop_app'
$env:DB_SSLMODE = 'require'
$env:SERVER_PORT = "$port"
$env:COOP_EDIT_ENABLED = 'true'
Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
$env:COOP_CONNECTIONS_FILE = Join-Path $here 'no-connections.json'
$data = Join-Path $here 'data'
New-Item -ItemType Directory -Force $data | Out-Null
$env:LOGGING_FILE_NAME = Join-Path $data 'backend.log'
$env:COOP_DOCUMENTS_ROOT = Join-Path $data 'documents'
# The fingerprint key: the copy from the PC that made this folder (so fingerprints saved there can be read here), else a new one is made.
$env:COOP_BIOMETRIC_KEY_FILE = Join-Path $here 'biometric.key'
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '10'

Write-Host "Connecting to $($settings.database) on $($settings.host)."
Write-Host "When it says Started, open the desktop app; in its Connection window use http://localhost:$port (log: $env:LOGGING_FILE_NAME)."
Write-Host 'The first start can take a minute (Neon may be waking up). Press Ctrl+C or close this window to stop.'
Set-Location $here
& java -Xmx1g -jar $jar
