# Downloads the speech model the voice commands use and puts it where the app serves it from (apps/desktop/public/voice/model.tar.gz).
# The model (Vosk small English, about 40 MB) runs inside the app on this computer; no audio is ever sent anywhere. It is not kept in git (too large): run this once on each
# computer that builds the app, then build or run as usual. Safe to run again: it replaces the file.
#   -Force   download again even if the model is already there
param([switch]$Force)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$target = Join-Path $root 'apps\desktop\public\voice'
$out = Join-Path $target 'model.tar.gz'
if ((Test-Path $out) -and -not $Force) { "The voice model is already at $out ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB). Use -Force to download it again."; return }

$name = 'vosk-model-small-en-us-0.15'
$url = "https://alphacephei.com/vosk/models/$name.zip"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("coop-voice-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work, $target | Out-Null
try {
  Write-Host "Downloading $url ..."
  Invoke-WebRequest -UseBasicParsing $url -OutFile (Join-Path $work "$name.zip")
  Expand-Archive (Join-Path $work "$name.zip") $work -Force
  $dir = Join-Path $work $name
  foreach ($need in 'am', 'conf', 'graph') { if (-not (Test-Path (Join-Path $dir $need))) { throw "The download is not the expected model (missing $need)." } }
  # The browser library reads a .tar.gz with the model's folder at the top.
  tar -czf $out -C $work $name
  if ($LASTEXITCODE -ne 0) { throw 'Packing the model failed.' }
  "Voice model ready: $out ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB)"
} finally {
  Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
