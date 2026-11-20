$ErrorActionPreference = 'Continue'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $projectRoot
$env:OPENCODE_CONFIG_CONTENT = '{"share":"disabled","permission":{"external_directory":"deny"}}'
& opencode run --model google/antigravity-gemini-3.8-flash --variant high --format json --title 'Final scoped review admin pause popup' --file (Join-Path $PSScriptRoot 'task-final-review-fresh.txt') --auto 'This is a fresh, scoped final repair pass. Read the attached concrete findings; implement them directly without redoing completed research. Preserve passing backend and independent tests. Complete dialog identity/error fixes, concurrency runner, npm integration, final tests and APK, and update handover.' *> (Join-Path $PSScriptRoot 'opencode-final-review-events.jsonl')
$cliExit = $LASTEXITCODE
Write-Output ('OpenCode final review exit code: ' + $cliExit)
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'opencode-final-review-events.jsonl') -Tail 5 | ForEach-Object {
    try {
        $event = $_ | ConvertFrom-Json
        if ($event.type -eq 'text') { Write-Output $event.part.text }
        elseif ($event.type -eq 'error') { Write-Output ($event.error | ConvertTo-Json -Compress -Depth 3) }
    } catch {}
}
exit $cliExit
