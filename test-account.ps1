param(
    [string]$MinecraftRoot = 'D:\.minecraft',
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin',
    [switch]$LiveQr
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TestClasses = Join-Path $ProjectDir 'build\account-test-classes'
$GsonJar = Get-ChildItem -LiteralPath (Join-Path $MinecraftRoot 'libraries\com\google\code\gson\gson') `
    -Filter 'gson-*.jar' -File -Recurse | Sort-Object FullName -Descending | Select-Object -First 1
if ($null -eq $GsonJar) {
    throw 'Gson dependency was not found in the Minecraft libraries directory.'
}
$Slf4jJar = Get-ChildItem -LiteralPath (Join-Path $MinecraftRoot 'libraries\org\slf4j\slf4j-api') `
    -Filter 'slf4j-api-*.jar' -File -Recurse | Sort-Object FullName -Descending | Select-Object -First 1
if ($null -eq $Slf4jJar) {
    throw 'SLF4J dependency was not found in the Minecraft libraries directory.'
}
$TestClassPath = $GsonJar.FullName + ';' + $Slf4jJar.FullName

New-Item -ItemType Directory -Force -Path $TestClasses | Out-Null
$Sources = @(
    (Join-Path $ProjectDir 'src\main\java\dev\localsync\client\BilibiliAccountData.java'),
    (Join-Path $ProjectDir 'src\main\java\dev\localsync\client\BilibiliHttp.java'),
    (Join-Path $ProjectDir 'src\main\java\dev\localsync\client\BilibiliSessionStore.java'),
    (Join-Path $ProjectDir 'src\main\java\dev\localsync\client\BilibiliAccountService.java'),
    (Join-Path $ProjectDir 'src\test\java\dev\localsync\LocalSyncMod.java'),
    (Join-Path $ProjectDir 'src\test\java\net\fabricmc\loader\api\FabricLoader.java'),
    (Join-Path $ProjectDir 'src\test\java\dev\localsync\client\BilibiliAccountDataTest.java'),
    (Join-Path $ProjectDir 'src\test\java\dev\localsync\client\BilibiliAccountServiceRaceTest.java')
)
& (Join-Path $JdkBin 'javac.exe') --release 25 -encoding UTF-8 `
    -classpath $TestClassPath -d $TestClasses $Sources
if ($LASTEXITCODE -ne 0) {
    throw "account test compilation failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') -ea `
    -classpath ($TestClasses + ';' + $TestClassPath) `
    dev.localsync.client.BilibiliAccountDataTest
if ($LASTEXITCODE -ne 0) {
    throw "account tests failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') -ea `
    -classpath ($TestClasses + ';' + $TestClassPath) `
    dev.localsync.client.BilibiliAccountServiceRaceTest
if ($LASTEXITCODE -ne 0) {
    throw "account race tests failed with exit code $LASTEXITCODE"
}

if ($LiveQr) {
    $Headers = @{
        'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36'
        'Referer' = 'https://www.bilibili.com/'
    }
    $Generated = Invoke-RestMethod `
        -Uri 'https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header' `
        -Headers $Headers -Method Get -TimeoutSec 20
    if ($Generated.code -ne 0 -or $Generated.data.qrcode_key.Length -lt 16) {
        throw 'live QR generation returned an invalid response'
    }
    if (-not $Generated.data.url.Contains('from=main-fe-header')) {
        throw 'live QR generation omitted the official login source marker'
    }
    $PollUri = 'https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=' `
        + [Uri]::EscapeDataString($Generated.data.qrcode_key)
    $Polled = Invoke-RestMethod -Uri $PollUri -Headers $Headers -Method Get -TimeoutSec 20
    if ($Polled.code -ne 0 -or $Polled.data.code -notin @(86101, 86090, 86038, 0)) {
        throw 'live QR polling returned an unexpected response'
    }
    Write-Output "PASS BilibiliQrLive: generated=true, pollState=$($Polled.data.code), credentials=redacted"
}
