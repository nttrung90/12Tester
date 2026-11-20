[CmdletBinding()]
param(
    [switch] $Continue,
    [string] $ConversationId = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$auditDir = Join-Path $projectRoot 'audit/daily-task-action-gate-2026-11-12'
$promptPath = Join-Path $projectRoot 'PROMPT_ANTIGRAVITY_CHAN_THAO_TAC_APP_2026-11-12.md'
$settingsPath = Join-Path $auditDir 'SESSION_SETTINGS.json'
$model = 'gemini-3.8-flash-high'
$agyCommand = Get-Command agy -ErrorAction SilentlyContinue
if ($null -eq $agyCommand) { throw 'Antigravity CLI (agy) is not available in PATH.' }
if (-not (Test-Path -LiteralPath $promptPath -PathType Leaf)) { throw "Missing prompt: $promptPath" }
if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) { throw "Missing session settings: $settingsPath" }
if ($Continue -and $ConversationId) { throw 'Select Continue or ConversationId, not both.' }
if ($ConversationId -and $ConversationId -notmatch '^[0-9a-fA-F-]{36}$') { throw 'ConversationId must be a conversation UUID.' }
New-Item -ItemType Directory -Path $auditDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$modelList = Join-Path $auditDir "models-$stamp.txt"
$modelError = Join-Path $auditDir "models-$stamp-stderr.log"
$modelLog = Join-Path $auditDir "models-$stamp-cli.log"

Push-Location -LiteralPath $projectRoot
try {
    Write-Host "Checking Antigravity model access from PowerShell. Workspace: $projectRoot"
    # Use the current signed-in session only. Never alter permission/auth settings.
    # PowerShell 5.1 can turn any native stderr progress message into a
    # terminating NativeCommandError when ErrorActionPreference is Stop.
    # Redirect at the process level and decide success from ExitCode instead.
    $modelProcess = Start-Process -FilePath $agyCommand.Source -ArgumentList @(
        '--log-file', ('"' + $modelLog + '"'), 'models'
    ) -WorkingDirectory $projectRoot -WindowStyle Hidden -Wait -PassThru `
        -RedirectStandardOutput $modelList -RedirectStandardError $modelError
    if ($modelProcess.ExitCode -ne 0) {
        throw "Cannot verify the requested model in this session. No implementation task was submitted. See $modelList and $modelError."
    }
    $available = Get-Content -LiteralPath $modelList -Raw -Encoding utf8
    if ($available -notmatch '(?m)^gemini-3\.8-flash-high(?:\s|$)') {
        throw "Requested model $model is unavailable. No substitute was selected and no task was submitted."
    }

    Write-Host 'Model access verified. Saving source fingerprints before implementation...'
    git status --short | Set-Content -LiteralPath (Join-Path $auditDir "before-status-$stamp.txt") -Encoding utf8
    $sourceRoots = @('app/src', 'supabase/migrations', 'supabase/tests', 'backend')
    $fingerprints = foreach ($relativeRoot in $sourceRoots) {
        Get-ChildItem -LiteralPath (Join-Path $projectRoot $relativeRoot) -Recurse -File |
            Where-Object { $_.FullName -notmatch '[\\/]node_modules[\\/]' } |
            ForEach-Object {
                [pscustomobject]@{
                    path = $_.FullName.Substring($projectRoot.Length + 1)
                    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
                }
            }
    }
    $fingerprints | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $auditDir "source-before-$stamp.json") -Encoding utf8
    $resultPath = Join-Path $auditDir "implementation-$stamp.ndjson"
    $cliLog = Join-Path $auditDir "implementation-$stamp-cli.log"
    $stderrPath = Join-Path $auditDir "implementation-$stamp-stderr.log"
    # A short ASCII bootstrap avoids Windows command-line quoting/encoding
    # problems with a multiline Vietnamese prompt. The agent reads the full file.
    $bootstrap = 'Read and follow the complete task in ' + $promptPath.Replace('\', '/') +
        '. Implement it fully, run the required tests, fix failures, and write the requested progress and result reports. Use only the selected Gemini 3.8 Flash high model.'
    Write-Host "Starting Antigravity: model=$model; effort=high; mode=accept-edits; workspace=$projectRoot"
    Write-Host "Session command permissions: $settingsPath"
    Write-Host "Evidence: $resultPath"
    $implementationArguments = @(
        '--model', $model, '--effort', 'high', '--mode', 'accept-edits',
        '--settings', ('"' + $settingsPath + '"'), '--disable-slash-commands',
        '--log-file', ('"' + $cliLog + '"'), '--output-format', 'stream-json',
        '--print', ('"' + $bootstrap + '"')
    )
    if ($ConversationId) { $implementationArguments += @('--conversation', $ConversationId) }
    elseif ($Continue) { $implementationArguments += '--continue' }
    $implementationProcess = Start-Process -FilePath $agyCommand.Source -ArgumentList $implementationArguments `
        -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $resultPath -RedirectStandardError $stderrPath
    Write-Host "Antigravity process: $($implementationProcess.Id). Progress is being written to $resultPath"
    $implementationProcess.WaitForExit()
    # Refresh the Process object before reading its final exit status.
    $implementationProcess.Refresh()
    if ($implementationProcess.ExitCode -ne 0) { throw "Antigravity exited with code $($implementationProcess.ExitCode). Work has not been verified; inspect $resultPath and $stderrPath." }
    Write-Host 'CLI turn ended. Independent review and tests are required before declaring completion.'
}
finally {
    Pop-Location
}
