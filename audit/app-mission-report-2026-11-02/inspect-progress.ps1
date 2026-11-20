param([string]$LogName = 'opencode-round2', [int]$Tail = 30, [switch]$SkipState)
$eventFile = Join-Path $PSScriptRoot ($LogName + '-events.jsonl')
$errorFile = Join-Path $PSScriptRoot ($LogName + '-stderr.log')
if (Test-Path -LiteralPath $eventFile) {
    Get-Item -LiteralPath $eventFile | Select-Object Length, LastWriteTimeUtc | ConvertTo-Json -Compress
    $events = @(Get-Content -LiteralPath $eventFile -Tail $Tail | ForEach-Object { try { $_ | ConvertFrom-Json } catch {} })
    $events | Where-Object { $_.type -in @('text','tool_use','error') } | Select-Object -Last 7 | ForEach-Object {
        if ($_.type -eq 'tool_use') {
            $command = [string]$_.part.state.input.command
            [pscustomobject]@{tool=$_.part.tool;status=$_.part.state.status;file=$_.part.state.input.filePath;command=$command.Substring(0,[Math]::Min(500,$command.Length));exit=$_.part.state.metadata.exit;error=$_.part.state.error} | ConvertTo-Json -Compress -Depth 2
        } elseif ($_.type -eq 'text') {
            $body = [string]$_.part.text
            'TEXT: ' + $body.Substring(0,[Math]::Min(1400,$body.Length))
        } else { $_.error | ConvertTo-Json -Compress -Depth 3 }
    }
}
if (Test-Path -LiteralPath $errorFile) { Get-Content -LiteralPath $errorFile -Tail 8 }
$stateFile = Join-Path $PSScriptRoot 'STATE.md'
if (-not $SkipState -and (Test-Path -LiteralPath $stateFile)) {
    $state = Get-Item -LiteralPath $stateFile
    if ($state.LastWriteTimeUtc -gt [DateTime]::UtcNow.AddMinutes(-5)) { Get-Content -LiteralPath $stateFile -Tail 18 }
}
