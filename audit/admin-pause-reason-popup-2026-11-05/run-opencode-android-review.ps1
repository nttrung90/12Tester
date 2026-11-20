$ErrorActionPreference = 'Continue'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $projectRoot
$env:OPENCODE_CONFIG_CONTENT = '{"share":"disabled","permission":{"external_directory":"deny"}}'
& opencode run --session ses_e57087968ffez2Rgp31xWtpWsP --model google/antigravity-gemini-3.8-flash --variant high --format json --file (Join-Path $PSScriptRoot 'task-android-review.txt') --auto 'Codex review has not accepted completion yet. Read the attached remaining review task. Repair dialog session isolation and inline action errors, complete the concurrency runner and npm test integration, preserve the passing backend fixes and independent assertions, run final tests/build, and update your handover.' *> (Join-Path $PSScriptRoot 'opencode-android-review-events.jsonl')
$cliExit = $LASTEXITCODE
Write-Output ('OpenCode Android review exit code: ' + $cliExit)
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'opencode-android-review-events.jsonl') -Tail 4
exit $cliExit
