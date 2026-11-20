$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$results=Join-Path $root 'app/build/test-results/testDebugUnitTest'
$rows=@(foreach($file in (Get-ChildItem -LiteralPath $results -Filter 'TEST-*.xml' -File)) {
  [xml]$document=Get-Content -LiteralPath $file.FullName -Raw
  $suite=$document.testsuite
  [pscustomobject]@{name=[string]$suite.name;tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped;timestamp=[string]$suite.timestamp;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
})
$totals=[ordered]@{suites=$rows.Count;tests=0;failures=0;errors=0;skipped=0}
foreach($item in $rows){foreach($key in @('tests','failures','errors','skipped')){$totals[$key]+=$item.$key}}
if($totals.tests -lt 716 -or $totals.failures -ne 0 -or $totals.errors -ne 0 -or $totals.skipped -ne 0){throw ('Full unit results are incomplete/failing: '+($totals|ConvertTo-Json -Compress))}
$summary=[ordered]@{collectedAt=[datetime]::UtcNow.ToString('o');totals=$totals;suites=$rows}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'android-full-final-summary.json')
foreach($name in @('SupervisorImmediateQuotaRaceTest','ImmediateDailyQuotaUnitTest')) {
  $files=@(Get-ChildItem -LiteralPath $results -Filter ('TEST-*.'+$name+'.xml') -File)
  if($files.Count -ne 1){throw ('Expected one final report for '+$name)}
  Copy-Item -LiteralPath $files[0].FullName -Destination (Join-Path $PSScriptRoot ('final-'+$name+'.xml'))
}
$totals | ConvertTo-Json -Compress | Write-Output
