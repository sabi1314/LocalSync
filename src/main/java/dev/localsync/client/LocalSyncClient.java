package dev.localsync.client;

import dev.localsync.net.Packets.CommandPayload;
import dev.localsync.net.Packets.SnapshotPayload;
import dev.localsync.net.Packets.ScreenPayload;
import dev.localsync.LocalSyncMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class LocalSyncClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("localsync", "controls"));
    private static KeyMapping openControls;
    private static boolean openScreenNextTick;

    @Override
    public void onInitializeClient() {
        PlaybackSession session = PlaybackSession.instance();
        HudSettings.instance();

        ClientPlayNetworking.registerGlobalReceiver(SnapshotPayload.TYPE,
            (payload, context) -> context.client().execute(() -> session.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ScreenPayload.TYPE,
            (payload, context) -> context.client().execute(() ->
                ScreenState.instance().accept(payload)));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            client.execute(() -> {
                session.onJoin();
                if (ClientPlayNetworking.canSend(CommandPayload.TYPE)) {
                    ClientPlayNetworking.send(new CommandPayload(6, 0L, ""));
                }
            }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            client.execute(() -> {
                session.close();
                VideoOverlay.release();
                BilibiliCoverCache.release();
                ScreenState.instance().clearLocal();
            }));
        openControls = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.localsync.open_controls", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            session.tick(client);
            while (openControls.consumeClick()) {
                openScreenNextTick = true;
            }
            if (openScreenNextTick && client.screen == null && client.level != null) {
                openScreenNextTick = false;
                client.setScreen(new ControlScreen());
            }
        });

        LocalSyncCommands.register();
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("localsync", "session_panel"),
            new LocalSyncHud());
        LevelRenderEvents.COLLECT_SUBMITS.register(VideoOverlay::renderWorld);
        LocalSyncMod.LOGGER.info("LocalSync client initialized; P opens controls");
    }

    public static void requestOpenControls() {
        openScreenNextTick = true;
    }
}
