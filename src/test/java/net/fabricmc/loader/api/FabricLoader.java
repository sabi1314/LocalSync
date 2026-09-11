package net.fabricmc.loader.api;

import java.nio.file.Path;

public final class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();

    private FabricLoader() {
    }

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    public Path getConfigDir() {
        return Path.of(".");
    }
}
