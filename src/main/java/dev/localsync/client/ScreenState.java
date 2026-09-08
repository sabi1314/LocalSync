package dev.localsync.client;

import dev.localsync.LocalSyncMod;
import dev.localsync.net.Packets.ScreenPayload;

public final class ScreenState {
    private static final ScreenState INSTANCE = new ScreenState();
    private ScreenPayload screen = empty();

    private ScreenState() {
    }

    public static ScreenState instance() {
        return INSTANCE;
    }

    public void accept(ScreenPayload payload) {
        if (payload.revision() >= screen.revision()) {
            boolean changed = payload.revision() != screen.revision()
                || payload.active() != screen.active();
            screen = payload;
            if (changed) {
                LocalSyncMod.LOGGER.info(
                    "Received LocalSync screen revision={} active={} dimension={} axis={} facing={}",
                    payload.revision(), payload.active(), payload.dimension(),
                    payload.axis(), payload.facing());
            }
        }
    }

    public void clearLocal() {
        screen = empty();
    }

    public ScreenPayload screen() {
        return screen;
    }

    public String statusText() {
        if (!screen.active()) {
            return "未设置影院屏幕";
        }
        int width = (int) Math.round(screen.horizontalMax() - screen.horizontalMin());
        int height = (int) Math.round(screen.verticalMax() - screen.verticalMin());
        return "影院屏幕 " + width + " x " + height + " 方块";
    }

    private static ScreenPayload empty() {
        return new ScreenPayload(false, 0, "", 0, 1,
            0.0, 0.0, 0.0, 0.0, 0.0, "");
    }
}
