package dev.localsync;

import dev.localsync.net.Packets;
import dev.localsync.server.RoomServer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalSyncMod implements ModInitializer {
    public static final String MOD_ID = "localsync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Packets.register();
        RoomServer.register();
        LOGGER.info("LocalSync common side initialized; integrated-server relay is ready");
    }
}
