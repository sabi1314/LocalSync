param(
    [string]$GameDir = 'D:\.minecraft\versions\26.1.2-Fabric',
    [string]$MinecraftRoot = 'D:\.minecraft',
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin',
    [switch]$Live
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path

& (Join-Path $ProjectDir 'build.ps1') `
    -GameDir $GameDir -MinecraftRoot $MinecraftRoot -JdkBin $JdkBin

& (Join-Path $ProjectDir 'test.ps1') -JdkBin $JdkBin
& (Join-Path $ProjectDir 'test-screen.ps1') -JdkBin $JdkBin
& (Join-Path $ProjectDir 'test-hud.ps1') -JdkBin $JdkBin
& (Join-Path $ProjectDir 'test-bilibili.ps1') `
    -JdkBin $JdkBin -SearchNetwork:$Live
& (Join-Path $ProjectDir 'test-account.ps1') `
    -MinecraftRoot $MinecraftRoot -JdkBin $JdkBin -LiveQr:$Live
& (Join-Path $ProjectDir 'test-autoplay.ps1') `
    -JdkBin $JdkBin -Live:$Live

$CoverCacheTest = Join-Path $ProjectDir 'test-cover-cache.ps1'
if (Test-Path -LiteralPath $CoverCacheTest -PathType Leaf) {
    & $CoverCacheTest -JdkBin $JdkBin
}

if ($Live) {
    Write-Output 'PASS LocalSync: build, offline suite, and live probes'
} else {
    Write-Output 'PASS LocalSync: build and offline suite'
}
