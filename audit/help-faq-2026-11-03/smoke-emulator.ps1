$ErrorActionPreference = 'Stop'
$sdkSetting = (Select-String -LiteralPath local.properties -Pattern '^sdk.dir=' | Select-Object -First 1).Line
$sdk = $sdkSetting -replace '^sdk.dir=' -replace '\\:', ':' -replace '\\\\', '\'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$target = 'emulator-5568'

function Invoke-Device {
    $result = & $adb -s $target @args
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $args" }
    $result
}

function Read-Screen {
    Invoke-Device shell uiautomator dump /sdcard/help-smoke-window.xml | Out-Null
    [xml]$screen = Invoke-Device shell cat /sdcard/help-smoke-window.xml | Out-String
    $screen.SelectNodes('//node')
}

function Assert-TextVisible($nodes, [string]$text) {
    if (!($nodes | Where-Object { $_.GetAttribute('text').Contains($text) })) {
        throw "Expected visible text: $text"
    }
}

function Assert-TextHidden($nodes, [string]$text) {
    if ($nodes | Where-Object { $_.GetAttribute('text').Contains($text) }) {
        throw "Expected hidden text: $text"
    }
}

function Touch-Text($nodes, [string]$text) {
    $node = $nodes | Where-Object { $_.GetAttribute('text') -eq $text } | Select-Object -First 1
    if (!$node) { throw "Tap target missing: $text" }
    $bounds = [regex]::Matches($node.GetAttribute('bounds'), '\d+').Value
    $x = [int](([int]$bounds[0] + [int]$bounds[2]) / 2)
    $y = [int](([int]$bounds[1] + [int]$bounds[3]) / 2)
    Invoke-Device shell input tap $x $y
    Start-Sleep -Milliseconds 300
}

$guide = 'Hướng dẫn sử dụng'
$faq = 'Câu hỏi thường gặp'
$firstQuestion = '1. Tôi có cần có app riêng mới tham gia được không?'
$firstAnswer = 'Bạn có thể đăng nhập và kiểm thử app của cộng đồng'
$secondQuestion = '2. Tôi quên mật khẩu hoặc chưa nhận được thư xác nhận thì làm gì?'
$secondAnswer = 'Nếu dùng tài khoản email'

Invoke-Device shell cmd connectivity airplane-mode enable
Invoke-Device shell svc wifi disable
Invoke-Device shell am start -W -n 'com.ntt.crosstest/.HelpSmokeActivity' | Out-Host
$nodes = Read-Screen
Assert-TextVisible $nodes '12 Cross Testers là ứng dụng Android'
Assert-TextHidden $nodes $firstQuestion
Touch-Text $nodes $faq
$nodes = Read-Screen
Assert-TextVisible $nodes $firstQuestion
Assert-TextHidden $nodes $firstAnswer
Touch-Text $nodes $firstQuestion
$nodes = Read-Screen
Assert-TextVisible $nodes $firstAnswer
Touch-Text $nodes $guide
$nodes = Read-Screen
Assert-TextVisible $nodes '12 Cross Testers là ứng dụng Android'
Touch-Text $nodes $faq
$nodes = Read-Screen
Assert-TextVisible $nodes $firstAnswer
Invoke-Device shell wm user-rotation lock 1
Start-Sleep -Seconds 2
Invoke-Device shell wm user-rotation lock 0
Start-Sleep -Seconds 2
$nodes = Read-Screen
Assert-TextVisible $nodes $firstAnswer
Touch-Text $nodes $secondQuestion
$nodes = Read-Screen
Assert-TextHidden $nodes $firstAnswer
Assert-TextVisible $nodes $secondAnswer
Touch-Text $nodes $secondQuestion
$nodes = Read-Screen
Assert-TextHidden $nodes $secondAnswer
Write-Output 'PASS: default Guide, FAQ initially collapsed, tab changes and rotation preserve answer, one answer at a time, repeated tap closes, airplane mode.'
