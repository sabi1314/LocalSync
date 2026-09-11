package dev.localsync.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

final class GlassUi {
    static final int TEXT = 0xFFF7F8FA;
    static final int MUTED = 0xFFADB4C0;
    static final int ACCENT = 0xFFFB7299;
    static final int ACCENT_SOFT = 0x70FB7299;
    static final int CYAN = 0xFF6EDDE8;
    static final int PANEL = 0xB8161A22;
    static final int PANEL_STRONG = 0xD820252E;
    static final int PANEL_SOFT = 0x8C2A303A;
    static final int BORDER = 0x48FFFFFF;
    static final int BORDER_SOFT = 0x22FFFFFF;
    static final int HIGHLIGHT = 0x34FFFFFF;

    private GlassUi() {
    }

    static void roundedPanel(GuiGraphicsExtractor graphics, int x, int y,
                             int width, int height, int fill) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width < 8 || height < 8) {
            graphics.fill(x, y, x + width, y + height, fill);
            return;
        }
        graphics.fill(x + 3, y, x + width - 3, y + height, fill);
        graphics.fill(x + 1, y + 2, x + width - 1, y + height - 2, fill);
        graphics.fill(x, y + 4, x + width, y + height - 4, fill);
        graphics.horizontalLine(x + 3, x + width - 4, y, BORDER);
        graphics.horizontalLine(x + 3, x + width - 4, y + height - 1, BORDER_SOFT);
        graphics.verticalLine(x, y + 4, y + height - 5, BORDER_SOFT);
        graphics.verticalLine(x + width - 1, y + 4, y + height - 5, BORDER_SOFT);
        graphics.horizontalLine(x + 4, x + width - 5, y + 1, HIGHLIGHT);
        graphics.fill(x + 1, y + 3, x + 2, y + 5, BORDER_SOFT);
        graphics.fill(x + width - 2, y + 3, x + width - 1, y + 5, BORDER_SOFT);
    }

    static void pill(GuiGraphicsExtractor graphics, int x, int y,
                     int width, int height, int fill, int border) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width < 8 || height < 6) {
            graphics.fill(x, y, x + width, y + height, fill);
            return;
        }
        int inset = Math.min(4, Math.max(1, height / 4));
        graphics.fill(x + inset, y, x + width - inset, y + height, fill);
        graphics.fill(x + 1, y + 2, x + width - 1, y + height - 2, fill);
        graphics.fill(x, y + inset, x + width, y + height - inset, fill);
        graphics.horizontalLine(x + inset, x + width - inset - 1, y, border);
        graphics.horizontalLine(x + inset, x + width - inset - 1, y + height - 1,
            BORDER_SOFT);
    }
}
