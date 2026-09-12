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
import org.watermedia.api.util.MediaQuality;

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
        new SnapshotPayload(false, false, 0, 0L, "", "", "");
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
    private boolean slowFrameNoticeSent;
    private int advanceGeneration = -1;
    private int advanceRevision = Integer.MIN_VALUE;
    private String advanceMediaUrl = "";
    private long liveReconnectAt;
    private int liveReconnectAttempts;

    private PlaybackSession() {
    }

    public static PlaybackSession instance() {
        return INSTANCE;
    }

    public void onJoin() {
        generation++;
        releasePlayer();
        snapshot = new SnapshotPayload(false, false, 0, 0L, "", "", "");
        phase = Phase.IDLE;
        errorText = "";
        resetAutoAdvance();
        resetLiveReconnect();
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
            resetLiveReconnect();
            return;
        }

        boolean changedMedia = !incoming.mediaUrl().equals(previous.mediaUrl());
        boolean changedRevision = incoming.revision() != previous.revision();
        if (changedMedia) {
            resetLiveReconnect();
        }
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
            created.quality(MediaQuality.Q8K);
            created.lod(MediaPlayer.LodLevel.MAX);
            if (!created.start()) {
                created.onStatus(null);
                created.release();
                throw new IllegalStateException("WaterMedia rejected the start request");
            }
            player = created;
            phase = Phase.READY;
            liveReconnectAt = 0L;
            playerCreatedAt = System.currentTimeMillis();
            firstVideoFrameLogged = false;
            slowFrameNoticeSent = false;
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
        if (snapshot.active() && BilibiliLiveResolver.isLiveInput(snapshot.mediaUrl())) {
            scheduleLiveReconnect(message);
            return;
        }
        phase = Phase.ERROR;
        resetAutoAdvance();
        errorText = message == null ? "未知错误" : message;
        LocalSyncMod.LOGGER.warn("LocalSync playback error: {}", errorText);
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                "LocalSync | 播放失败：" + errorText + "；详情请查看 latest.log"));
        }
    }

    public void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (client.level != null && snapshot.active() && liveReconnectAt > 0L
                && now >= liveReconnectAt) {
            liveReconnectAt = 0L;
            beginResolve(snapshot.mediaUrl());
            return;
        }
        MediaPlayer current = player;
        if (client.level == null || current == null || !snapshot.active()) {
            return;
        }
        if (BilibiliLiveResolver.isLiveInput(snapshot.mediaUrl())
                && (current.ended() || current.error() || current.stopped())) {
            scheduleLiveReconnect("直播流已断开");
            return;
        }
        if (current.ended()) {
            handleEnded(generation, current);
            return;
        }
        int width = current.width();
        int height = current.height();
        long texture = current.texture();
        boolean frameReady = width > 0 && height > 0 && texture > 0L;
        if (frameReady && !firstVideoFrameLogged) {
            firstVideoFrameLogged = true;
            LocalSyncMod.LOGGER.info("LocalSync first video frame ready: texture={} size={}x{}",
                texture, width, height);
            if (slowFrameNoticeSent && client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                    "LocalSync | 画面加载完成"));
            }
        } else if (!slowFrameNoticeSent && now - playerCreatedAt >= 8_000L
                && current.withVideo() && (width <= 0 || height <= 0 || texture <= 0L)) {
            slowFrameNoticeSent = true;
            LocalSyncMod.LOGGER.info(
                "LocalSync video frame is still initializing after 8s: texture={} size={}x{}",
                texture, width, height);
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                    "LocalSync | 画面加载较慢，播放器尚未报告失败；"
                        + "直播或高码率视频可能需要几十秒，请继续等待"));
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
        if (BilibiliLiveResolver.isLiveInput(endedState.mediaUrl())) {
            scheduleLiveReconnect("直播流已结束");
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
        if (QueueState.instance().hasEntries()) {
            phase = Phase.ADVANCING;
            errorText = "";
            submitAdvance(revision, mediaUrl, "");
            return;
        }
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
        if (QueueState.instance().hasEntries()) {
            submitAdvance(revision, mediaUrl, "");
            return;
        }
        if (failure != null) {
            phase = Phase.ENDED;
            errorText = "自动连播失败: " + readable(failure);
            LocalSyncMod.LOGGER.warn("LocalSync auto-advance resolution failed", failure);
            if (endedPlayer != null && endedPlayer.ended()) {
                submitAdvance(revision, mediaUrl, "");
            }
            return;
        }
        if (next.isEmpty() || next.get().equals(mediaUrl)) {
            boolean playbackFinished = endedPlayer != null && endedPlayer.ended();
            phase = playbackFinished ? Phase.ENDED : Phase.READY;
            errorText = "";
            LocalSyncMod.LOGGER.info("LocalSync reached the end of the current collection");
            if (playbackFinished) {
                submitAdvance(revision, mediaUrl, "");
            }
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

    public void requestNext() {
        SnapshotPayload state = snapshot;
        if (!state.active()) {
            errorText = "当前没有正在播放的视频";
            return;
        }
        if (BilibiliLiveResolver.isLiveInput(state.mediaUrl())) {
            errorText = "直播不使用下一集";
            return;
        }
        int taskGeneration = generation;
        MediaPlayer expectedPlayer = player;
        int revision = state.revision();
        String mediaUrl = state.mediaUrl();
        advanceGeneration = taskGeneration;
        advanceRevision = revision;
        advanceMediaUrl = mediaUrl;
        phase = Phase.ADVANCING;
        errorText = "";
        if (QueueState.instance().hasEntries()) {
            submitAdvance(revision, mediaUrl, "");
            return;
        }
        RESOLVER.execute(() -> {
            try {
                Optional<String> next = BilibiliAutoplayResolver.resolveNext(mediaUrl);
                Minecraft.getInstance().execute(() -> finishAutoAdvance(
                    taskGeneration, expectedPlayer, revision, mediaUrl, next, null));
            } catch (Throwable error) {
                Minecraft.getInstance().execute(() -> finishAutoAdvance(
                    taskGeneration, expectedPlayer, revision, mediaUrl,
                    Optional.empty(), error));
            }
        });
    }

    private void submitAdvance(int revision, String mediaUrl, String fallbackUrl) {
        if (!ClientPlayNetworking.canSend(AdvancePayload.TYPE)) {
            phase = Phase.ENDED;
            errorText = "服务端版本不支持播放队列";
            return;
        }
        ClientPlayNetworking.send(new AdvancePayload(revision, mediaUrl,
            fallbackUrl == null ? "" : fallbackUrl));
        LocalSyncMod.LOGGER.info("Submitted LocalSync queue-first advance for revision {}",
            revision);
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

    private void resetLiveReconnect() {
        liveReconnectAt = 0L;
        liveReconnectAttempts = 0;
        playerCreatedAt = 0L;
    }

    private void scheduleLiveReconnect(String reason) {
        if (liveReconnectAt > 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (playerCreatedAt > 0L && now - playerCreatedAt >= 30_000L) {
            liveReconnectAttempts = 0;
        }
        playerCreatedAt = 0L;
        releasePlayer();
        if (liveReconnectAttempts >= 5) {
            phase = Phase.ERROR;
            errorText = "直播重连失败: " + reason;
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                    "LocalSync | 直播播放失败：连续重试 5 次仍未恢复；"
                        + "详情请查看 latest.log"));
            }
            SnapshotPayload state = snapshot;
            if (state.active() && BilibiliLiveResolver.isLiveInput(state.mediaUrl())) {
                submitAdvance(state.revision(), state.mediaUrl(), "");
            }
            return;
        }
        liveReconnectAttempts++;
        long delay = Math.min(15_000L, 1_000L << (liveReconnectAttempts - 1));
        liveReconnectAt = now + delay;
        phase = Phase.LOADING;
        errorText = "直播重连 " + liveReconnectAttempts + "/5";
        LocalSyncMod.LOGGER.info("LocalSync live reconnect {} scheduled in {}ms",
            liveReconnectAttempts, delay);
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
        if (BilibiliLiveResolver.isLiveInput(snapshot.mediaUrl())) {
            applyTransportState();
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
        snapshot = new SnapshotPayload(false, false, 0, 0L, "", "", "");
        errorText = "";
        resetAutoAdvance();
        resetLiveReconnect();
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
        String suppliedTitle = snapshot.mediaTitle();
        if (suppliedTitle != null && !suppliedTitle.isBlank()) {
            return suppliedTitle.length() > 54
                ? suppliedTitle.substring(0, 51) + "..." : suppliedTitle;
        }
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
        if (BilibiliLiveResolver.isLiveInput(snapshot.mediaUrl())) {
            return snapshot.paused() ? "直播已暂停" : "直播中";
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
    public boolean live() {
        return snapshot.active() && BilibiliLiveResolver.isLiveInput(snapshot.mediaUrl());
    }
}
