package dev.localsync.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.localsync.LocalSyncMod;
import dev.localsync.net.Packets.ScreenPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ScreenStore {
    private static final String FILE_NAME = "localsync-screen.json";

    private ScreenStore() {
    }

    static ScreenPayload load(MinecraftServer server) {
        Path path = path(server);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            ScreenPayload loaded = decode(Files.readString(path, StandardCharsets.UTF_8));
            if (loaded == null) {
                LocalSyncMod.LOGGER.warn("Ignored invalid LocalSync screen file: {}", path);
            }
            return loaded;
        } catch (Exception error) {
            LocalSyncMod.LOGGER.warn("Failed to load LocalSync screen from {}", path, error);
            return null;
        }
    }

    static void save(MinecraftServer server, ScreenPayload screen) {
        Path path = path(server);
        try {
            Files.createDirectories(path.getParent());
            if (!screen.active()) {
                Files.deleteIfExists(path);
                LocalSyncMod.LOGGER.info("Cleared persisted LocalSync cinema screen");
                return;
            }
            Path temporary = path.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temporary, encode(screen), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            LocalSyncMod.LOGGER.info("Persisted LocalSync cinema screen to {}", path);
        } catch (Exception error) {
            LocalSyncMod.LOGGER.warn("Failed to persist LocalSync screen to {}", path, error);
        }
    }

    static String encode(ScreenPayload screen) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("active", screen.active());
        root.addProperty("revision", screen.revision());
        root.addProperty("dimension", screen.dimension());
        root.addProperty("axis", screen.axis());
        root.addProperty("facing", screen.facing());
        root.addProperty("plane", screen.plane());
        root.addProperty("horizontalMin", screen.horizontalMin());
        root.addProperty("horizontalMax", screen.horizontalMax());
        root.addProperty("verticalMin", screen.verticalMin());
        root.addProperty("verticalMax", screen.verticalMax());
        root.addProperty("actor", screen.actor());
        return root.toString();
    }

    static ScreenPayload decode(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        boolean active = root.has("active") && root.get("active").getAsBoolean();
        if (!active) {
            return null;
        }
        ScreenPayload screen = new ScreenPayload(true,
            Math.max(1, root.get("revision").getAsInt()),
            root.get("dimension").getAsString(),
            root.get("axis").getAsInt(), root.get("facing").getAsInt(),
            root.get("plane").getAsDouble(),
            root.get("horizontalMin").getAsDouble(),
            root.get("horizontalMax").getAsDouble(),
            root.get("verticalMin").getAsDouble(),
            root.get("verticalMax").getAsDouble(),
            root.has("actor") ? root.get("actor").getAsString() : "");
        return valid(screen) ? screen : null;
    }

    private static boolean valid(ScreenPayload screen) {
        double width = screen.horizontalMax() - screen.horizontalMin();
        double height = screen.verticalMax() - screen.verticalMin();
        return screen.dimension() != null && !screen.dimension().isBlank()
            && screen.dimension().length() <= 128
            && (screen.axis() == 0 || screen.axis() == 1)
            && (screen.facing() == -1 || screen.facing() == 1)
            && Double.isFinite(screen.plane())
            && Double.isFinite(screen.horizontalMin())
            && Double.isFinite(screen.horizontalMax())
            && Double.isFinite(screen.verticalMin())
            && Double.isFinite(screen.verticalMax())
            && width >= 1.0 && width <= 128.0
            && height >= 1.0 && height <= 128.0;
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME);
    }
}
