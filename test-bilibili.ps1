param(
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ArgumentsFile = Join-Path $ProjectDir 'build\javac.args'
$ClassPath = (Get-Content -LiteralPath $ArgumentsFile)[5].Trim('"')
$ClassPath = (Join-Path $ProjectDir 'build\classes') + ';' + $ClassPath
$TestClasses = Join-Path $ProjectDir 'build\test-classes'
$Source = Join-Path $ProjectDir 'src\test\java\dev\localsync\client\BilibiliResolverProbe.java'

& (Join-Path $JdkBin 'javac.exe') --release 25 -encoding UTF-8 `
    -classpath $ClassPath -d $TestClasses $Source
if ($LASTEXITCODE -ne 0) {
    throw "Bilibili probe compilation failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') --add-modules jdk.httpserver `
    -classpath ($TestClasses + ';' + $ClassPath) dev.localsync.client.BilibiliResolverProbe
if ($LASTEXITCODE -ne 0) {
    throw "Bilibili probe failed with exit code $LASTEXITCODE"
}
