param(
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TestClasses = Join-Path $ProjectDir 'build\test-classes'
$TimelineSource = Join-Path $ProjectDir 'src\main\java\dev\localsync\server\RoomTimeline.java'
$UrlSource = Join-Path $ProjectDir 'src\main\java\dev\localsync\server\UrlNormalizer.java'
$TestSource = Join-Path $ProjectDir 'src\test\java\dev\localsync\server\RoomTimelineTest.java'

New-Item -ItemType Directory -Force -Path $TestClasses | Out-Null
& (Join-Path $JdkBin 'javac.exe') --release 25 -encoding UTF-8 -d $TestClasses `
    $TimelineSource $UrlSource $TestSource
if ($LASTEXITCODE -ne 0) {
    throw "test compilation failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') -ea -classpath $TestClasses `
    dev.localsync.server.RoomTimelineTest
if ($LASTEXITCODE -ne 0) {
    throw "tests failed with exit code $LASTEXITCODE"
}
