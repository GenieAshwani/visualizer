$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $projectRoot 'backend')
try {
    & mvn.cmd package -q
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests or packaging failed.' }
    Write-Host 'Open http://127.0.0.1:8085 — press Ctrl+C to stop.'
    & java -jar 'target/array-tracer-1.0.0.jar' --debug=false
} finally { Pop-Location }
