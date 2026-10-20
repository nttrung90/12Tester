# migrate-antigravity.ps1
# Run this in a NEW PowerShell window, NOT inside OpenCode.
# It stops every OpenCode process, then migrates the legacy
# antigravity-accounts.json into the new account store.

$ErrorActionPreference = "Stop"

Write-Host "=== Step 1: stopping all OpenCode processes ===" -ForegroundColor Cyan
$procs = Get-Process OpenCode, opencode-cli -ErrorAction SilentlyContinue
if ($procs) {
    $procs | Stop-Process -Force
    Start-Sleep -Seconds 3
}
$remaining = Get-Process OpenCode, opencode-cli -ErrorAction SilentlyContinue
if ($remaining) {
    Write-Host "Still running - close them manually, then run this script again:" -ForegroundColor Red
    $remaining | Format-Table Id, ProcessName
    exit 1
}
Write-Host "OK - no OpenCode process is running." -ForegroundColor Green

Write-Host ""
Write-Host "=== Step 2: migrating legacy accounts ===" -ForegroundColor Cyan
npx -y "@cortexkit/opencode-antigravity-auth@latest" migrate --offline --yes

Write-Host ""
Write-Host "=== Step 3: verifying account store ===" -ForegroundColor Cyan
npx -y "@cortexkit/opencode-antigravity-auth@latest" list --json

Write-Host ""
Write-Host "=== DONE ===" -ForegroundColor Green
Write-Host "Now start OpenCode again and run this in a terminal:" -ForegroundColor Yellow
Write-Host "  opencode auth login" -ForegroundColor White
Write-Host "Choose: Google -> Google (antigravity-auth pool, browser)" -ForegroundColor White
Write-Host "Then finish the browser sign-in and pick the saved 'Antigravity' connection." -ForegroundColor White
Write-Host "After that open /antigravity in OpenCode to check Accounts and Quota." -ForegroundColor White
