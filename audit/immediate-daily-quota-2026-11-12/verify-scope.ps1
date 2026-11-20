param([switch]$AllowDebugApk)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$manifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'before-manifest.json') -Raw | ConvertFrom-Json
$changed=@()
foreach($item in $manifest) {
  $path=Join-Path $root $item.path
  if(!(Test-Path -LiteralPath $path)) {$changed+=[pscustomobject]@{path=$item.path;change='deleted'};continue}
  if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $item.sha256) {$changed+=[pscustomobject]@{path=$item.path;change='modified'}}
}
$changed | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'modified-baseline-files.json')
$forbidden=@($changed | Where-Object {$_.path -match '^supabase/migrations[\\/](0[6-8][0-9])_' -or $_.path -eq 'app/build.gradle.kts'})
if($forbidden.Count) {throw ('Protected baseline files changed: '+($forbidden.path -join ', '))}
$before=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'packages-before.json') -Raw | ConvertFrom-Json
if($AllowDebugApk) {
  # The user explicitly permitted test APK/AAB creation after the original no-APK scope.
  $before=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'packages-before-authorized-debug.json') -Raw | ConvertFrom-Json
}
$packages=@(Get-ChildItem -LiteralPath $root -File -Recurse -Include *.apk,*.aab -ErrorAction Stop | ForEach-Object {
  [pscustomobject]@{path=$_.FullName;length=$_.Length;lastWriteUtc=$_.LastWriteTimeUtc.ToString('o');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
})
$packages | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'packages-after.json')
Write-Output ('Modified pre-existing source files: '+$changed.Count)
$changed | Format-Table -AutoSize
foreach($item in $before) {
  $now=@($packages | Where-Object {$_.path -eq $item.path})
  if($AllowDebugApk -and $item.path -eq (Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk')) {
    if($now.Count -ne 1 -or $now[0].length -le 0) {throw 'Authorized debug APK is missing/empty'}
    continue
  }
  if($now.Count -ne 1 -or $now[0].sha256 -ne $item.sha256 -or $now[0].length -ne $item.length -or ([datetime]$now[0].lastWriteUtc).ToUniversalTime().Ticks -ne ([datetime]$item.lastWriteUtc).ToUniversalTime().Ticks) {throw ('Package changed: '+$item.path)}
}
$cutoff=(Get-Item -LiteralPath (Join-Path $PSScriptRoot 'before-manifest.json')).LastWriteTimeUtc
$extra=@($packages | Where-Object {$_.path -notin $before.path})
foreach($item in $extra) {if([datetime]$item.lastWriteUtc -ge $cutoff) {throw ('Unexpected recently generated package: '+$item.path)}}
if($AllowDebugApk) {
  if($extra.Count -gt 0) {throw 'Unexpected APK/AAB output outside authorized debug APK'}
  Write-Output 'PASS: Authorized debug APK exists; all six other legacy APK/AAB artifacts retain their hashes, sizes and times.'
} else {
  Write-Output ('PASS: All '+$before.Count+' baseline output APK/AAB hashes, sizes and times unchanged; '+$extra.Count+' additional legacy artifacts have timestamps before task snapshot; no recently generated packages.')
}
