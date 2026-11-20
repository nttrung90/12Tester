$ErrorActionPreference = 'Continue'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $projectRoot
$env:OPENCODE_CONFIG_CONTENT = '{"share":"disabled","permission":{"external_directory":"deny"}}'
& opencode run --model google/antigravity-gemini-3.8-flash --variant high --format json --title 'Implement admin pause reason popup 2026-11-05' --file (Join-Path $PSScriptRoot 'task-opencode.txt') --file (Join-Path $projectRoot 'KE_HOACH_THONG_BAO_NOI_KHI_ADMIN_TAM_DUNG_APP_2026-11-05.md') --auto 'Read the attached task and plan. Implement the complete feature now, test it, and write the handover. Preserve all existing work.' *> (Join-Path $PSScriptRoot 'opencode-events.jsonl')
$cliExit = $LASTEXITCODE
Write-Output ('OpenCode exit code: ' + $cliExit)
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'opencode-events.jsonl') -Tail 5
exit $cliExit
