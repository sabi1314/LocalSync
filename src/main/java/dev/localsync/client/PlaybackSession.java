package dev.localsync.client;

import dev.localsync.LocalSyncMod;
import dev.localsync.net.Packets.SnapshotPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaybackSession {
    public enum Phase { IDLE, LOADING, READY, ERROR }

    private static final long RESOLVE_TIMEOUT_MS = 60_000L;
    private static final long DRIFT_THRESHOLD_MS = 1_250L;
    private static final long CORRECTION_INTERVAL_MS = 500L;
    private static final PlaybackSession INSTANCE = new PlaybackSession();
    private static final ExecutorService RESOLVER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "localsync-media-resolver");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Phase phase = Phase.IDLE;
    private volatile SnapshotPayload snapshot =
        new SnapshotPayload(false, false, 0, 0L, "", "");
    private volatile long snapshotReceivedAt;
    private volatile MediaPlayer player;
    private volatile String errorText = "";
    private volatile int generation;
    private volatile int volume = 70;
    private volatile boolean visible = true;
    private volatile boolean flipVertical;
    private long lastCorrectionAt;
    private long playerCreatedAt;
    private boolean firstVideoFrameLogged;
    private boolean missingFrameWarningLogged;

    private PlaybackSession() {
    }

    public static PlaybackSession instance() {
        return INSTANCE;
    }

    public void onJoin() {
        generation++;
        releasePlayer();
        snapshot = new SnapshotPayload(false, false, 0, 0L, "", "");
        phase = Phase.IDLE;
        errorText = "";
    }

    public void accept(SnapshotPayload incoming) {
        LocalSyncMod.LOGGER.info("Received LocalSync snapshot revision={} active={} paused={}",
            incoming.revision(), incoming.active(), incoming.paused());
        SnapshotPayload previous = snapshot;
        snapshot = incoming;
        snapshotReceivedAt = System.currentTimeMillis();

        if (!incoming.active()) {
            generation++;
            releasePlayer();
            phase = Phase.IDLE;
            errorText = "";
            return;
        }

        boolean changedMedia = !incoming.mediaUrl().equals(previous.mediaUrl());
        if (changedMedia || player == null && phase != Phase.LOADING) {
            beginResolve(incoming.mediaUrl());
        } else {
            applyTransportState();
        }
    }

    private void beginResolve(String mediaUrl) {
        int taskGeneration = ++generation;
        releasePlayer();
        phase = Phase.LOADING;
        errorText = "";
        LocalSyncMod.LOGGER.info("Starting LocalSync media resolution");

        RESOLVER.execute(() -> {
            try {
                String resolvedUrl = BilibiliResolver.resolveIfNeeded(mediaUrl);
                LocalSyncMod.LOGGER.info("LocalSync URL preparation complete; invoking WaterMedia");
                MRL mrl = MediaAPI.mrl(resolvedUrl);
                boolean completed = mrl.await(RESOLVE_TIMEOUT_MS);
                if (!completed || !mrl.status().loaded() || mrl.sourceCount() == 0) {
                    Throwable cause = mrl.exception();
                    String detail = cause == null ? String.valueOf(mrl.status())
                        : cause.getMessage();
                    failOnClient(taskGeneration, "解析失败: " + detail);
                    return;
                }
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> createPlayer(taskGeneration, mrl));
            } catch (Throwable error) {
                failOnClient(taskGeneration, "解析失败: " + readable(error));
            }
        });
    }

    private void createPlayer(int taskGeneration, MRL mrl) {
        if (taskGeneration != generation || !snapshot.active()) {
            return;
        }
        try {
            Minecraft client = Minecraft.getInstance();
            Thread renderThread = Thread.currentThread();
            MediaPlayer created = MediaAPI.createPlayer(mrl,
                () -> MediaAPI.glEngine(renderThread, client::execute),
                MediaAPI::alEngine);
            created.volume(volume);
            created.start();
            player = created;
            phase = Phase.READY;
            playerCreatedAt = System.currentTimeMillis();
            firstVideoFrameLogged = false;
            missingFrameWarningLogged = false;
            LocalSyncMod.LOGGER.info("LocalSync player ready: video={} size={}x{} duration={}ms",
                created.withVideo(), created.width(), created.height(), created.duration());
            correct(true);
        } catch (Throwable error) {
            fail(taskGeneration, "播放器启动失败: " + readable(error));
        }
    }

    private void failOnClient(int taskGeneration, String message) {
        Minecraft.getInstance().execute(() -> fail(taskGeneration, message));
    }

    private void fail(int taskGeneration, String message) {
        if (taskGeneration != generation) {
            return;
        }
        releasePlayer();
        phase = Phase.ERROR;
        errorText = message == null ? "未知错误" : message;
        LocalSyncMod.LOGGER.warn("LocalSync playback error: {}", errorText);
    }

    public void tick(Minecraft client) {
        if (client.level == null || player == null || !snapshot.active()) {
            return;
        }
        long now = System.currentTimeMillis();
        int width = player.width();
        int height = player.height();
        long texture = player.texture();
        if (!firstVideoFrameLogged && width > 0 && height > 0 && texture > 0L) {
            firstVideoFrameLogged = true;
            LocalSyncMod.LOGGER.info("LocalSync first video frame ready: texture={} size={}x{}",
                texture, width, height);
        } else if (!missingFrameWarningLogged && now - playerCreatedAt >= 8_000L
                && player.withVideo() && (width <= 0 || height <= 0 || texture <= 0L)) {
            missingFrameWarningLogged = true;
            LocalSyncMod.LOGGER.warn(
                "LocalSync video frame unavailable after 8s: texture={} size={}x{}",
                texture, width, height);
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                    "LocalSync | 视频已播放，但画面纹理尚未生成；请查看 latest.log"));
            }
        }
        if (now - lastCorrectionAt >= CORRECTION_INTERVAL_MS) {
            lastCorrectionAt = now;
            correct(false);
        }
    }

    private void applyTransportState() {
        MediaPlayer current = player;
        if (current == null) {
            return;
        }
        try {
            if (snapshot.paused()) {
                if (!current.paused()) {
                    current.pause();
                }
            } else if (current.paused()) {
                current.resume();
            }
        } catch (Throwable ignored) {
        }
    }

    private void correct(boolean force) {
        MediaPlayer current = player;
        if (current == null || !snapshot.active()) {
            return;
        }
        try {
            long expected = expectedPosition();
            long drift = expected - current.time();
            if (force || Math.abs(drift) >= DRIFT_THRESHOLD_MS) {
                current.seek(expected);
            }
            applyTransportState();
        } catch (Throwable error) {
            LocalSyncMod.LOGGER.debug("Playback correction deferred", error);
        }
    }

    public long expectedPosition() {
        SnapshotPayload state = snapshot;
        if (!state.active() || state.paused()) {
            return Math.max(0L, state.positionMs());
        }
        return Math.max(0L, state.positionMs()
            + Math.max(0L, System.currentTimeMillis() - snapshotReceivedAt));
    }

    public void close() {
        generation++;
        releasePlayer();
        phase = Phase.IDLE;
        snapshot = new SnapshotPayload(false, false, 0, 0L, "", "");
        errorText = "";
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try {
                old.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName() : message;
    }

    public void setVolume(int value) {
        volume = Math.max(0, Math.min(100, value));
        MediaPlayer current = player;
        if (current != null) {
            current.volume(volume);
        }
    }

    public void toggleVisible() {
        visible = !visible;
    }

    public void toggleFlip() {
        flipVertical = !flipVertical;
    }

    public boolean hasVideoFrame() {
        MediaPlayer current = player;
        return visible && current != null && current.withVideo()
            && current.texture() > 0L && current.width() > 0 && current.height() > 0;
    }

    public String displayTitle() {
        String value = snapshot.mediaUrl();
        if (value == null || value.isBlank()) {
            return "LocalSync";
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host != null) {
                String tail = path == null || path.equals("/") ? "" : path;
                String title = host + tail;
                return title.length() > 54 ? title.substring(0, 51) + "..." : title;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return value.length() > 54 ? value.substring(0, 51) + "..." : value;
    }

    public String statusText() {
        if (phase == Phase.ERROR) {
            return errorText;
        }
        if (!snapshot.active()) {
            return "空闲";
        }
        if (phase == Phase.LOADING) {
            return "正在解析媒体";
        }
        String by = snapshot.actor();
        String base = snapshot.paused() ? "已暂停" : "同步播放中";
        return by == null || by.isBlank() ? base : base + " · " + by;
    }

    public Phase phase() { return phase; }
    public SnapshotPayload snapshot() { return snapshot; }
    public MediaPlayer player() { return player; }
    public int volume() { return volume; }
    public boolean visible() { return visible; }
    public boolean flipVertical() { return flipVertical; }
}
