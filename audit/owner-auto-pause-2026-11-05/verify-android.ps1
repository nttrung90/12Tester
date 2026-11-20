param(
    [ValidateSet('focused', 'all', 'build')]
    [string]$Mode = 'all',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')]
    [string]$LogName = 'supervisor-android'
)
$ErrorActionPreference = 'Stop'
$taskGradleArguments = switch ($Mode) {
    'focused' {
        @(':app:testDebugUnitTest',
          '--tests', 'com.ntt.crosstest.ui.viewmodels.OwnerAutoPauseResumeHardeningTest',
          '--tests', 'com.ntt.crosstest.ui.viewmodels.OwnerDailyRefreshRaceReviewTest',
          '--tests', 'com.ntt.crosstest.ui.viewmodels.OwnerDailyTaskOrderingReviewTest',
          '--tests', 'com.ntt.crosstest.ui.viewmodels.AdminOwnerAutoPauseViewModelTest',
          '--tests', 'com.ntt.crosstest.data.AdminOwnerAutoPauseTest', '--rerun')
    }
    'all' { @(':app:testDebugUnitTest', '--rerun') }
    'build' { @(':app:assembleDebug') }
}
# The array is passed inside PowerShell, avoiding native powershell -File's
# argument-array truncation. All public CLI arguments to this script are scalar.
& (Join-Path $PSScriptRoot 'run-gradle-logged.ps1') -LogName $LogName -GradleArguments $taskGradleArguments
exit $LASTEXITCODE
