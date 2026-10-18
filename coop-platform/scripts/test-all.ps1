# Runs every automated check and prints one summary.
#   -Quick   skip the database tests (rules and unit tests only, no database needed)
# The database tests post real hand-offs through the real code and roll them back. They need the limited coop_app user
# with the V006 grants. The password is read from db.txt at run time and is not stored anywhere.
#   -E2e     also run the browser tests (Playwright). Needs the backend running on :8090. Set E2E_USER and E2E_PASSWORD in
#            your shell first (the head cashier shows the most); without them only the sign-in test runs.
param([switch]$Quick, [switch]$E2e)
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$results = [ordered]@{}

Push-Location (Join-Path $root 'coop-platform\backend\coop-core')
if (-not $Quick) {
  $env:DB_USER = 'coop_app'
  $env:DB_PASSWORD = ((Get-Content (Join-Path $root 'db.txt') | Where-Object { $_ -match '^app_password' }) -replace '^app_password:\s*', '').Trim()
}
mvn test 2>&1 | Tee-Object -Variable mvnOut | Select-String -Pattern 'Tests run:|FAIL|ERROR\]' | Select-Object -Last 15
$results['Backend tests'] = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'FAILED' }
$summary = ($mvnOut | Select-String -Pattern 'Tests run:.*Skipped' | Select-Object -Last 1)
Pop-Location

Push-Location (Join-Path $root 'coop-platform\apps\desktop')
npm run build 2>&1 | Select-String -Pattern 'error|built in' | Select-Object -First 15
$results['Screens type-check and build'] = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'FAILED' }
if ($E2e) {
  $up = $false
  try { $up = (Invoke-WebRequest 'http://localhost:8090/actuator/health' -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { }
  if (-not $up) {
    $results['Browser tests'] = 'SKIPPED (backend is not running on :8090)'
  } else {
    npx playwright test --reporter=line 2>&1 | Select-String -Pattern 'passed|failed|skipped|Error:' | Select-Object -First 20
    $results['Browser tests'] = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'FAILED (open the report: npm run e2e:report)' }
    if (-not $env:E2E_USER -or -not $env:E2E_PASSWORD) { $results['Browser tests'] += ' - signed-in tests skipped, E2E_USER/E2E_PASSWORD not set' }
  }
}
Pop-Location

''
'==== Summary ===='
$results.GetEnumerator() | ForEach-Object { '{0,-32} {1}' -f $_.Key, $_.Value }
if ($summary) { $summary.Line.Trim() }
if ($Quick) { 'Database tests were skipped (-Quick).' }
if (($results.Values | Where-Object { $_ -like 'FAILED*' }).Count -gt 0) { exit 1 }
