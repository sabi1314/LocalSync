package dev.localsync.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

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
    private static final int MAX_TITLE = 256;
    private static final int MAX_ACTOR = 128;
    public static final int MAX_QUEUE_ENTRIES = 64;

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

    public record QueueAddPayload(String mediaUrl, String title)
            implements CustomPacketPayload {
        public static final Type<QueueAddPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("localsync", "queue_add"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QueueAddPayload> CODEC =
            StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.mediaUrl() == null ? "" : payload.mediaUrl(), MAX_TEXT);
                    buf.writeUtf(payload.title() == null ? "" : payload.title(), MAX_TITLE);
                },
                buf -> new QueueAddPayload(buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TITLE)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AdvancePayload(int expectedRevision, String expectedMediaUrl,
                                 String nextMediaUrl) implements CustomPacketPayload {
        public static final Type<AdvancePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("localsync", "advance"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AdvancePayload> CODEC =
            StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.expectedRevision());
                    buf.writeUtf(payload.expectedMediaUrl() == null
                        ? "" : payload.expectedMediaUrl(), MAX_TEXT);
                    buf.writeUtf(payload.nextMediaUrl() == null
                        ? "" : payload.nextMediaUrl(), MAX_TEXT);
                },
                buf -> new AdvancePayload(buf.readVarInt(), buf.readUtf(MAX_TEXT),
                    buf.readUtf(MAX_TEXT)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SnapshotPayload(boolean active, boolean paused, int revision,
                                  long positionMs, String actor, String mediaUrl,
                                  String mediaTitle)
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
                    buf.writeUtf(payload.actor() == null ? "" : payload.actor(), MAX_ACTOR);
                    buf.writeUtf(payload.mediaUrl() == null ? "" : payload.mediaUrl(), MAX_TEXT);
                    buf.writeUtf(payload.mediaTitle() == null ? "" : payload.mediaTitle(), MAX_TITLE);
                },
                buf -> new SnapshotPayload(buf.readBoolean(), buf.readBoolean(),
                    buf.readVarInt(), buf.readLong(), buf.readUtf(MAX_ACTOR),
                    buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TITLE)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record QueueEntryData(long id, String requester, String title,
                                 String mediaUrl) {
    }

    public record QueuePayload(int revision, List<QueueEntryData> entries)
            implements CustomPacketPayload {
        public static final Type<QueuePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("localsync", "queue"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QueuePayload> CODEC =
            StreamCodec.of(
                (buf, payload) -> {
                    List<QueueEntryData> values = payload.entries() == null
                        ? List.of() : payload.entries();
                    if (values.size() > MAX_QUEUE_ENTRIES) {
                        throw new IllegalArgumentException("LocalSync queue is too large");
                    }
                    buf.writeVarInt(values.size());
                    buf.writeVarInt(payload.revision());
                    for (QueueEntryData entry : values) {
                        buf.writeLong(entry.id());
                        buf.writeUtf(entry.requester() == null ? "" : entry.requester(), MAX_ACTOR);
                        buf.writeUtf(entry.title() == null ? "" : entry.title(), MAX_TITLE);
                        buf.writeUtf(entry.mediaUrl() == null ? "" : entry.mediaUrl(), MAX_TEXT);
                    }
                },
                buf -> {
                    int size = buf.readVarInt();
                    if (size < 0 || size > MAX_QUEUE_ENTRIES) {
                        throw new IllegalArgumentException("Invalid LocalSync queue size: " + size);
                    }
                    int revision = buf.readVarInt();
                    List<QueueEntryData> entries = new ArrayList<>(size);
                    for (int index = 0; index < size; index++) {
                        entries.add(new QueueEntryData(buf.readLong(),
                            buf.readUtf(MAX_ACTOR), buf.readUtf(MAX_TITLE),
                            buf.readUtf(MAX_TEXT)));
                    }
                    return new QueuePayload(revision, List.copyOf(entries));
                });

        public QueuePayload {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

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
        PayloadTypeRegistry.serverboundPlay().register(QueueAddPayload.TYPE,
            QueueAddPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AdvancePayload.TYPE,
            AdvancePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SnapshotPayload.TYPE,
            SnapshotPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(QueuePayload.TYPE,
            QueuePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ScreenPayload.TYPE,
            ScreenPayload.CODEC);
    }
}
