# Release verification

## LocalSync 1.4.2

Expected SHA-256 for `localsync-1.4.2.jar`:

```text
2A789F70DD0565B8F11B1A91DB5528AF7FFF8F5F59AFF1228139FC989F06388C
```

Verify the downloaded JAR on Windows:

```powershell
Get-FileHash .\localsync-1.4.2.jar -Algorithm SHA256
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
