param([string]$ConversationId='', [string]$PromptFile='TASK_IMPLEMENTATION.txt', [string]$RunName='implementation')
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$prompt=Get-Content -LiteralPath (Join-Path $PSScriptRoot $PromptFile) -Raw
$settingsPath=Join-Path $PSScriptRoot 'SESSION_SETTINGS.json'
$arguments=@('--model','gemini-3.8-flash-high','--effort','high','--mode','accept-edits','--settings',$settingsPath,'--output-format','stream-json','--disable-slash-commands','--log-file',(Join-Path $PSScriptRoot ($RunName+'-cli.log')),'--print',$prompt)
if($ConversationId){$arguments+=@('--conversation',$ConversationId)}
Push-Location -LiteralPath $workspace
try {
    & agy @arguments 1> (Join-Path $PSScriptRoot ($RunName+'.ndjson')) 2> (Join-Path $PSScriptRoot ($RunName+'-stderr.log'))
    $runExit=$LASTEXITCODE
    Write-Output ('Antigravity exit: '+$runExit)
    Write-Output ('Output: '+(Join-Path $PSScriptRoot ($RunName+'.ndjson')))
    exit $runExit
} finally { Pop-Location }
