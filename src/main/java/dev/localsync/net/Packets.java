package dev.localsync.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class Packets {
    public static final int PLAY = 1;
    public static final int PAUSE = 2;
    public static final int RESUME = 3;
    public static final int SEEK_RELATIVE = 4;
    public static final int STOP = 5;
    public static final int REQUEST_STATE = 6;
    public static final int SCREEN_POS1 = 7;
    public static final int SCREEN_POS2 = 8;
    public static final int SCREEN_CLEAR = 9;

    private static final int MAX_TEXT = 8192;

    public record CommandPayload(int action, long value, String text)
            implements CustomPacketPayload {
        public static final Type<CommandPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("localsync", "command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CommandPayload> CODEC =
            StreamCodec.of(
                (buf, payload) -> {
                    buf.writeByte(payload.action());
                    buf.writeLong(payload.value());
                    buf.writeUtf(payload.text() == null ? "" : payload.text(), MAX_TEXT);
                },
                buf -> new CommandPayload(buf.readUnsignedByte(), buf.readLong(),
                    buf.readUtf(MAX_TEXT)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SnapshotPayload(boolean active, boolean paused, int revision,
                                  long positionMs, String actor, String mediaUrl)
            implements CustomPacketPayload {
        public static final Type<SnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("localsync", "snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC =
            StreamCodec.of(
                (buf, payload) -> {
                    buf.writeBoolean(payload.active());
                    buf.writeBoolean(payload.paused());
                    buf.writeVarInt(payload.revision());
                    buf.writeLong(payload.positionMs());
                    buf.writeUtf(payload.actor() == null ? "" : payload.actor(), 128);
                    buf.writeUtf(payload.mediaUrl() == null ? "" : payload.mediaUrl(), MAX_TEXT);
                },
                buf -> new SnapshotPayload(buf.readBoolean(), buf.readBoolean(),
                    buf.readVarInt(), buf.readLong(), buf.readUtf(128),
                    buf.readUtf(MAX_TEXT)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ScreenPayload(boolean active, int revision, String dimension,
                                int axis, int facing, double plane,
                                double horizontalMin, double horizontalMax,
                                double verticalMin, double verticalMax,
                                String actor) implements CustomPacketPayload {
        public static final Type<ScreenPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("localsync", "screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenPayload> CODEC =
            StreamCodec.of(
                (buf, payload) -> {
                    buf.writeBoolean(payload.active());
                    buf.writeVarInt(payload.revision());
                    buf.writeUtf(payload.dimension() == null ? "" : payload.dimension(), 128);
                    buf.writeByte(payload.axis());
                    buf.writeByte(payload.facing());
                    buf.writeDouble(payload.plane());
                    buf.writeDouble(payload.horizontalMin());
                    buf.writeDouble(payload.horizontalMax());
                    buf.writeDouble(payload.verticalMin());
                    buf.writeDouble(payload.verticalMax());
                    buf.writeUtf(payload.actor() == null ? "" : payload.actor(), 128);
                },
                buf -> new ScreenPayload(buf.readBoolean(), buf.readVarInt(),
                    buf.readUtf(128), buf.readUnsignedByte(), buf.readByte(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private Packets() {
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(CommandPayload.TYPE,
            CommandPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SnapshotPayload.TYPE,
            SnapshotPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ScreenPayload.TYPE,
            ScreenPayload.CODEC);
    }
}
