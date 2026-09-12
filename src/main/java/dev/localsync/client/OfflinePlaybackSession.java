package dev.localsync.client;

import dev.localsync.LocalSyncMod;
import net.minecraft.client.Minecraft;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.util.MediaQuality;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OfflinePlaybackSession {
    public enum Phase { IDLE, LOADING, READY, ADVANCING, ENDED, ERROR }

    private static final long RESOLVE_TIMEOUT_MS = 60_000L;
    private static final OfflinePlaybackSession INSTANCE = new OfflinePlaybackSession();
    private static final ExecutorService RESOLVER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "localsync-offline-resolver");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Phase phase = Phase.IDLE;
    private volatile MediaPlayer player;
    private volatile String mediaUrl = "";
    private volatile String mediaTitle = "";
    private volatile String errorText = "";
    private volatile int generation;
    private volatile int volume = 70;
    private volatile boolean visible = true;
    private volatile boolean flipVertical;
    private long playerCreatedAt;
    private long liveReconnectAt;
    private int liveReconnectAttempts;

    private OfflinePlaybackSession() {
    }

    public static OfflinePlaybackSession instance() {
        return INSTANCE;
    }

    public void play(String url, String title) {
        if (url == null || url.isBlank()) {
            errorText = "请输入有效的视频链接";
            phase = Phase.ERROR;
            return;
        }
        mediaUrl = url.trim();
        mediaTitle = title == null ? "" : title.trim();
        resetLiveReconnect();
        beginResolve(mediaUrl);
    }

    private void beginResolve(String url) {
        int taskGeneration = ++generation;
        releasePlayer();
        phase = Phase.LOADING;
        errorText = "";
        RESOLVER.execute(() -> {
            try {
                String resolved = BilibiliResolver.resolveIfNeeded(url);
                MRL mrl = MediaAPI.mrl(resolved);
                boolean completed = mrl.await(RESOLVE_TIMEOUT_MS);
                if (!completed || !mrl.status().loaded() || mrl.sourceCount() == 0) {
                    Throwable cause = mrl.exception();
                    String detail = cause == null ? String.valueOf(mrl.status())
                        : readable(cause);
                    failOnClient(taskGeneration, "解析失败: " + detail);
                    return;
                }
                Minecraft.getInstance().execute(() -> createPlayer(taskGeneration, mrl));
            } catch (Throwable error) {
                failOnClient(taskGeneration, "解析失败: " + readable(error));
            }
        });
    }

    private void createPlayer(int taskGeneration, MRL mrl) {
        if (taskGeneration != generation) {
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
            playerCreatedAt = System.currentTimeMillis();
            liveReconnectAt = 0L;
            LocalSyncMod.LOGGER.info("LocalSync offline player ready: video={} size={}x{}",
                created.withVideo(), created.width(), created.height());
        } catch (Throwable error) {
            fail(taskGeneration, "播放器启动失败: " + readable(error));
        }
    }

    public void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (client.level != null && liveReconnectAt > 0L && now >= liveReconnectAt) {
            liveReconnectAt = 0L;
            beginResolve(mediaUrl);
            return;
        }
        MediaPlayer current = player;
        if (client.level != null && current != null
                && BilibiliLiveResolver.isLiveInput(mediaUrl)
                && (current.ended() || current.error() || current.stopped())) {
            scheduleLiveReconnect("直播流已断开");
        } else if (client.level != null && current != null && current.ended()) {
            handleEnded(generation, current);
        }
    }

    private void handleEnded(int taskGeneration, MediaPlayer endedPlayer) {
        if (taskGeneration != generation || player != endedPlayer
                || !endedPlayer.ended() || phase == Phase.ADVANCING) {
            return;
        }
        if (BilibiliLiveResolver.isLiveInput(mediaUrl)) {
            scheduleLiveReconnect("直播流已结束");
            return;
        }
        resolveNext(taskGeneration, endedPlayer);
    }

    public void requestNext() {
        MediaPlayer current = player;
        if (mediaUrl.isBlank()) {
            errorText = "当前没有正在播放的视频";
            return;
        }
        if (BilibiliLiveResolver.isLiveInput(mediaUrl)) {
            errorText = "直播不使用下一集";
            return;
        }
        resolveNext(generation, current);
    }

    private void resolveNext(int taskGeneration, MediaPlayer expectedPlayer) {
        String currentUrl = mediaUrl;
        phase = Phase.ADVANCING;
        errorText = "";
        RESOLVER.execute(() -> {
            try {
                Optional<String> next = BilibiliAutoplayResolver.resolveNext(currentUrl);
                Minecraft.getInstance().execute(() -> {
                    if (taskGeneration != generation || player != expectedPlayer
                            || !mediaUrl.equals(currentUrl)) {
                        return;
                    }
                    if (next.isPresent() && !next.get().equals(currentUrl)) {
                        mediaTitle = "";
                        mediaUrl = next.get();
                        beginResolve(mediaUrl);
                    } else {
                        phase = Phase.ENDED;
                    }
                });
            } catch (Throwable error) {
                failOnClient(taskGeneration, "获取后续视频失败: " + readable(error));
            }
        });
    }

    public void togglePause() {
        MediaPlayer current = player;
        if (current == null) {
            return;
        }
        try {
            if (current.paused()) {
                current.resume();
            } else {
                current.pause();
            }
        } catch (Throwable error) {
            errorText = "切换暂停状态失败: " + readable(error);
        }
    }

    public void seekRelative(long deltaMs) {
        MediaPlayer current = player;
        if (current == null || BilibiliLiveResolver.isLiveInput(mediaUrl)) {
            return;
        }
        try {
            current.seek(Math.max(0L, current.time() + deltaMs));
        } catch (Throwable error) {
            errorText = "跳转失败: " + readable(error);
        }
    }

    public void stop() {
        generation++;
        releasePlayer();
        mediaUrl = "";
        mediaTitle = "";
        errorText = "";
        phase = Phase.IDLE;
        resetLiveReconnect();
    }

    public void close() {
        stop();
    }

    private void failOnClient(int taskGeneration, String message) {
        Minecraft.getInstance().execute(() -> fail(taskGeneration, message));
    }

    private void fail(int taskGeneration, String message) {
        if (taskGeneration != generation) {
            return;
        }
        releasePlayer();
        if (BilibiliLiveResolver.isLiveInput(mediaUrl)) {
            scheduleLiveReconnect(message);
            return;
        }
        phase = Phase.ERROR;
        errorText = message;
        LocalSyncMod.LOGGER.warn("LocalSync offline playback error: {}", message);
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
            return;
        }
        liveReconnectAttempts++;
        long delay = Math.min(15_000L, 1_000L << (liveReconnectAttempts - 1));
        liveReconnectAt = now + delay;
        phase = Phase.LOADING;
        errorText = "直播重连 " + liveReconnectAttempts + "/5";
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

    public void toggleVisible() { visible = !visible; }
    public void toggleFlip() { flipVertical = !flipVertical; }

    public boolean hasVideoFrame() {
        MediaPlayer current = player;
        return visible && current != null && current.withVideo()
            && current.texture() > 0L && current.width() > 0 && current.height() > 0;
    }

    public String displayTitle() {
        if (!mediaTitle.isBlank()) {
            return mediaTitle;
        }
        if (mediaUrl.isBlank()) {
            return "离线屏幕";
        }
        try {
            URI uri = URI.create(mediaUrl);
            return uri.getHost() == null ? mediaUrl : uri.getHost() + uri.getPath();
        } catch (IllegalArgumentException ignored) {
            return mediaUrl;
        }
    }

    public String statusText() {
        if (BilibiliLiveResolver.isLiveInput(mediaUrl) && phase == Phase.READY) {
            return player != null && player.paused() ? "私人直播已暂停" : "私人直播中";
        }
        return switch (phase) {
            case IDLE -> "离线屏幕空闲";
            case LOADING -> "离线视频解析中";
            case READY -> player != null && player.paused() ? "离线播放已暂停" : "离线播放中";
            case ADVANCING -> "正在获取下一集";
            case ENDED -> "离线播放结束";
            case ERROR -> errorText;
        };
    }

    public Phase phase() { return phase; }
    public MediaPlayer player() { return player; }
    public String mediaUrl() { return mediaUrl; }
    public int volume() { return volume; }
    public boolean visible() { return visible; }
    public boolean flipVertical() { return flipVertical; }
}
