package dev.localsync.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.watermedia.api.media.players.MediaPlayer;

public final class LocalSyncHud implements HudElement {
    private static final int BACKGROUND = 0xDD101218;
    private static final int BACKGROUND_SOFT = 0xB81A1D25;
    private static final int ACCENT = 0xFFFB7299;
    private static final int TEXT = 0xFFF4F5F8;
    private static final int MUTED = 0xFF9CA3AF;
    private static final int ERROR = 0xFFFF7474;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        PlaybackSession session = PlaybackSession.instance();
        if (!session.visible() || session.phase() == PlaybackSession.Phase.IDLE) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        HudSettings settings = HudSettings.instance();
        int width = settings.effectiveWidth(graphics.guiWidth());
        float scale = (float) settings.scale();
        int x = settings.screenX(graphics.guiWidth(), width);
        int y = settings.screenY(graphics.guiHeight());

        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        try {
            drawPanel(graphics, font, session, width);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, Font font,
                                  PlaybackSession session, int width) {
        int height = HudSettings.PANEL_HEIGHT;
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.fill(0, 0, 3, height, ACCENT);
        graphics.fill(8, 31, width - 8, 34, BACKGROUND_SOFT);

        String title = font.plainSubstrByWidth(session.displayTitle(), width - 84);
        graphics.text(font, title, 10, 7, TEXT, false);

        String status = session.statusText();
        int statusColor = session.phase() == PlaybackSession.Phase.ERROR ? ERROR : MUTED;
        status = font.plainSubstrByWidth(status, width - 20);
        graphics.text(font, status, 10, 19, statusColor, false);

        long elapsed = session.expectedPosition();
        MediaPlayer player = session.player();
        long duration = player == null ? 0L : Math.max(0L, player.duration());
        double ratio = duration > 0L
            ? Math.min(1.0, (double) elapsed / duration)
            : (System.currentTimeMillis() % 2500L) / 2500.0;
        int fill = (int) Math.round((width - 16) * ratio);
        graphics.fill(8, 31, 8 + fill, 34, ACCENT);

        String left = LocalSyncCommands.formatTime(elapsed);
        String right = duration > 0L ? LocalSyncCommands.formatTime(duration) : "--:--";
        String volume = "VOL " + session.volume();
        graphics.text(font, left, 10, 37, MUTED, false);
        graphics.text(font, volume, (width - font.width(volume)) / 2, 37, MUTED, false);
        graphics.text(font, right, width - 10 - font.width(right), 37, MUTED, false);
    }
}
