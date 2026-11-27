# Makes a folder (and a .zip of it) to run the backend against the Neon test database on another PC, on any network: the last backend build, the start
# scripts in scripts\neon-package, the Neon address, the test fingerprint key, and the desktop app's installer when one was built. No password is in it:
# the other PC asks them at its first start. Copy the .zip there, unzip, and follow README.txt.
#   -NeonHost, -Database   the Neon direct host and database (default: the ones run-backend-neon.ps1 remembered)
#   -Out                   the folder to write (default backups\neon-package under the repository; backups\ is git-ignored)
#   -NoFingerprintKey      leave the test fingerprint key out (the other PC then makes its own; fingerprints saved on one PC are not readable on the other)
param([string]$NeonHost, [string]$Database, [string]$Out, [switch]$NoFingerprintKey)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$core = Join-Path $root 'coop-platform\backend\coop-core'
$jar = Join-Path $core 'target\coop-core-0.1.0-SNAPSHOT.jar'
if (-not $Out) { $Out = Join-Path $root 'backups\neon-package' }

$savedFile = Join-Path $env:LOCALAPPDATA 'coop-neon\settings.json'
$saved = if (Test-Path $savedFile) { Get-Content $savedFile -Raw | ConvertFrom-Json } else { $null }
if (-not $NeonHost -and $saved) { $NeonHost = $saved.host }
if (-not $Database -and $saved) { $Database = $saved.database }
if (-not $NeonHost -or -not $Database) { throw 'Give -NeonHost ep-....aws.neon.tech -Database <database> (or run run-backend-neon.ps1 once, which remembers them).' }
if ($NeonHost -match '-pooler') { throw 'Use the direct host (without -pooler).' }

# The build must be up to date with the code, or the other PC runs an old backend.
if (-not (Test-Path $jar)) { throw "No backend build at $jar. Run scripts\run-backend.ps1 (or run-backend-neon.ps1) first, which builds it." }
$newest = @((Join-Path $core 'src\main'), (Join-Path $core 'pom.xml'), (Join-Path $root 'coop-platform\database\postgres\migrations')) |
  ForEach-Object { Get-ChildItem $_ -Recurse -File } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($newest.LastWriteTime -gt (Get-Item $jar).LastWriteTime) {
  throw "The code changed after the last build ($($newest.Name)). Run scripts\run-backend.ps1 (or run-backend-neon.ps1) first, which rebuilds, then run this again."
}

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Force $Out | Out-Null
Copy-Item $jar (Join-Path $Out 'coop-neon.jar')
Copy-Item (Join-Path $PSScriptRoot 'neon-package\*') $Out
@{ host = $NeonHost; database = $Database; port = 8092 } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $Out 'settings.json')
$key = Join-Path $env:TEMP 'coop-neon\biometric.key'
if (-not $NoFingerprintKey -and (Test-Path $key)) { Copy-Item $key (Join-Path $Out 'biometric.key') }
$setup = Get-ChildItem (Join-Path $root 'coop-platform\apps\desktop\src-tauri\target\release\bundle\nsis') -Filter '*-setup.exe' -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($setup) { Copy-Item $setup.FullName $Out }

$zip = "$Out.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
# Windows' virus scan may still be reading the files just copied; try again for up to half a minute.
Add-Type -AssemblyName System.IO.Compression.FileSystem
for ($try = 1; ; $try++) {
  try { [IO.Compression.ZipFile]::CreateFromDirectory($Out, $zip, [IO.Compression.CompressionLevel]::Optimal, $false); break }
  catch { if (Test-Path $zip) { Remove-Item $zip -Force }; if ($try -ge 10) { throw "Making the zip failed: $($_.Exception.Message)" }; Start-Sleep 3 }
}
$mb = [math]::Round((Get-Item $zip).Length / 1MB)
"Written: $zip ($mb MB), for $Database on $NeonHost."
if ($setup) { "Includes the desktop app installer $($setup.Name) (built $($setup.LastWriteTime))." } else { 'No desktop app installer was found (npx tauri build in apps\desktop makes one); install the app on the other PC separately.' }
'No password is in it. Keep it private anyway (it holds the test fingerprint key), and delete it when the test is over.'
