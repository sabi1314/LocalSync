param(
    [string]$GameDir = 'D:\.minecraft\versions\26.1.2-Fabric',
    [string]$MinecraftRoot = 'D:\.minecraft',
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SourceDir = Join-Path $ProjectDir 'src\main\java'
$ResourceDir = Join-Path $ProjectDir 'src\main\resources'
$BuildDir = Join-Path $ProjectDir 'build'
$ClassesDir = Join-Path $BuildDir 'classes'
$LibDir = Join-Path $BuildDir 'libs'
$OutputJar = Join-Path $LibDir 'localsync-1.2.0.jar'

New-Item -ItemType Directory -Force -Path $ClassesDir, $LibDir | Out-Null
Get-ChildItem -LiteralPath $ClassesDir -Force -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force

$dependencies = [System.Collections.Generic.List[string]]::new()
$dependencies.Add((Join-Path $GameDir '26.1.2-Fabric.jar'))
Get-ChildItem -LiteralPath (Join-Path $MinecraftRoot 'libraries') -Filter '*.jar' -File -Recurse |
    Where-Object { $_.Name -notmatch '-natives-' -and $_.Name -notmatch '-sources\.jar$' -and $_.Name -notmatch '-javadoc\.jar$' } |
    ForEach-Object { $dependencies.Add($_.FullName) }
Get-ChildItem -LiteralPath (Join-Path $GameDir '.fabric\processedMods') -Filter '*.jar' -File |
    ForEach-Object { $dependencies.Add($_.FullName) }
$dependencies.Add((Join-Path $GameDir 'mods\watermedia-3.0.0.23.jar'))

$sources = Get-ChildItem -LiteralPath $SourceDir -Filter '*.java' -File -Recurse |
    ForEach-Object FullName
if ($sources.Count -eq 0) {
    throw 'No Java sources found.'
}

$classpath = (($dependencies | Select-Object -Unique) | ForEach-Object {
    $_.Replace('\', '/')
}) -join ';'
$argFile = Join-Path $BuildDir 'javac.args'
$args = @(
    '--release', '25',
    '-encoding', 'UTF-8',
    '-classpath', ('"' + $classpath + '"'),
    '-d', ('"' + $ClassesDir.Replace('\', '/') + '"')
) + ($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' })
[System.IO.File]::WriteAllLines($argFile, $args, [System.Text.UTF8Encoding]::new($false))

& (Join-Path $JdkBin 'javac.exe') ('@' + $argFile)
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}
Get-ChildItem -LiteralPath $ResourceDir -Force |
    Copy-Item -Destination $ClassesDir -Recurse -Force
Copy-Item -LiteralPath (Join-Path $ProjectDir 'LICENSE') -Destination (Join-Path $ClassesDir 'LICENSE_localsync') -Force
Copy-Item -LiteralPath (Join-Path $ProjectDir 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $ClassesDir 'THIRD_PARTY_NOTICES_localsync.md') -Force

& (Join-Path $JdkBin 'jar.exe') --create --file $OutputJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}
Write-Output $OutputJar
