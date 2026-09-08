package dev.localsync.server;

import dev.localsync.LocalSyncMod;
import dev.localsync.net.Packets;
import dev.localsync.net.Packets.CommandPayload;
import dev.localsync.net.Packets.SnapshotPayload;
import dev.localsync.net.Packets.ScreenPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RoomServer {
    private static final int HEARTBEAT_TICKS = 20;
    private static final int MAX_URL_LENGTH = 8192;

    private static MinecraftServer activeServer;
    private static final RoomTimeline TIMELINE = new RoomTimeline();
    private static int ticksUntilHeartbeat;
    private static final Map<UUID, ScreenCorner> FIRST_CORNERS = new HashMap<>();
    private static ScreenPayload screen = emptyScreen(0);

    private RoomServer() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(CommandPayload.TYPE, (payload, context) ->
            context.server().execute(() -> handle(context.server(), context.player(), payload)));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            server.execute(() -> {
                send(handler.getPlayer());
                sendScreen(handler.getPlayer());
            }));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            resetRuntime();
            ScreenPayload persisted = ScreenStore.load(server);
            if (persisted != null) {
                screen = persisted;
                LocalSyncMod.LOGGER.info("Restored LocalSync cinema screen revision={} dimension={}",
                    screen.revision(), screen.dimension());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (activeServer == server) {
                resetRuntime();
                activeServer = null;
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server != activeServer || !TIMELINE.active()) {
                return;
            }
            if (--ticksUntilHeartbeat <= 0) {
                ticksUntilHeartbeat = HEARTBEAT_TICKS;
                broadcast(server);
            }
        });
    }

    private static void handle(MinecraftServer server, ServerPlayer player,
                               CommandPayload command) {
        if (server != activeServer) {
            activeServer = server;
            resetRuntime();
            ScreenPayload persisted = ScreenStore.load(server);
            if (persisted != null) {
                screen = persisted;
            }
        }
        long now = System.currentTimeMillis();
        String name = player == null ? "?" : player.getGameProfile().name();
        switch (command.action()) {
            case Packets.PLAY -> {
                if (!screen.active() && player != null) {
                    player.sendSystemMessage(Component.literal(
                        "LocalSync | 当前未设置影院屏幕，将只播放声音；按 P 依次设置角点 1 和角点 2"));
                }
                String candidate = UrlNormalizer.normalize(command.text(), MAX_URL_LENGTH);
                if (candidate == null) {
                    LocalSyncMod.LOGGER.warn("Rejected LocalSync play request from {} ({} characters)",
                        name, command.text() == null ? 0 : command.text().length());
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(
                            "LocalSync | 没有识别到有效的 HTTP/HTTPS 链接"));
                    }
                    send(player);
                    return;
                }
                TIMELINE.play(candidate, name, now);
                LocalSyncMod.LOGGER.info("{} started a LocalSync session", name);
                if (player != null) {
                    player.sendSystemMessage(Component.literal(
                        "LocalSync | 播放请求已同步，客户端正在解析媒体"));
                }
            }
            case Packets.PAUSE -> TIMELINE.pause(name, now);
            case Packets.RESUME -> TIMELINE.resume(name, now);
            case Packets.SEEK_RELATIVE -> {
                long delta = Math.max(-86_400_000L,
                    Math.min(86_400_000L, command.value()));
                TIMELINE.seekRelative(delta, name, now);
            }
            case Packets.STOP -> TIMELINE.stop(name, now);
            case Packets.REQUEST_STATE -> {
                send(player);
                sendScreen(player);
                return;
            }
            case Packets.SCREEN_POS1 -> {
                ScreenCorner corner = parseCorner(command.text());
                if (corner != null && player != null) {
                    FIRST_CORNERS.put(player.getUUID(), corner);
                    LocalSyncMod.LOGGER.info("{} selected LocalSync screen corner one", name);
                }
                sendScreen(player);
                return;
            }
            case Packets.SCREEN_POS2 -> {
                ScreenCorner second = parseCorner(command.text());
                ScreenCorner first = player == null ? null : FIRST_CORNERS.get(player.getUUID());
                ScreenPayload created = createScreen(first, second, name, screen.revision() + 1);
                if (created != null) {
                    screen = created;
                    FIRST_CORNERS.remove(player.getUUID());
                    ScreenStore.save(server, screen);
                    LocalSyncMod.LOGGER.info("{} created a LocalSync cinema screen", name);
                    broadcastScreen(server);
                } else {
                    sendScreen(player);
                }
                return;
            }
            case Packets.SCREEN_CLEAR -> {
                screen = emptyScreen(screen.revision() + 1);
                ScreenStore.save(server, screen);
                if (player != null) {
                    FIRST_CORNERS.remove(player.getUUID());
                }
                broadcastScreen(server);
                return;
            }
            default -> {
                return;
            }
        }
        ticksUntilHeartbeat = HEARTBEAT_TICKS;
        broadcast(server);
    }

    private static SnapshotPayload snapshot() {
        RoomTimeline.State state = TIMELINE.state(System.currentTimeMillis());
        return new SnapshotPayload(state.active(), state.paused(), state.revision(),
            state.positionMs(), state.actor(), state.mediaUrl());
    }

    private static void broadcast(MinecraftServer server) {
        SnapshotPayload state = snapshot();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, state);
        }
    }

    private static void send(ServerPlayer player) {
        send(player, snapshot());
    }

    private static void send(ServerPlayer player, SnapshotPayload state) {
        if (player == null || !ServerPlayNetworking.canSend(player, SnapshotPayload.TYPE)) {
            return;
        }
        try {
            ServerPlayNetworking.send(player, state);
        } catch (RuntimeException error) {
            LocalSyncMod.LOGGER.debug("Snapshot delivery failed for {}",
                player.getGameProfile().name(), error);
        }
    }

    private static void broadcastScreen(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendScreen(player);
        }
    }

    private static void sendScreen(ServerPlayer player) {
        if (player == null || !ServerPlayNetworking.canSend(player, ScreenPayload.TYPE)) {
            return;
        }
        try {
            ServerPlayNetworking.send(player, screen);
        } catch (RuntimeException error) {
            LocalSyncMod.LOGGER.debug("Screen delivery failed for {}",
                player.getGameProfile().name(), error);
        }
    }

    private static ScreenCorner parseCorner(String text) {
        if (text == null) {
            return null;
        }
        String[] fields = text.split("\\|", -1);
        if (fields.length != 5 || fields[0].length() > 128) {
            return null;
        }
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(fields[1]),
                Integer.parseInt(fields[2]), Integer.parseInt(fields[3]));
            Direction face = Direction.from3DDataValue(Integer.parseInt(fields[4]));
            if (face.getAxis() == Direction.Axis.Y) {
                return null;
            }
            return new ScreenCorner(fields[0], pos, face);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ScreenPayload createScreen(ScreenCorner first, ScreenCorner second,
                                               String actor, int revision) {
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
        double horizontalMin = Math.min(firstHorizontal, secondHorizontal);
        double horizontalMax = Math.max(firstHorizontal, secondHorizontal) + 1.0;
        double verticalMin = Math.min(first.pos.getY(), second.pos.getY());
        double verticalMax = Math.max(first.pos.getY(), second.pos.getY()) + 1.0;
        if (horizontalMax - horizontalMin > 128.0 || verticalMax - verticalMin > 128.0) {
            return null;
        }
        return new ScreenPayload(true, revision, first.dimension,
            xPlane ? 0 : 1, face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1,
            firstPlane, horizontalMin, horizontalMax, verticalMin, verticalMax, actor);
    }

    private static ScreenPayload emptyScreen(int revision) {
        return new ScreenPayload(false, revision, "", 0, 1,
            0.0, 0.0, 0.0, 0.0, 0.0, "");
    }

    private static void resetRuntime() {
        TIMELINE.reset(System.currentTimeMillis());
        FIRST_CORNERS.clear();
        screen = emptyScreen(screen.revision() + 1);
        ticksUntilHeartbeat = HEARTBEAT_TICKS;
    }

    private record ScreenCorner(String dimension, BlockPos pos, Direction face) {
    }
}
