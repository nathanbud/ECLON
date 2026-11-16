# Sets one version number in every place that carries it, so the screen, the desktop shell and the backend always agree.
#   .\bump-version.ps1 -Version 0.2.0
# Semantic Versioning: MAJOR.MINOR.PATCH (0.x while the system is pre-release). The backend (Maven) is written as <version>-SNAPSHOT between releases; pass -Release to write it without.
# After bumping: commit, then tag the commit  git tag v0.2.0  so the build number shown on the sign-in screen can be traced to a release.
param([Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version, [switch]$Release)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$mavenVersion = if ($Release) { $Version } else { "$Version-SNAPSHOT" }

function Set-First([string]$file, [string]$pattern, [string]$replacement, [string]$shown) {
  $path = Join-Path $root $file
  $text = [System.IO.File]::ReadAllText($path)
  $new = [regex]::Replace($text, $pattern, $replacement, 'None', [TimeSpan]::FromSeconds(5))
  # only the first match may change: patterns are anchored to the project's own version line
  if ($new -eq $text) { "{0,-52} already {1}" -f $file, $shown } else { [System.IO.File]::WriteAllText($path, $new); "{0,-52} -> {1}" -f $file, $shown }
}

Set-First 'apps\desktop\package.json' '(?m)^(  "version": ")[^"]+(")' "`${1}$Version`${2}" $Version
Set-First 'apps\desktop\src-tauri\tauri.conf.json' '(?m)^(  "version": ")[^"]+(")' "`${1}$Version`${2}" $Version
Set-First 'apps\desktop\src-tauri\Cargo.toml' '(?m)^(version = ")[^"]+(")' "`${1}$Version`${2}" $Version
Set-First 'backend\coop-core\pom.xml' '(?s)(<artifactId>coop-core</artifactId>\s*<version>)[^<]+(</version>)' "`${1}$mavenVersion`${2}" $mavenVersion
''
"Version is now $Version (backend $mavenVersion). Commit, then: git tag v$Version"
