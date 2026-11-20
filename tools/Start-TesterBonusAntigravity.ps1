[CmdletBinding()]
param(
    [string] $Model
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$promptPath = Join-Path $projectRoot 'PROMPT_ANTIGRAVITY_THUONG_THEM_CHO_TESTER_2026-11-11.md'
$logDirectory = Join-Path $projectRoot '.tmp'
$agyCommand = Get-Command agy -ErrorAction SilentlyContinue
if ($null -eq $agyCommand) {
    throw 'Antigravity CLI (agy) was not found in PATH. Open a terminal where agy --help works.'
}
if (-not (Test-Path -LiteralPath $promptPath -PathType Leaf)) {
    throw "Task prompt is missing: $promptPath"
}

Push-Location -LiteralPath $projectRoot
try {
    if ([string]::IsNullOrWhiteSpace($Model)) {
        & $agyCommand.Source models
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to list models. Run this script in your normal signed-in Windows PowerShell session.'
        }
        Write-Host ''
        Write-Host 'Run again with -Model and the exact Gemini 3.8 Flash model identifier/label shown above. The script sets --effort high.'
        Write-Host 'No coding task has been submitted.'
        return
    }

    if ($Model -notmatch '(?i)gemini' -or $Model -notmatch '3[._ -]?8' -or $Model -notmatch '(?i)flash') {
        throw 'This task requires Gemini 3.8 Flash. Use the exact identifier from agy models; no fallback model will be selected by this script.'
    }

    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $cliLogPath = Join-Path $logDirectory "antigravity-tester-bonus-$runStamp.log"
    $resultPath = Join-Path $logDirectory "antigravity-tester-bonus-$runStamp.jsonl"
    $taskPrompt = Get-Content -LiteralPath $promptPath -Raw -Encoding utf8

    Write-Host "Workspace: $projectRoot"
    Write-Host "Requested model: $Model; effort: high"
    Write-Host "Result log: $resultPath"
    & $agyCommand.Source --model $Model --effort high --mode auto --log-file $cliLogPath --output-format stream-json --print $taskPrompt |
        Tee-Object -FilePath $resultPath
    if ($LASTEXITCODE -ne 0) {
        throw "Antigravity exited with code $LASTEXITCODE. Check the result and CLI logs; completion has not been verified."
    }
    Write-Host 'CLI turn ended. Review the verification evidence and remaining blockers in its result before treating the feature as complete.'
}
finally {
    Pop-Location
}
