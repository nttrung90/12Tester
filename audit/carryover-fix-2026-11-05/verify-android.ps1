param(
    [ValidateSet('focused','all','build')][string]$Mode='all',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$LogName='supervisor-android'
)
$ErrorActionPreference='Stop'
$taskGradleArguments=switch($Mode) {
    'focused' { @(':app:testDebugUnitTest','--tests','com.ntt.crosstest.data.DailyTaskCarryoverUnitTest','--tests','com.ntt.crosstest.ui.viewmodels.OwnerAutoPauseResumeHardeningTest','--rerun') }
    'all' { @(':app:testDebugUnitTest','--rerun') }
    'build' { @(':app:assembleDebug') }
}
& (Join-Path $PSScriptRoot 'run-gradle-logged.ps1') -LogName $LogName -GradleArguments $taskGradleArguments
exit $LASTEXITCODE
