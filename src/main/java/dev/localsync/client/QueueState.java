package dev.localsync.client;

import dev.localsync.net.Packets.QueueEntryData;
import dev.localsync.net.Packets.QueuePayload;

import java.util.List;

public final class QueueState {
    private static final QueueState INSTANCE = new QueueState();

    private volatile int revision;
    private volatile List<QueueEntryData> entries = List.of();

    private QueueState() {
    }

    public static QueueState instance() {
        return INSTANCE;
    }

    public void accept(QueuePayload payload) {
        if (payload.revision() < revision) {
            return;
        }
        revision = payload.revision();
        entries = List.copyOf(payload.entries());
    }

    public void clear() {
        revision = 0;
        entries = List.of();
    }

    public List<QueueEntryData> entries() {
        return entries;
    }

    public boolean hasEntries() {
        return !entries.isEmpty();
    }
}
