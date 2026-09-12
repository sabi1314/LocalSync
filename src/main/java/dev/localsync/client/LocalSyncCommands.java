package dev.localsync.client;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.localsync.net.Packets;
import dev.localsync.net.Packets.CommandPayload;
import dev.localsync.net.Packets.QueueAddPayload;
import dev.localsync.LocalSyncMod;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class LocalSyncCommands {
    private LocalSyncCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommands.literal("ls")
                .executes(context -> help(context.getSource()));

            root.then(ClientCommands.literal("play")
                .then(ClientCommands.argument("url", StringArgumentType.greedyString())
                    .executes(context -> send(context.getSource(), Packets.PLAY, 0L,
                        StringArgumentType.getString(context, "url")))));
            root.then(ClientCommands.literal("pause")
                .executes(context -> send(context.getSource(), Packets.PAUSE, 0L, "")));
            root.then(ClientCommands.literal("resume")
                .executes(context -> send(context.getSource(), Packets.RESUME, 0L, "")));
            root.then(ClientCommands.literal("stop")
                .executes(context -> send(context.getSource(), Packets.STOP, 0L, "")));
            root.then(ClientCommands.literal("seek")
                .then(ClientCommands.argument("seconds", DoubleArgumentType.doubleArg(-86400, 86400))
                    .executes(context -> send(context.getSource(), Packets.SEEK_RELATIVE,
                        Math.round(DoubleArgumentType.getDouble(context, "seconds") * 1000.0), ""))));
            root.then(ClientCommands.literal("status").executes(context -> {
                PlaybackSession session = PlaybackSession.instance();
                context.getSource().sendFeedback(Component.literal("§dLocalSync §7| §f"
                    + session.statusText() + " §7| §f" + formatTime(session.expectedPosition())
                    + " §7| §f音量 " + session.volume()));
                send(context.getSource(), Packets.REQUEST_STATE, 0L, "");
                return 1;
            }));
            root.then(ClientCommands.literal("ui").executes(context -> {
                LocalSyncClient.requestOpenControls();
                return 1;
            }));
            var screen = ClientCommands.literal("screen")
                .then(ClientCommands.literal("pos1")
                    .executes(context -> selectCorner(context.getSource(), Packets.SCREEN_POS1)))
                .then(ClientCommands.literal("pos2")
                    .executes(context -> selectCorner(context.getSource(), Packets.SCREEN_POS2)))
                .then(ClientCommands.literal("clear")
                    .executes(context -> clearPublicScreen(context.getSource())));
            root.then(screen);
            root.then(ClientCommands.literal("volume")
                .then(ClientCommands.argument("value", IntegerArgumentType.integer(0, 100))
                    .executes(context -> {
                        int value = IntegerArgumentType.getInteger(context, "value");
                        PlaybackSession.instance().setVolume(value);
                        context.getSource().sendFeedback(Component.literal(
                            "§dLocalSync §7| §f本地音量 " + value));
                        return 1;
                    })));
            root.then(ClientCommands.literal("hide").executes(context -> {
                PlaybackSession.instance().toggleVisible();
                context.getSource().sendFeedback(Component.literal("§dLocalSync §7| §f画面 "
                    + (PlaybackSession.instance().visible() ? "显示" : "隐藏")));
                return 1;
            }));
            root.then(ClientCommands.literal("flip").executes(context -> {
                PlaybackSession.instance().toggleFlip();
                context.getSource().sendFeedback(Component.literal(
                    "§dLocalSync §7| §f已切换画面方向"));
                return 1;
            }));
            root.then(ClientCommands.literal("help")
                .executes(context -> help(context.getSource())));
            dispatcher.register(root);
        });
    }

    private static int send(FabricClientCommandSource source, int action, long value,
                            String text) {
        try {
            if (!sendAction(action, value, text)) {
                source.sendError(Component.literal("§c当前世界未加载 LocalSync 服务端"));
                return 0;
            }
            return 1;
        } catch (RuntimeException error) {
            source.sendError(Component.literal("§c发送失败: " + error.getMessage()));
            return 0;
        }
    }

    static boolean sendAction(int action, long value, String text) {
        if (!ClientPlayNetworking.canSend(CommandPayload.TYPE)) {
            LocalSyncMod.LOGGER.warn("Cannot send LocalSync action {}; server channel unavailable",
                action);
            return false;
        }
        LocalSyncMod.LOGGER.info("Sending LocalSync action {} ({} characters)", action,
            text == null ? 0 : text.length());
        ClientPlayNetworking.send(new CommandPayload(action, value,
            text == null ? "" : text));
        return true;
    }

    static boolean queueMedia(String url, String title) {
        if (!ClientPlayNetworking.canSend(QueueAddPayload.TYPE)) {
            return false;
        }
        ClientPlayNetworking.send(new QueueAddPayload(url == null ? "" : url,
            title == null ? "" : title));
        return true;
    }

    static boolean selectLookedAtCorner(int action) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || client.level == null
                || hit.getDirection().getAxis() == net.minecraft.core.Direction.Axis.Y) {
            return false;
        }
        var pos = hit.getBlockPos();
        String dimension = client.level.dimension().identifier().toString();
        String value = dimension + "|" + pos.getX() + "|" + pos.getY() + "|"
            + pos.getZ() + "|" + hit.getDirection().get3DDataValue();
        return sendAction(action, 0L, value);
    }

    static boolean clearPublicScreen() {
        return sendAction(Packets.SCREEN_CLEAR, 0L, "");
    }

    private static int clearPublicScreen(FabricClientCommandSource source) {
        if (!clearPublicScreen()) {
            source.sendError(Component.literal("§c当前世界未加载 LocalSync 服务端"));
            return 0;
        }
        return 1;
    }

    private static int selectCorner(FabricClientCommandSource source, int action) {
        if (!selectLookedAtCorner(action)) {
            source.sendError(Component.literal("请看向竖直墙面上的方块后再设置角点"));
            return 0;
        }
        source.sendFeedback(Component.literal(action == Packets.SCREEN_POS1
            ? "LocalSync | 已设置角点 1，请看向同一墙面的另一角设置角点 2"
            : "LocalSync | 已提交角点 2"));
        return 1;
    }

    private static int help(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("§dLocalSync §7| §f/ls play <网页或媒体链接>"));
        source.sendFeedback(Component.literal("§7/ls pause · resume · seek <秒> · stop"));
        source.sendFeedback(Component.literal("§7P 或 /ls ui · volume <0-100> · hide · flip · status"));
        source.sendFeedback(Component.literal("§7/ls screen pos1 · pos2 · clear"));
        return 1;
    }

    static String formatTime(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        return String.format("%02d:%02d", total / 60L, total % 60L);
    }
}
