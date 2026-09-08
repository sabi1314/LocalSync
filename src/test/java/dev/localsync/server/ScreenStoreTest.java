package dev.localsync.server;

import dev.localsync.net.Packets.ScreenPayload;

public final class ScreenStoreTest {
    public static void main(String[] args) {
        ScreenPayload source = new ScreenPayload(true, 7, "minecraft:overworld",
            1, -1, 42.0, 10.0, 18.0, 64.0, 69.0, "host");
        ScreenPayload restored = ScreenStore.decode(ScreenStore.encode(source));
        if (!source.equals(restored)) {
            throw new AssertionError("screen persistence round-trip mismatch: " + restored);
        }
        String invalid = ScreenStore.encode(new ScreenPayload(true, 1, "minecraft:overworld",
            1, -1, 42.0, 10.0, 300.0, 64.0, 69.0, "host"));
        if (ScreenStore.decode(invalid) != null) {
            throw new AssertionError("oversized screen should be rejected");
        }
        System.out.println("PASS ScreenStore: world screen round-trip and validation");
    }
}
