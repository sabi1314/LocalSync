param(
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ArgumentsFile = Join-Path $ProjectDir 'build\javac.args'
$ClassPath = (Get-Content -LiteralPath $ArgumentsFile)[5].Trim('"')
$ClassPath = (Join-Path $ProjectDir 'build\classes') + ';' + $ClassPath
$TestClasses = Join-Path $ProjectDir 'build\test-classes'
$CacheSource = Join-Path $ProjectDir `
    'src\main\java\dev\localsync\client\BilibiliCoverCache.java'
$HttpSource = Join-Path $ProjectDir `
    'src\main\java\dev\localsync\client\BilibiliHttp.java'
$Source = Join-Path $ProjectDir `
    'src\test\java\dev\localsync\client\BilibiliCoverCacheTest.java'

New-Item -ItemType Directory -Force -Path $TestClasses | Out-Null
& (Join-Path $JdkBin 'javac.exe') --release 25 -encoding UTF-8 `
    -classpath $ClassPath -d $TestClasses $HttpSource $CacheSource $Source
if ($LASTEXITCODE -ne 0) {
    throw "cover cache test compilation failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') -ea `
    -classpath ($TestClasses + ';' + $ClassPath) `
    dev.localsync.client.BilibiliCoverCacheTest
if ($LASTEXITCODE -ne 0) {
    throw "cover cache test failed with exit code $LASTEXITCODE"
}
