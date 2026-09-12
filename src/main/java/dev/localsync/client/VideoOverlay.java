package dev.localsync.client;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.localsync.LocalSyncMod;
import dev.localsync.net.Packets.ScreenPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.watermedia.api.media.players.MediaPlayer;

public final class VideoOverlay {
    private static final Identifier[] VIDEO_TEXTURES = {
        Identifier.fromNamespaceAndPath("localsync", "world_video"),
        Identifier.fromNamespaceAndPath("localsync", "offline_world_video")
    };
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final DynamicTexture[] mirrorTextures = new DynamicTexture[2];
    private static final int[] mirrorWidths = new int[2];
    private static final int[] mirrorHeights = new int[2];
    private static final int[] readFramebuffers = new int[2];
    private static final int[] lastLoggedSourceTextures = new int[2];
    private static final boolean[] rendererFailed = new boolean[2];

    private VideoOverlay() {
    }

    public static void renderWorld(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        PlaybackSession session = PlaybackSession.instance();
        ScreenPayload screen = ScreenState.instance().screen();
        renderSource(context, client, 0, screen, session.player(),
            session.hasVideoFrame(), session.flipVertical());

        OfflinePlaybackSession offline = OfflinePlaybackSession.instance();
        ScreenPayload offlineScreen = OfflineScreenState.instance().screen();
        renderSource(context, client, 1, offlineScreen, offline.player(),
            offline.hasVideoFrame(), offline.flipVertical());
    }

    private static void renderSource(LevelRenderContext context, Minecraft client,
                                     int slot, ScreenPayload screen, MediaPlayer player,
                                     boolean hasVideoFrame, boolean flipVertical) {
        if (client.level == null || !screen.active() || !hasVideoFrame
                || !client.level.dimension().identifier().toString().equals(screen.dimension())) {
            return;
        }
        if (player == null || player.texture() <= 0L
                || player.width() <= 0 || player.height() <= 0 || rendererFailed[slot]) {
            return;
        }
        try {
            RenderSystem.assertOnRenderThread();
            int sourceTexture = (int) player.texture();
            copyFrame(client, slot, sourceTexture, player.width(), player.height());
            submitScreen(context, VIDEO_TEXTURES[slot], screen,
                player.width(), player.height(), flipVertical);
            if (sourceTexture != lastLoggedSourceTextures[slot]) {
                lastLoggedSourceTextures[slot] = sourceTexture;
                LocalSyncMod.LOGGER.info(
                    "LocalSync submitted {} geometry: sourceTexture={} mirror={} size={}x{} screenRevision={}",
                    slot == 0 ? "shared" : "offline", sourceTexture, mirrorGlId(slot),
                    player.width(), player.height(), screen.revision());
            }
        } catch (Throwable error) {
            rendererFailed[slot] = true;
            LocalSyncMod.LOGGER.error("LocalSync {} video renderer disabled",
                slot == 0 ? "shared" : "offline", error);
        }
    }

    public static void release() {
        Minecraft client = Minecraft.getInstance();
        if (!RenderSystem.isOnRenderThread()) {
            client.execute(VideoOverlay::release);
            return;
        }
        for (int slot = 0; slot < VIDEO_TEXTURES.length; slot++) {
            if (mirrorTextures[slot] != null) {
                client.getTextureManager().release(VIDEO_TEXTURES[slot]);
                mirrorTextures[slot] = null;
            }
            if (readFramebuffers[slot] != 0) {
                GL30C.glDeleteFramebuffers(readFramebuffers[slot]);
                readFramebuffers[slot] = 0;
            }
            mirrorWidths[slot] = 0;
            mirrorHeights[slot] = 0;
            lastLoggedSourceTextures[slot] = 0;
            rendererFailed[slot] = false;
        }
    }

    private static void copyFrame(Minecraft client, int slot, int sourceTexture,
                                  int width, int height) {
        ensureMirror(client, slot, width, height);
        if (readFramebuffers[slot] == 0) {
            readFramebuffers[slot] = GL30C.glGenFramebuffers();
        }

        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffers[slot]);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER,
                GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, sourceTexture, 0);
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_READ_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                    "WaterMedia texture framebuffer is incomplete: 0x"
                        + Integer.toHexString(status));
            }
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, mirrorGlId(slot));
            GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0,
                0, 0, 0, 0, width, height);
        } finally {
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER,
                GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
    }

    private static void ensureMirror(Minecraft client, int slot, int width, int height) {
        if (mirrorTextures[slot] != null && mirrorWidths[slot] == width
                && mirrorHeights[slot] == height) {
            return;
        }
        if (mirrorTextures[slot] != null) {
            client.getTextureManager().release(VIDEO_TEXTURES[slot]);
        }
        mirrorTextures[slot] = new LinearVideoTexture(width, height);
        mirrorWidths[slot] = width;
        mirrorHeights[slot] = height;
        client.getTextureManager().register(VIDEO_TEXTURES[slot], mirrorTextures[slot]);
        LocalSyncMod.LOGGER.info(
            "LocalSync mirror texture allocated: glId={} size={}x{}",
            mirrorGlId(slot), width, height);
    }

    private static int mirrorGlId(int slot) {
        if (mirrorTextures[slot] == null
                || !(mirrorTextures[slot].getTexture() instanceof GlTexture texture)) {
            throw new IllegalStateException("Minecraft OpenGL mirror texture is unavailable");
        }
        return texture.glId();
    }

    private static final class LinearVideoTexture extends DynamicTexture {
        private LinearVideoTexture(int width, int height) {
            super("LocalSync video mirror", width, height, false);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    private static void submitScreen(LevelRenderContext context, Identifier texture,
                                     ScreenPayload screen,
                                     int videoWidth, int videoHeight,
                                     boolean flipVertical) {
        CameraRenderState camera = context.levelState().cameraRenderState;
        double areaWidth = screen.horizontalMax() - screen.horizontalMin();
        double areaHeight = screen.verticalMax() - screen.verticalMin();
        double videoAspect = (double) videoWidth / videoHeight;
        double drawWidth = areaWidth;
        double drawHeight = drawWidth / videoAspect;
        if (drawHeight > areaHeight) {
            drawHeight = areaHeight;
            drawWidth = drawHeight * videoAspect;
        }

        double hMin = (screen.horizontalMin() + screen.horizontalMax() - drawWidth) * 0.5;
        double hMax = hMin + drawWidth;
        double yMin = (screen.verticalMin() + screen.verticalMax() - drawHeight) * 0.5;
        double yMax = yMin + drawHeight;
        double plane = screen.plane() + screen.facing() * 0.003;
        boolean ascending = screen.axis() == 0 ? screen.facing() < 0 : screen.facing() > 0;
        double left = ascending ? hMin : hMax;
        double right = ascending ? hMax : hMin;
        float topV = flipVertical ? 1f : 0f;
        float bottomV = flipVertical ? 0f : 1f;

        context.submitNodeCollector().submitCustomGeometry(
            context.poseStack(), RenderTypes.text(texture),
            (pose, consumer) -> emitQuad(pose, consumer, screen, camera,
                plane, left, right, yMin, yMax, topV, bottomV));
    }

    private static void emitQuad(PoseStack.Pose pose, VertexConsumer consumer,
                                 ScreenPayload screen, CameraRenderState camera,
                                 double plane, double left, double right,
                                 double yMin, double yMax,
                                 float topV, float bottomV) {
        putVertex(consumer, pose, screen, camera, plane, left, yMin, 0f, bottomV);
        putVertex(consumer, pose, screen, camera, plane, right, yMin, 1f, bottomV);
        putVertex(consumer, pose, screen, camera, plane, right, yMax, 1f, topV);
        putVertex(consumer, pose, screen, camera, plane, left, yMax, 0f, topV);
    }

    private static void putVertex(VertexConsumer consumer, PoseStack.Pose pose,
                                  ScreenPayload screen, CameraRenderState camera,
                                  double plane, double horizontal, double y,
                                  float u, float v) {
        double x = screen.axis() == 0 ? plane : horizontal;
        double z = screen.axis() == 0 ? horizontal : plane;
        consumer.addVertex(pose,
                (float) (x - camera.pos.x),
                (float) (y - camera.pos.y),
                (float) (z - camera.pos.z))
            .setColor(255, 255, 255, 255)
            .setUv(u, v)
            .setLight(FULL_BRIGHT);
    }
}
