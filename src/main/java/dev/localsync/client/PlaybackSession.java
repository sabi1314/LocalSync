package dev.localsync.client;

import dev.localsync.LocalSyncMod;
import dev.localsync.net.Packets.AdvancePayload;
import dev.localsync.net.Packets.SnapshotPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaybackSession {
    public enum Phase { IDLE, LOADING, READY, ADVANCING, ENDED, ERROR }

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
    private int advanceGeneration = -1;
    private int advanceRevision = Integer.MIN_VALUE;
    private String advanceMediaUrl = "";

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
        resetAutoAdvance();
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
            resetAutoAdvance();
            return;
        }

        boolean changedMedia = !incoming.mediaUrl().equals(previous.mediaUrl());
        boolean changedRevision = incoming.revision() != previous.revision();
        if (changedMedia || player == null && phase != Phase.LOADING && changedRevision) {
            beginResolve(incoming.mediaUrl());
        } else {
            if (changedRevision) {
                resumeEndedPlayer(incoming);
            }
            applyTransportState();
        }
    }

    private void beginResolve(String mediaUrl) {
        int taskGeneration = ++generation;
        releasePlayer();
        phase = Phase.LOADING;
        errorText = "";
        resetAutoAdvance();
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
            if (created == null) {
                throw new IllegalStateException("WaterMedia did not create a player");
            }
            created.onStatus((previous, current) -> {
                if (current == MediaPlayer.Status.ENDED) {
                    client.execute(() -> handleEnded(taskGeneration, created));
                }
            });
            created.repeat(false);
            created.volume(volume);
            if (!created.start()) {
                created.onStatus(null);
                created.release();
                throw new IllegalStateException("WaterMedia rejected the start request");
            }
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
        resetAutoAdvance();
        errorText = message == null ? "未知错误" : message;
        LocalSyncMod.LOGGER.warn("LocalSync playback error: {}", errorText);
    }

    public void tick(Minecraft client) {
        MediaPlayer current = player;
        if (client.level == null || current == null || !snapshot.active()) {
            return;
        }
        if (current.ended()) {
            handleEnded(generation, current);
            return;
        }
        long now = System.currentTimeMillis();
        int width = current.width();
        int height = current.height();
        long texture = current.texture();
        if (!firstVideoFrameLogged && width > 0 && height > 0 && texture > 0L) {
            firstVideoFrameLogged = true;
            LocalSyncMod.LOGGER.info("LocalSync first video frame ready: texture={} size={}x{}",
                texture, width, height);
        } else if (!missingFrameWarningLogged && now - playerCreatedAt >= 8_000L
                && current.withVideo() && (width <= 0 || height <= 0 || texture <= 0L)) {
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

    private void handleEnded(int playerGeneration, MediaPlayer endedPlayer) {
        SnapshotPayload endedState = snapshot;
        if (playerGeneration != generation || player != endedPlayer
                || !endedState.active() || endedState.paused() || !endedPlayer.ended()) {
            return;
        }
        int revision = endedState.revision();
        String mediaUrl = endedState.mediaUrl();
        if (advanceGeneration == playerGeneration && advanceRevision == revision
                && advanceMediaUrl.equals(mediaUrl)) {
            return;
        }
        advanceGeneration = playerGeneration;
        advanceRevision = revision;
        advanceMediaUrl = mediaUrl;
        phase = Phase.ADVANCING;
        errorText = "";
        LocalSyncMod.LOGGER.info(
            "LocalSync playback ended at revision {}; resolving auto-advance", revision);

        RESOLVER.execute(() -> {
            try {
                Optional<String> next = BilibiliAutoplayResolver.resolveNext(mediaUrl);
                Minecraft.getInstance().execute(() -> finishAutoAdvance(
                    playerGeneration, endedPlayer, revision, mediaUrl, next, null));
            } catch (Throwable error) {
                Minecraft.getInstance().execute(() -> finishAutoAdvance(
                    playerGeneration, endedPlayer, revision, mediaUrl,
                    Optional.empty(), error));
            }
        });
    }

    private void finishAutoAdvance(int playerGeneration, MediaPlayer endedPlayer,
                                   int revision, String mediaUrl,
                                   Optional<String> next, Throwable failure) {
        SnapshotPayload currentState = snapshot;
        if (playerGeneration != generation || player != endedPlayer
                || advanceGeneration != playerGeneration || advanceRevision != revision
                || !advanceMediaUrl.equals(mediaUrl) || !currentState.active()
                || currentState.revision() != revision
                || !currentState.mediaUrl().equals(mediaUrl)) {
            return;
        }
        if (failure != null) {
            phase = Phase.ENDED;
            errorText = "自动连播失败: " + readable(failure);
            LocalSyncMod.LOGGER.warn("LocalSync auto-advance resolution failed", failure);
            return;
        }
        if (next.isEmpty() || next.get().equals(mediaUrl)) {
            phase = Phase.ENDED;
            errorText = "";
            LocalSyncMod.LOGGER.info("LocalSync reached the end of the current collection");
            return;
        }
        if (!ClientPlayNetworking.canSend(AdvancePayload.TYPE)) {
            phase = Phase.ENDED;
            errorText = "服务器不支持自动连播";
            return;
        }
        ClientPlayNetworking.send(new AdvancePayload(revision, mediaUrl, next.get()));
        LocalSyncMod.LOGGER.info("Submitted LocalSync auto-advance for revision {}", revision);
    }

    private void resumeEndedPlayer(SnapshotPayload incoming) {
        MediaPlayer current = player;
        if (current == null || !current.ended()) {
            return;
        }
        resetAutoAdvance();
        phase = Phase.READY;
        errorText = "";
        if (incoming.paused()) {
            return;
        }
        try {
            current.seek(expectedPosition());
        } catch (Throwable error) {
            LocalSyncMod.LOGGER.debug("Playback restart after timeline update deferred", error);
        }
    }

    private void resetAutoAdvance() {
        advanceGeneration = -1;
        advanceRevision = Integer.MIN_VALUE;
        advanceMediaUrl = "";
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
            if (current.ended() || current.error() || current.stopped()) {
                return;
            }
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
        resetAutoAdvance();
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try {
                old.onStatus(null);
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
        if (phase == Phase.ADVANCING) {
            return "正在获取下一视频";
        }
        if (phase == Phase.ENDED) {
            return errorText.isBlank() ? "播放结束" : errorText;
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
