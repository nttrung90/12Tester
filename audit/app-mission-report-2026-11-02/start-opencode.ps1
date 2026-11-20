param([string]$SessionId = '', [string]$PromptFile = 'task-opencode-implementation.txt', [string]$LogName = 'opencode-implementation', [string]$AttachUrl = '')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$promptPath = Join-Path $PSScriptRoot $PromptFile
$eventPath = Join-Path $PSScriptRoot ($LogName + '-events.jsonl')
$errorPath = Join-Path $PSScriptRoot ($LogName + '-stderr.log')
$env:OPENCODE_PERMISSION = '{"external_directory":"deny","read":{"*.env":"deny","*.env.*":"deny","*antigravity-accounts*":"deny","*local.properties":"deny","*google-services.json":"deny","*.jks":"deny","*.keystore":"deny"}}'
$cliArguments = @('run', 'Read the attached task and implement it completely. Keep progress and all verification evidence in the task audit folder.', '--model', 'google/antigravity-gemini-3.8-flash', '--variant', 'medium', '--format', 'json', '--dir', $projectRoot, '--title', 'App mission reports 3 images daily quota', '--file', $promptPath)
if ($SessionId) { $cliArguments += @('--session', $SessionId) }
if ($AttachUrl) { $cliArguments += @('--attach', $AttachUrl) }
Push-Location -LiteralPath $projectRoot
try {
    & opencode @cliArguments 1> $eventPath 2> $errorPath
    $cliExit = $LASTEXITCODE
    Write-Output ('OpenCode exit: ' + $cliExit)
    Write-Output ('Events: ' + $eventPath)
    Write-Output ('Errors: ' + $errorPath)
    exit $cliExit
} finally { Pop-Location }
