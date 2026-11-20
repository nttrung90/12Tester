param(
    [ValidatePattern('^[a-zA-Z0-9_-]+$')]
    [string]$LogName = 'gradle-logged',
    [string[]]$GradleArguments = @(':app:compileDebugKotlin')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$gradleLog = Join-Path $PSScriptRoot ($LogName + '.log')
$env:JAVA_HOME = 'C:\Users\nguye\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$buildMutex = [System.Threading.Mutex]::new($false, 'Local\Codex12TesterGradle')
$mutexOwned = $false
$gradleExit = 1
try {
    try {
        $mutexOwned = $buildMutex.WaitOne(300000)
    } catch [System.Threading.AbandonedMutexException] {
        $mutexOwned = $true
    }
    if (-not $mutexOwned) { throw 'Timed out waiting for another coordinated Gradle build.' }
    Push-Location -LiteralPath $projectRoot
    try {
        # Keep Gradle daemon child output out of the OpenCode tool's pipes.
        $previousErrorAction = $ErrorActionPreference
        $runStartedUtc = [DateTime]::UtcNow
        $ErrorActionPreference = 'Continue'
        try {
            & .\gradlew.bat --offline --no-daemon @GradleArguments *> $gradleLog
            $gradleExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        # Preserve this run's JUnit evidence before another mutex-protected build replaces it.
        if (@($GradleArguments | Where-Object { $_ -match '(^|:)testDebugUnitTest$' }).Count -gt 0) {
            $xmlRoot = Join-Path $projectRoot 'app/build/test-results/testDebugUnitTest'
            $freshResults = @()
            if (Test-Path -LiteralPath $xmlRoot) {
                $freshResults = @(Get-ChildItem -LiteralPath $xmlRoot -Filter 'TEST-*.xml' -File |
                    Where-Object { $_.LastWriteTimeUtc -ge $runStartedUtc.AddSeconds(-1) })
            }
            $snapshotName = $LogName + '-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
            $snapshotPath = Join-Path (Join-Path $PSScriptRoot 'test-result-snapshots') $snapshotName
            New-Item -ItemType Directory -Path $snapshotPath -Force | Out-Null
            foreach ($resultFile in $freshResults) {
                Copy-Item -LiteralPath $resultFile.FullName -Destination $snapshotPath
            }
            [pscustomobject]@{
                logName = $LogName
                arguments = $GradleArguments
                exitCode = $gradleExit
                startedUtc = $runStartedUtc.ToString('o')
                finishedUtc = [DateTime]::UtcNow.ToString('o')
                currentRunXmlCount = $freshResults.Count
            } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $snapshotPath 'run.json')
            Write-Output ('JUnit snapshot: ' + $snapshotPath)
        }
    } finally {
        Pop-Location
    }
    Write-Output ('Gradle log: ' + $gradleLog)
    Get-Content -LiteralPath $gradleLog -Tail 50
} finally {
    if ($mutexOwned) { $buildMutex.ReleaseMutex() }
    $buildMutex.Dispose()
}
exit $gradleExit
