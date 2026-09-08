param(
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ArgumentsFile = Join-Path $ProjectDir 'build\javac.args'
$ClassPath = (Get-Content -LiteralPath $ArgumentsFile)[5].Trim('"')
$ClassPath = (Join-Path $ProjectDir 'build\classes') + ';' + $ClassPath
$TestClasses = Join-Path $ProjectDir 'build\test-classes'
$Source = Join-Path $ProjectDir 'src\test\java\dev\localsync\client\HudSettingsTest.java'

& (Join-Path $JdkBin 'javac.exe') --release 25 -encoding UTF-8 `
    -classpath $ClassPath -d $TestClasses $Source
if ($LASTEXITCODE -ne 0) {
    throw "HUD settings test compilation failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') -ea -classpath ($TestClasses + ';' + $ClassPath) `
    dev.localsync.client.HudSettingsTest
if ($LASTEXITCODE -ne 0) {
    throw "HUD settings test failed with exit code $LASTEXITCODE"
}
