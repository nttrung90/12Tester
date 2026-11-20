param([string]$SessionId = '', [string]$PromptFile = 'task-implementation.txt', [string]$LogName = 'implementation')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$promptPath = Join-Path $PSScriptRoot $PromptFile
$eventPath = Join-Path $PSScriptRoot ($LogName + '-events.jsonl')
$errorPath = Join-Path $PSScriptRoot ($LogName + '-stderr.log')
$env:OPENCODE_PERMISSION = '{"external_directory":"deny","read":{"*.env":"deny","*.env.*":"deny","*antigravity-accounts*":"deny","*local.properties":"deny","*google-services.json":"deny","*.jks":"deny","*.keystore":"deny"}}'
$cliArguments = @('run', 'Read the attached authorized task, implement all requirements and verify until complete. Record evidence in audit/carryover-fix-2026-11-05.', '--model', 'google/antigravity-gemini-3.8-flash', '--variant', 'high', '--format', 'json', '--dir', $projectRoot, '--title', 'Fix daily carryover debt and task reporting', '--file', $promptPath)
if ($SessionId) { $cliArguments += @('--session', $SessionId) }
Push-Location -LiteralPath $projectRoot
try {
    & opencode @cliArguments 1> $eventPath 2> $errorPath
    $cliExit = $LASTEXITCODE
    Write-Output ('OpenCode exit: ' + $cliExit)
    Write-Output ('Events: ' + $eventPath)
    Write-Output ('Errors: ' + $errorPath)
    exit $cliExit
} finally { Pop-Location }
