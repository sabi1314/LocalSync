# LocalSync 1.5.2

LocalSync provides synchronized LAN media playback for Minecraft 26.1.2 Fabric without an external verification server.

## Highlights

- Search Bilibili from the in-game `P` menu using the website's default comprehensive ordering.
- Sign in by QR code or Cookie and browse your favorite folders and videos in game.
- Continue automatically through multi-page videos and UGC collections, or to a related video.
- Queue requests from multiple players in FIFO order; manual and automatic next playback consume the queue first.
- Create a private per-player screen whose video, audio, placement, and controls stay on that client.
- Search and play Bilibili live rooms in shared or private mode with bounded reconnect handling.
- Place a shared video on a selected vertical cinema wall.
- Request the highest Bilibili quality available to the current account and render it with maximum WaterMedia LOD and smooth linear scaling.
- Use ReGlass 2.0's native Liquid Glass shader across the complete `P` control center, its controls and result rows, and the playback HUD.
- Keep the playback HUD below other HUD elements and hide it while menus are open, preventing JEI overlap.
- Move or resize the local playback HUD and preview the same ReGlass effect in settings.
- Preserve synchronized play, pause, seek, resume, stop, and late-join state.
- Preserve search state and reopen the last closed available public/private control center.
- Distinguish slow first-frame loading from confirmed playback failure.

## Requirements

- Minecraft 26.1.2
- Fabric Loader 0.19.4
- Java 25
- Fabric API 0.155.2+26.1.2
- WaterMedia 3.0.0.23
- WaterMedia Binaries 3.0.0.6
- Fabric Language Kotlin 1.13.12+kotlin.2.4.0

Every participating client must install the same LocalSync JAR and dependencies.

WaterMedia dependencies are not bundled because their licenses prohibit redistribution. Account
cookies remain local to each client and are never included in LAN synchronization packets.

ReGlass 2.0 is optional. LocalSync calls its public API when present and falls back to its own
control-center and HUD backgrounds when absent; no ReGlass classes or shaders are bundled.

Bilibili still decides the delivered resolution from the source video and the signed-in account's
permissions. LocalSync requests the maximum available tier, including 4K, but does not upscale a
lower-resolution source.

LocalSync JAR SHA-256: `AAF44A7515E68265BEB7A0F3328A14C23F25B5FDB541C249744D8F8A09E54CA8`
