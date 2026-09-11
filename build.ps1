param(
    [string]$GameDir = 'D:\.minecraft\versions\26.1.2-Fabric',
    [string]$MinecraftRoot = 'D:\.minecraft',
    [string]$JdkBin = 'D:\.minecraft\runtime\java-runtime-epsilon\bin'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SourceDir = Join-Path $ProjectDir 'src\main\java'
$ResourceDir = Join-Path $ProjectDir 'src\main\resources'
$DependencyDir = Join-Path $ProjectDir 'lib'
$LicenseDir = Join-Path $ProjectDir 'licenses'
$BuildDir = Join-Path $ProjectDir 'build'
$BuildDependencyDir = Join-Path $BuildDir 'dependencies'
$ClassesDir = Join-Path $BuildDir 'classes'
$LibDir = Join-Path $BuildDir 'libs'
$OutputJar = Join-Path $LibDir 'localsync-1.4.0.jar'
$LocalZxingJar = Join-Path $DependencyDir 'zxing-core-3.5.3.jar'
$CachedZxingJar = Join-Path $BuildDependencyDir 'zxing-core-3.5.3.jar'
$ZxingLicense = Join-Path $LicenseDir 'ZXING-APACHE-2.0.txt'
$ExpectedZxingSha256 = '8D8064C1636FDAEF7189DD9055C7D59950A8940A12F2293956446EC3C109FD82'
$ZxingDownloadUri = 'https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar'

function Test-ZxingJar {
    param([Parameter(Mandatory)][string]$Path)

    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $ExpectedZxingSha256
}

New-Item -ItemType Directory -Force -Path $BuildDependencyDir | Out-Null
if (Test-Path -LiteralPath $LocalZxingJar -PathType Leaf) {
    if (!(Test-ZxingJar -Path $LocalZxingJar)) {
        throw "Local ZXing dependency failed SHA-256 verification: $LocalZxingJar"
    }
    $ZxingJar = $LocalZxingJar
} elseif (Test-ZxingJar -Path $CachedZxingJar) {
    $ZxingJar = $CachedZxingJar
} else {
    $DownloadPath = Join-Path $BuildDependencyDir `
        ("zxing-core-3.5.3.{0}.download" -f [Guid]::NewGuid().ToString('N'))
    try {
        Invoke-WebRequest -Uri $ZxingDownloadUri -OutFile $DownloadPath `
            -UseBasicParsing -TimeoutSec 60
        if (!(Test-ZxingJar -Path $DownloadPath)) {
            throw 'Downloaded ZXing dependency failed SHA-256 verification.'
        }
        Move-Item -LiteralPath $DownloadPath -Destination $CachedZxingJar -Force
    } finally {
        if (Test-Path -LiteralPath $DownloadPath) {
            Remove-Item -LiteralPath $DownloadPath -Force
        }
    }
    $ZxingJar = $CachedZxingJar
}
if (!(Test-Path -LiteralPath $ZxingLicense -PathType Leaf)) {
    throw "Missing ZXing license: $ZxingLicense"
}

New-Item -ItemType Directory -Force -Path $ClassesDir, $LibDir | Out-Null
Get-ChildItem -LiteralPath $ClassesDir -Force -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force

$dependencies = [System.Collections.Generic.List[string]]::new()
$dependencies.Add((Join-Path $GameDir '26.1.2-Fabric.jar'))
$dependencies.Add($ZxingJar)
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
$NestedJarDir = Join-Path $ClassesDir 'META-INF\jars'
New-Item -ItemType Directory -Force -Path $NestedJarDir | Out-Null
$NestedZxingJar = Join-Path $NestedJarDir 'zxing-core-3.5.3.jar'
Copy-Item -LiteralPath $ZxingJar -Destination $NestedZxingJar -Force
$NestedMetadataDir = Join-Path $BuildDir 'zxing-nested-metadata'
New-Item -ItemType Directory -Force -Path $NestedMetadataDir | Out-Null
$NestedMetadataPath = Join-Path $NestedMetadataDir 'fabric.mod.json'
$NestedMetadata = @'
{
  "schemaVersion": 1,
  "id": "com_google_zxing_core",
  "version": "3.5.3",
  "name": "ZXing Core",
  "description": "Bundled QR code generation library for LocalSync.",
  "license": "Apache-2.0",
  "environment": "client",
  "custom": {
    "fabric-loom:generated": true
  }
}
'@
[System.IO.File]::WriteAllText($NestedMetadataPath, $NestedMetadata,
    [System.Text.UTF8Encoding]::new($false))
& (Join-Path $JdkBin 'jar.exe') --update --file $NestedZxingJar `
    -C $NestedMetadataDir 'fabric.mod.json'
if ($LASTEXITCODE -ne 0) {
    throw "ZXing nested JAR metadata injection failed with exit code $LASTEXITCODE"
}
Copy-Item -LiteralPath (Join-Path $ProjectDir 'LICENSE') -Destination (Join-Path $ClassesDir 'LICENSE_localsync') -Force
Copy-Item -LiteralPath $ZxingLicense -Destination (Join-Path $ClassesDir 'LICENSE_zxing-Apache-2.0.txt') -Force
Copy-Item -LiteralPath (Join-Path $ProjectDir 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $ClassesDir 'THIRD_PARTY_NOTICES_localsync.md') -Force

& (Join-Path $JdkBin 'jar.exe') --create --file $OutputJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}
Write-Output $OutputJar
