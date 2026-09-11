package dev.localsync.client;

import dev.localsync.LocalSyncMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Method;

final class ReGlassCompat {
    private static final String MOD_ID = "reglass";

    private static boolean initialized;
    private static boolean available;
    private static boolean failureLogged;
    private static Method create;
    private static Method dimensions;
    private static Method screenSpace;
    private static Method render;

    private ReGlassCompat() {
    }

    static boolean renderPanel(GuiGraphicsExtractor graphics, int x, int y,
                               int width, int height) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID) || !initialize()) {
            return false;
        }
        try {
            Object builder = create.invoke(null, graphics);
            builder = dimensions.invoke(builder, x, y, width, height);
            builder = screenSpace.invoke(builder);
            render.invoke(builder);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            disable(error);
            return false;
        }
    }

    private static synchronized boolean initialize() {
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            Class<?> api = Class.forName("restudio.reglass.client.api.ReGlassApi");
            Class<?> builder = Class.forName("restudio.reglass.client.api.ReGlassApi$Builder");
            create = api.getMethod("create", GuiGraphicsExtractor.class);
            dimensions = builder.getMethod("dimensions",
                int.class, int.class, int.class, int.class);
            screenSpace = builder.getMethod("screenSpace");
            render = builder.getMethod("render");
            available = true;
            LocalSyncMod.LOGGER.info("LocalSync HUD is using the ReGlass liquid-glass renderer");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            disable(error);
        }
        return available;
    }

    private static void disable(Throwable error) {
        if (!failureLogged) {
            failureLogged = true;
            LocalSyncMod.LOGGER.warn(
                "ReGlass HUD integration is unavailable; using the LocalSync fallback", error);
        }
        initialized = true;
        available = false;
    }
}
