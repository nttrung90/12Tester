param([Parameter(Mandatory=$true)][string]$SnapshotName)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$snapshot=Join-Path (Join-Path $PSScriptRoot 'test-result-snapshots') $SnapshotName
$run=Get-Content -LiteralPath (Join-Path $snapshot 'run.json') -Raw | ConvertFrom-Json
if ($run.exitCode -ne 0) { throw 'Final Android verification did not pass.' }
$totals=@{suites=0;tests=0;failures=0;errors=0;skipped=0}
foreach($f in Get-ChildItem -LiteralPath $snapshot -Filter 'TEST-*.xml' -File){
    $suite=([xml](Get-Content -LiteralPath $f.FullName -Raw)).testsuite
    $totals.suites++
    foreach($key in @('tests','failures','errors','skipped')){$totals[$key]+=[int]$suite.$key}
}
if ($totals.tests -lt 542 -or $totals.failures -ne 0 -or $totals.errors -ne 0 -or $totals.skipped -ne 0){throw 'Missing or failed final test evidence.'}
$apk=Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
$apkMeta=Get-Content -LiteralPath (Join-Path (Split-Path $apk) 'output-metadata.json') -Raw|ConvertFrom-Json
$before=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'before-manifest.json') -Raw|ConvertFrom-Json)
$checkpoint=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'round4-checkpoint-manifest.json') -Raw|ConvertFrom-Json)
$beforeMap=@{}
foreach($item in $before){$beforeMap[$item.path.Replace('\','/')]=$item.sha256}
$paths=@($before.path+$checkpoint.path|ForEach-Object{$_.Replace('\','/')}|Sort-Object -Unique)
$changes=@();$manifest=@()
foreach($path in $paths){
    $file=Join-Path $projectRoot $path
    $hash=if(Test-Path -LiteralPath $file -PathType Leaf){(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash}else{$null}
    $manifest+=[pscustomobject]@{path=$path;sha256=$hash}
    $old=$beforeMap[$path]
    if($hash -ne $old){$changes+=[pscustomobject]@{path=$path;status=if(!$hash){'missing'}elseif(!$old){'new'}else{'modified'};beforeSha256=$old;afterSha256=$hash}}
}
if(@($changes|Where-Object{$_.status -eq 'missing'}).Count -gt 0){throw 'An existing source file is missing.'}
$changes|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'root-source-changes-final.json')
$manifest|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'root-source-manifest-final.json')
$tests=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'independent-tests') -Filter '*.kt' -File|ForEach-Object{[pscustomobject]@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash;testMethods=@(Select-String -LiteralPath $_.FullName -Pattern '@Test').Count}})
$tests|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'root-independent-tests-final-manifest.json')
$migration=Join-Path $projectRoot 'supabase/migrations/074_app_mission_window_and_multi_attachments.sql'
$migrationHash=(Get-FileHash -LiteralPath $migration -Algorithm SHA256).Hash
if($migrationHash -ne 'ED7E17603DA72F6A2C7CAE9DE5B26B6E3A69C99CB6ABE6ED7E157126EE00B97A'){throw 'SQL source changed after the verified backend run.'}
$evidence=[pscustomobject]@{
    recordedUtc=[DateTime]::UtcNow.ToString('o');androidRun=$run;androidTotals=$totals
    independentRootTests=($tests|Measure-Object -Property testMethods -Sum).Sum
    apk=[pscustomobject]@{path=$apk;bytes=(Get-Item -LiteralPath $apk).Length;sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash;versionName=$apkMeta.elements[0].versionName;versionCode=$apkMeta.elements[0].versionCode}
    migrationSha256=$migrationHash
    sourceChanges=@($changes|Group-Object status|ForEach-Object{[pscustomobject]@{status=$_.Name;count=$_.Count}})
    comparedKnownSourceFiles=$paths.Count
    productionDeployed=$false;physicalDeviceTested=$false;postgresMultiConnectionTested=$false
}
$evidence|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'root-final-verification.json')
$evidence|ConvertTo-Json -Depth 8
