param(
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin',
    [switch]$Live
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ArgumentsFile = Join-Path $ProjectDir 'build\javac.args'
$ClassPath = (Get-Content -LiteralPath $ArgumentsFile)[5].Trim('"')
$TestClasses = Join-Path $ProjectDir 'build\test-classes'
$ResolverSource = Join-Path $ProjectDir `
    'src\main\java\dev\localsync\client\BilibiliAutoplayResolver.java'
$HttpSource = Join-Path $ProjectDir `
    'src\main\java\dev\localsync\client\BilibiliHttp.java'
$ProbeSource = Join-Path $ProjectDir `
    'src\test\java\dev\localsync\client\BilibiliAutoplayResolverProbe.java'

New-Item -ItemType Directory -Force -Path $TestClasses | Out-Null
& (Join-Path $JdkBin 'javac.exe') --release 25 -encoding UTF-8 `
    -classpath $ClassPath -d $TestClasses $HttpSource $ResolverSource $ProbeSource
if ($LASTEXITCODE -ne 0) {
    throw "autoplay probe compilation failed with exit code $LASTEXITCODE"
}
& (Join-Path $JdkBin 'java.exe') -ea `
    -classpath ($TestClasses + ';' + $ClassPath) `
    dev.localsync.client.BilibiliAutoplayResolverProbe
if ($LASTEXITCODE -ne 0) {
    throw "autoplay probe failed with exit code $LASTEXITCODE"
}

if ($Live) {
    $Headers = @{
        'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36'
        'Referer' = 'https://www.bilibili.com/'
        'Origin' = 'https://www.bilibili.com'
    }
    $MultiBvid = 'BV1ZKwRe5EMm'
    $Multi = Invoke-RestMethod `
        -Uri "https://api.bilibili.com/x/web-interface/view?bvid=$MultiBvid" `
        -Headers $Headers -TimeoutSec 20
    if ($Multi.code -ne 0 -or $Multi.data.pages.Count -lt 2 `
            -or $Multi.data.pages[1].page -ne 2) {
        throw 'live multi-page metadata did not expose p=2'
    }
    $SeasonBvid = 'BV1PybV6QEsF'
    $Season = Invoke-RestMethod `
        -Uri "https://api.bilibili.com/x/web-interface/view?bvid=$SeasonBvid" `
        -Headers $Headers -TimeoutSec 20
    if ($Season.code -ne 0 -or $Season.data.ugc_season.sections.Count -eq 0 `
            -or $Season.data.ugc_season.sections[0].episodes.Count -lt 2) {
        throw 'live UGC season metadata did not expose ordered episodes'
    }
    $Related = Invoke-RestMethod `
        -Uri 'https://api.bilibili.com/x/web-interface/archive/related?bvid=BV1xx411c7mD' `
        -Headers $Headers -TimeoutSec 20
    if ($Related.code -ne 0 -or $Related.data.Count -eq 0 `
            -or [string]::IsNullOrWhiteSpace($Related.data[0].bvid)) {
        throw 'live related endpoint returned no playable recommendation'
    }
    Write-Output 'PASS BilibiliAutoplayLive: multi-page, UGC season, related metadata'
}
