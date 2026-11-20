$ErrorActionPreference = 'Continue'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $projectRoot
$env:OPENCODE_CONFIG_CONTENT = '{"share":"disabled","permission":{"external_directory":"deny"}}'
& opencode run --model google/antigravity-gemini-3.8-flash --variant high --format json --title 'Repair admin pause backend review findings' --file (Join-Path $PSScriptRoot 'task-backend-review.txt') --auto 'Read the attached scoped repair task. Fix backend review findings 1-4 now, test, and write backend-review-handover.md. Do not touch Android or files owned by the primary session.' *> (Join-Path $PSScriptRoot 'opencode-backend-review-events.jsonl')
$cliExit = $LASTEXITCODE
Write-Output ('OpenCode backend review exit code: ' + $cliExit)
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'opencode-backend-review-events.jsonl') -Tail 4
exit $cliExit
