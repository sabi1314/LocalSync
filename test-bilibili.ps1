param(
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin',
    [switch]$SearchNetwork
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ArgumentsFile = Join-Path $ProjectDir 'build\javac.args'
$ClassPath = (Get-Content -LiteralPath $ArgumentsFile)[5].Trim('"')
$ClassPath = (Join-Path $ProjectDir 'build\classes') + ';' + $ClassPath
$TestClasses = Join-Path $ProjectDir 'build\test-classes'
$Sources = @(
    (Join-Path $ProjectDir 'src\main\java\dev\localsync\client\BilibiliHttp.java'),
    (Join-Path $ProjectDir 'src\test\java\dev\localsync\client\BilibiliResolverProbe.java'),
    (Join-Path $ProjectDir 'src\test\java\dev\localsync\client\BilibiliHttpProbe.java'),
    (Join-Path $ProjectDir 'src\test\java\dev\localsync\client\BilibiliLiveResolverProbe.java')
)

& (Join-Path $JdkBin 'javac.exe') --release 25 -encoding UTF-8 `
    -classpath $ClassPath -d $TestClasses $Sources
if ($LASTEXITCODE -ne 0) {
    throw "Bilibili probe compilation failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') `
    -classpath ($TestClasses + ';' + $ClassPath) `
    dev.localsync.client.BilibiliResolverProbe
if ($LASTEXITCODE -ne 0) {
    throw "Bilibili probe failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') `
    -classpath ($TestClasses + ';' + $ClassPath) `
    dev.localsync.client.BilibiliLiveResolverProbe
if ($LASTEXITCODE -ne 0) {
    throw "Bilibili live resolver probe failed with exit code $LASTEXITCODE"
}

if ($SearchNetwork) {
    & (Join-Path $JdkBin 'java.exe') `
        -classpath ($TestClasses + ';' + $ClassPath) `
        dev.localsync.client.BilibiliHttpProbe
    if ($LASTEXITCODE -ne 0) {
        throw "Bilibili live transport probe failed with exit code $LASTEXITCODE"
    }
    & (Join-Path $JdkBin 'java.exe') `
        -classpath ($TestClasses + ';' + $ClassPath) `
        dev.localsync.client.BilibiliLiveResolverProbe --live
    if ($LASTEXITCODE -ne 0) {
        throw "Bilibili live stream probe failed with exit code $LASTEXITCODE"
    }
}
