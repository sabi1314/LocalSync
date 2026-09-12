# Changelog

All notable changes to LocalSync are documented here. Versions follow semantic versioning.

## [1.5.2] - 2026-09-12

### Added

- A server-authoritative FIFO request queue for multiple players, with manual next-item playback and queue-first auto-advance.
- Per-player private screens with independent playback, audio, placement, search, favorites, account quality, and autoplay state.
- Bilibili live search and room playback for both shared and private screens, including quality selection and bounded stream reconnection.
- A public/private screen chooser, mode-specific control centers, back navigation, and a centered mode indicator.
- Technical notes covering the Bilibili live APIs and stream-selection behavior used by LocalSync.

### Changed

- Preserve video and live search queries, results, pages, and scroll positions when the control center closes.
- Reopen the last closed public or private control center when its complete screen is available; show the chooser when neither screen is available.
- Reduce and center the public/private chooser buttons across wide and narrow GUI scales.
- Prefer AVC-FLV live streams and request the highest quality actually granted to the signed-in account.
- Clarify delayed video-frame initialization as slow loading rather than failure, and report when the frame becomes ready.

### Fixed

- Keep private playback data entirely client-local and scope private screen placement by player and world/server.
- Prevent shared autoplay from skipping queued player requests.
- Distinguish confirmed resolver/player failures from media that is still producing its first texture.

## [1.4.2] - 2026-09-11

### Added

- Optional runtime integration with the public ReGlass 2.0 API for a native liquid-glass playback HUD.
- The complete `P` control center, its custom controls and result rows use the same native ReGlass renderer as the playback HUD.
- The HUD layout preview uses the same ReGlass renderer as the in-game playback panel.

### Changed

- Registered the playback panel at the bottom of Fabric's HUD layer order instead of appending it last.
- Kept ReGlass optional through an isolated runtime bridge; its classes and shaders are not bundled.
- Request the highest Bilibili quality available to the current account and source, including the 4K capability flag.
- Set WaterMedia to its maximum media quality and decode LOD before playback starts.
- Use linear clamp-to-edge sampling for the world video texture instead of Minecraft's pixelated nearest-neighbor default.

### Fixed

- Hide the playback HUD while inventory, container, and other screens are open so it cannot cover JEI overlays.
- Respect Minecraft's hide-GUI option when rendering the playback HUD.

## [1.4.0] - 2026-09-11

### Added

- Bilibili QR-code and Cookie login from the in-game control panel.
- Account profile loading and paginated browsing of the user's favorite folders and videos.
- Automatic continuation to the next multi-page part, the next UGC collection episode, or a related video.
- Responsive translucent-glass controls for search, favorites, playback, HUD layout, and account access.
- Bundled ZXing Core 3.5.3 for local QR rendering, including its Apache-2.0 license.
- Offline account, HTTP, autoplay, cover-cache, and race-condition tests plus optional live API probes.

### Changed

- Search now requests Bilibili's `totalrank` ordering, matching the website's default comprehensive ranking instead of forcing play-count order.
- Bilibili HTTP requests now use centralized redirect and credential-forwarding safeguards.
- Cover loading and media request handling are more resilient to current Bilibili endpoints.
- The playback HUD uses a more compact glass layout and remains freely movable and resizable.

### Fixed

- Added the official login source marker and request headers required by Bilibili QR generation.
- Generated QR textures at their actual display size to prevent unreadable or failed codes.
- Prevented account cookies from being forwarded to redirected hosts or synchronized over LAN.

## [1.2.0] - 2026-09-08

### Added

- In-game Bilibili keyword, video, and creator search.
- Search result covers, titles, creators, durations, play counts, and pagination.
- One-click synchronized playback from search results.
- Persistent HUD position, width, and scale settings.
- Draggable HUD preview and four layout sliders.

### Changed

- Rebuilt the `P` control panel as separate search, playback/screen, and HUD pages.
- Moved the default playback HUD from the hotbar area to the upper-left corner.

## [1.1.3] - 2026-09-08

### Fixed

- Replaced the raw OpenGL world projection with Fabric 26.1 submitted geometry.
- Added a GPU-only WaterMedia-to-Minecraft texture mirror, fixing audio without video.

## [1.1.2] - 2026-09-08

### Added

- Per-world cinema screen persistence and renderer diagnostics.

## [1.1.0] - 2026-09-08

### Added

- Bilibili video, multi-page video, share text, and `b23.tv` support.
- Vertical cinema screen selection and the `P` control panel.
