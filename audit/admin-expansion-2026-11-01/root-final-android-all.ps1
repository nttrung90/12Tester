$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/run-gradle-logged.ps1" -LogName root-final-android-all -GradleArguments @(
    '-I', 'audit/admin-expansion-2026-11-01/layout-review.init.gradle',
    ':app:testDebugUnitTest', ':app:assembleDebug'
)
exit $LASTEXITCODE
