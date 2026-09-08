# Contributing

Thanks for helping improve LocalSync. Keep changes focused, explain user-visible behavior, and add a regression test when practical.

## Development environment

- Minecraft 26.1.2
- Fabric Loader 0.19.4
- Java 25
- Fabric API 0.155.2+26.1.2
- WaterMedia 3.0.0.23 and WaterMedia Binaries 3.0.0.6

The repository intentionally does not redistribute Minecraft or dependency JARs. `build.ps1` reads them from an existing Minecraft installation.

```powershell
.\build.ps1 `
  -MinecraftRoot 'D:\.minecraft' `
  -GameDir 'D:\.minecraft\versions\26.1.2-Fabric' `
  -JdkBin 'D:\.minecraft\runtime\java-runtime-epsilon\bin'
```

## Tests

Run tests sequentially because the scripts share `build/test-classes`.

```powershell
.\test.ps1
.\test-bilibili.ps1
.\test-screen.ps1
.\test-hud.ps1
```

## Pull requests

1. Describe the problem and the behavior after the change.
2. Keep networking and synchronization changes compatible with LAN integrated servers.
3. Do not include game files, dependency JARs, logs, worlds, cookies, tokens, or local configuration.
4. Confirm the build and all four test scripts pass.
5. Update `CHANGELOG.md` for user-visible changes.
