package dev.localsync.client;

public final class HudSettingsTest {
    public static void main(String[] args) {
        HudSettings clamped = HudSettings.decode(
            "{\"x\":-4,\"y\":3,\"width\":999,\"scale\":0.1}");
        if (clamped.xPosition() != 0.0 || clamped.yPosition() != 1.0
                || clamped.panelWidth() != HudSettings.MAX_WIDTH
                || clamped.scale() != HudSettings.MIN_SCALE) {
            throw new AssertionError("HUD settings were not clamped");
        }

        HudSettings restored = HudSettings.decode(clamped.encode());
        if (restored.xPosition() != clamped.xPosition()
                || restored.yPosition() != clamped.yPosition()
                || restored.panelWidth() != clamped.panelWidth()
                || restored.scale() != clamped.scale()) {
            throw new AssertionError("HUD settings round-trip failed");
        }
        System.out.println("PASS HudSettings: clamp and JSON round-trip");
    }
}
