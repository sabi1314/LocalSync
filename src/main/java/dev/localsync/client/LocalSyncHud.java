package dev.localsync.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.watermedia.api.media.players.MediaPlayer;

public final class LocalSyncHud implements HudElement {
    private static final int ERROR = 0xFFFF7474;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        PlaybackSession session = PlaybackSession.instance();
        if (!session.visible() || session.phase() == PlaybackSession.Phase.IDLE) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui || client.screen != null) {
            return;
        }
        Font font = client.font;
        HudSettings settings = HudSettings.instance();
        int width = settings.effectiveWidth(graphics.guiWidth());
        float scale = (float) settings.scale();
        int x = settings.screenX(graphics.guiWidth(), width);
        int y = settings.screenY(graphics.guiHeight());
        int actualWidth = (int) Math.ceil(width * scale);
        int actualHeight = (int) Math.ceil(HudSettings.PANEL_HEIGHT * scale);
        boolean liquidGlass = ReGlassCompat.renderPanel(
            graphics, x, y, actualWidth, actualHeight);

        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        try {
            drawPanel(graphics, font, session, width, liquidGlass);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, Font font,
                                  PlaybackSession session, int width,
                                  boolean liquidGlass) {
        int height = HudSettings.PANEL_HEIGHT;
        if (!liquidGlass) {
            GlassUi.roundedPanel(graphics, 1, 1, width - 2, height - 2, 0xC9161A21);
            graphics.fillGradient(5, 2, width - 5, 15, 0x28FFFFFF, 0x00FFFFFF);
        }
        GlassUi.pill(graphics, 8, 7, 8, 8, GlassUi.ACCENT, 0x8AFFFFFF);
        graphics.fill(9, 8, 15, 9, 0x72FFFFFF);
        graphics.fill(9, 31, width - 9, 34, 0x88323944);

        String title = font.plainSubstrByWidth(session.displayTitle(), width - 36);
        graphics.text(font, title, 22, 6, GlassUi.TEXT, false);

        String status = session.statusText();
        int statusColor = session.phase() == PlaybackSession.Phase.ERROR ? ERROR : GlassUi.MUTED;
        String volume = "VOL " + session.volume();
        status = font.plainSubstrByWidth(status, width - 30 - font.width(volume));
        graphics.text(font, status, 10, 18, statusColor, false);
        graphics.text(font, volume, width - 10 - font.width(volume), 18,
            GlassUi.MUTED, false);

        boolean live = session.live();
        long elapsed = session.expectedPosition();
        MediaPlayer player = session.player();
        long duration = live || player == null ? 0L : Math.max(0L, player.duration());
        double ratio = !live && duration > 0L
            ? Math.min(1.0, (double) elapsed / duration)
            : (System.currentTimeMillis() % 2500L) / 2500.0;
        int fill = (int) Math.round((width - 18) * ratio);
        if (fill > 0) {
            graphics.fill(9, 31, 9 + fill, 34, GlassUi.ACCENT);
            graphics.fill(9, 31, 9 + fill, 32, 0x78FFFFFF);
        }

        String left = live ? "LIVE" : LocalSyncCommands.formatTime(elapsed);
        String right = live ? "实时" : duration > 0L
            ? LocalSyncCommands.formatTime(duration) : "--:--";
        graphics.text(font, left, 10, 37, GlassUi.MUTED, false);
        graphics.text(font, right, width - 10 - font.width(right), 37,
            GlassUi.MUTED, false);
    }
}
