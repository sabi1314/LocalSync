package dev.localsync.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.localsync.LocalSyncMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class HudSettings {
    static final int MIN_WIDTH = 180;
    static final int MAX_WIDTH = 420;
    static final double MIN_SCALE = 0.70;
    static final double MAX_SCALE = 1.50;
    static final int PANEL_HEIGHT = 48;

    private double xPosition = 0.02;
    private double yPosition = 0.04;
    private int panelWidth = 278;
    private double scale = 1.0;

    private HudSettings() {
    }

    static HudSettings instance() {
        return Holder.INSTANCE;
    }

    static HudSettings decode(String json) {
        HudSettings settings = new HudSettings();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("x")) settings.xPosition = root.get("x").getAsDouble();
        if (root.has("y")) settings.yPosition = root.get("y").getAsDouble();
        if (root.has("width")) settings.panelWidth = root.get("width").getAsInt();
        if (root.has("scale")) settings.scale = root.get("scale").getAsDouble();
        settings.sanitize();
        return settings;
    }

    String encode() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("x", xPosition);
        root.addProperty("y", yPosition);
        root.addProperty("width", panelWidth);
        root.addProperty("scale", scale);
        return root.toString();
    }

    void setXPosition(double value) {
        xPosition = clamp01(value);
        save();
    }

    void setYPosition(double value) {
        yPosition = clamp01(value);
        save();
    }

    void setPositionPreview(double x, double y) {
        xPosition = clamp01(x);
        yPosition = clamp01(y);
    }

    void setPanelWidth(double value) {
        panelWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, (int) Math.round(value)));
        save();
    }

    void setScale(double value) {
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
        save();
    }

    void reset() {
        xPosition = 0.02;
        yPosition = 0.04;
        panelWidth = 278;
        scale = 1.0;
        save();
    }

    void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, encode(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            LocalSyncMod.LOGGER.warn("Could not save LocalSync HUD settings", error);
        }
    }

    double xPosition() { return xPosition; }
    double yPosition() { return yPosition; }
    int panelWidth() { return panelWidth; }
    double scale() { return scale; }

    int effectiveWidth(int guiWidth) {
        int available = Math.max(MIN_WIDTH, (int) Math.floor(guiWidth / scale) - 8);
        return Math.min(panelWidth, available);
    }

    int screenX(int guiWidth, int logicalWidth) {
        int actualWidth = (int) Math.ceil(logicalWidth * scale);
        return (int) Math.round(xPosition * Math.max(0, guiWidth - actualWidth));
    }

    int screenY(int guiHeight) {
        int actualHeight = (int) Math.ceil(PANEL_HEIGHT * scale);
        return (int) Math.round(yPosition * Math.max(0, guiHeight - actualHeight));
    }

    private void sanitize() {
        if (!Double.isFinite(xPosition)) xPosition = 0.02;
        if (!Double.isFinite(yPosition)) yPosition = 0.04;
        if (!Double.isFinite(scale)) scale = 1.0;
        xPosition = clamp01(xPosition);
        yPosition = clamp01(yPosition);
        panelWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, panelWidth));
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("localsync-client.json");
    }

    private static final class Holder {
        private static final HudSettings INSTANCE = load();

        private static HudSettings load() {
            Path path = configPath();
            if (!Files.isRegularFile(path)) {
                return new HudSettings();
            }
            try {
                return decode(Files.readString(path, StandardCharsets.UTF_8));
            } catch (Exception error) {
                LocalSyncMod.LOGGER.warn("Could not load LocalSync HUD settings", error);
                return new HudSettings();
            }
        }
    }
}
