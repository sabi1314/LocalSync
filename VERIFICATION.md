# Release verification

## LocalSync 1.4.0

Expected SHA-256 for `localsync-1.4.0.jar`:

```text
F4E452EE51289D2ECAE23018F89D89C14ECE86F85612058898580DF91B2DA3AA
```

Verify the downloaded JAR on Windows:

```powershell
Get-FileHash .\localsync-1.4.0.jar -Algorithm SHA256
```

Build and run the complete offline test suite from this source tree:

```powershell
.\test-all.ps1
```

The build expects Minecraft 26.1.2 Fabric and its dependencies under `D:\.minecraft` by default.
Override `-GameDir`, `-MinecraftRoot`, and `-JdkBin` when using other local paths. WaterMedia and
WaterMedia Binaries are build/runtime dependencies and are intentionally absent from this repository
and the LocalSync JAR.

The Bilibili login file `config/localsync-bilibili-account.json` is local credential material. It is
not part of this source tree, release archive, or LAN protocol.
