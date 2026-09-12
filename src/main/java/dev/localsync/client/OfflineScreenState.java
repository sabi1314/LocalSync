package dev.localsync.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.localsync.LocalSyncMod;
import dev.localsync.net.Packets.ScreenPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class OfflineScreenState {
    private static final OfflineScreenState INSTANCE = new OfflineScreenState();
    private static final Path STORE = FabricLoader.getInstance().getConfigDir()
        .resolve("localsync-offline-screens.json");

    private ScreenPayload screen = empty(0);
    private Corner firstCorner;
    private String scope = "";

    private OfflineScreenState() {
    }

    public static OfflineScreenState instance() {
        return INSTANCE;
    }

    public void refreshScope(Minecraft client) {
        String next = scopeFor(client);
        if (next.equals(scope)) {
            return;
        }
        scope = next;
        firstCorner = null;
        screen = load(next);
    }

    public boolean selectCorner(int number) {
        Minecraft client = Minecraft.getInstance();
        refreshScope(client);
        Corner corner = lookedAtCorner(client);
        if (corner == null) {
            return false;
        }
        if (number == 1) {
            firstCorner = corner;
            return true;
        }
        ScreenPayload created = createScreen(firstCorner, corner, screen.revision() + 1);
        if (created == null) {
            return false;
        }
        screen = created;
        firstCorner = null;
        save();
        return true;
    }

    public void clear() {
        screen = empty(screen.revision() + 1);
        firstCorner = null;
        save();
    }

    public ScreenPayload screen() {
        return screen;
    }

    public String statusText() {
        if (!screen.active()) {
            return firstCorner == null ? "未设置离线屏幕" : "离线屏幕：已设置角点 1";
        }
        int width = (int) Math.round(screen.horizontalMax() - screen.horizontalMin());
        int height = (int) Math.round(screen.verticalMax() - screen.verticalMin());
        return "离线屏幕 " + width + " x " + height + " 方块";
    }

    private static Corner lookedAtCorner(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || client.level == null
                || hit.getDirection().getAxis() == Direction.Axis.Y) {
            return null;
        }
        return new Corner(client.level.dimension().identifier().toString(),
            hit.getBlockPos(), hit.getDirection());
    }

    private static ScreenPayload createScreen(Corner first, Corner second, int revision) {
        if (first == null || second == null || !first.dimension.equals(second.dimension)
                || first.face != second.face) {
            return null;
        }
        Direction face = first.face;
        boolean xPlane = face.getAxis() == Direction.Axis.X;
        double firstPlane = xPlane
            ? first.pos.getX() + (face == Direction.EAST ? 1.0 : 0.0)
            : first.pos.getZ() + (face == Direction.SOUTH ? 1.0 : 0.0);
        double secondPlane = xPlane
            ? second.pos.getX() + (face == Direction.EAST ? 1.0 : 0.0)
            : second.pos.getZ() + (face == Direction.SOUTH ? 1.0 : 0.0);
        if (firstPlane != secondPlane) {
            return null;
        }
        double firstHorizontal = xPlane ? first.pos.getZ() : first.pos.getX();
        double secondHorizontal = xPlane ? second.pos.getZ() : second.pos.getX();
        double hMin = Math.min(firstHorizontal, secondHorizontal);
        double hMax = Math.max(firstHorizontal, secondHorizontal) + 1.0;
        double vMin = Math.min(first.pos.getY(), second.pos.getY());
        double vMax = Math.max(first.pos.getY(), second.pos.getY()) + 1.0;
        if (hMax - hMin > 128.0 || vMax - vMin > 128.0) {
            return null;
        }
        return new ScreenPayload(true, revision, first.dimension, xPlane ? 0 : 1,
            face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1,
            firstPlane, hMin, hMax, vMin, vMax, "local");
    }

    private static String scopeFor(Minecraft client) {
        String player = client.getUser() == null ? "unknown"
            : client.getUser().getProfileId().toString();
        if (client.isLocalServer() && client.getSingleplayerServer() != null) {
            return player + "|singleplayer|"
                + client.getSingleplayerServer().getWorldData().getLevelName();
        }
        if (client.getCurrentServer() != null) {
            return player + "|server|" + client.getCurrentServer().ip;
        }
        return player + "|menu";
    }

    private static ScreenPayload load(String scope) {
        if (scope.endsWith("|menu") || !Files.isRegularFile(STORE)) {
            return empty(0);
        }
        try {
            JsonObject root = JsonParser.parseString(
                Files.readString(STORE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has(scope) || !root.get(scope).isJsonObject()) {
                return empty(0);
            }
            JsonObject value = root.getAsJsonObject(scope);
            return new ScreenPayload(true, integer(value, "revision", 1),
                string(value, "dimension"), integer(value, "axis", 0),
                integer(value, "facing", 1), decimal(value, "plane"),
                decimal(value, "horizontalMin"), decimal(value, "horizontalMax"),
                decimal(value, "verticalMin"), decimal(value, "verticalMax"), "local");
        } catch (Throwable error) {
            LocalSyncMod.LOGGER.warn("Failed to load LocalSync offline screen", error);
            return empty(0);
        }
    }

    private void save() {
        if (scope.endsWith("|menu")) {
            return;
        }
        try {
            JsonObject root = Files.isRegularFile(STORE)
                ? JsonParser.parseString(Files.readString(STORE, StandardCharsets.UTF_8))
                    .getAsJsonObject() : new JsonObject();
            if (screen.active()) {
                JsonObject value = new JsonObject();
                value.addProperty("revision", screen.revision());
                value.addProperty("dimension", screen.dimension());
                value.addProperty("axis", screen.axis());
                value.addProperty("facing", screen.facing());
                value.addProperty("plane", screen.plane());
                value.addProperty("horizontalMin", screen.horizontalMin());
                value.addProperty("horizontalMax", screen.horizontalMax());
                value.addProperty("verticalMin", screen.verticalMin());
                value.addProperty("verticalMax", screen.verticalMax());
                root.add(scope, value);
            } else {
                root.remove(scope);
            }
            Files.createDirectories(STORE.getParent());
            Path temporary = Files.createTempFile(STORE.getParent(),
                ".localsync-offline-", ".tmp");
            try {
                Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
                Files.move(temporary, STORE, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Throwable error) {
            LocalSyncMod.LOGGER.warn("Failed to save LocalSync offline screen", error);
        }
    }

    private static ScreenPayload empty(int revision) {
        return new ScreenPayload(false, revision, "", 0, 1,
            0.0, 0.0, 0.0, 0.0, 0.0, "local");
    }

    private static String string(JsonObject value, String key) {
        return value.has(key) ? value.get(key).getAsString() : "";
    }

    private static int integer(JsonObject value, String key, int fallback) {
        return value.has(key) ? value.get(key).getAsInt() : fallback;
    }

    private static double decimal(JsonObject value, String key) {
        return value.has(key) ? value.get(key).getAsDouble() : 0.0;
    }

    private record Corner(String dimension, BlockPos pos, Direction face) {
    }
}
