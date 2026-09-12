package dev.localsync.server;

import java.util.ArrayDeque;
import java.util.List;

public final class RoomTimeline {
    public record State(boolean active, boolean paused, int revision,
                        long positionMs, String actor, String mediaUrl,
                        String mediaTitle) {
    }

    public record QueueItem(long id, String requester, String title, String mediaUrl) {
    }

    public record QueueState(int revision, List<QueueItem> entries) {
    }

    public enum RequestResult { STARTED, QUEUED, FULL }
    public enum AdvanceResult { QUEUED, FALLBACK, NONE, STALE }

    public static final int MAX_QUEUE_ENTRIES = 64;

    private final ArrayDeque<QueueItem> queue = new ArrayDeque<>();
    private int queueRevision;
    private long nextQueueId = 1L;

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    private boolean active;
    private boolean paused;
    private int revision;
    private long anchorPositionMs;
    private long anchorWallMs;
    private String actor = "";
    private String mediaUrl = "";
    private String mediaTitle = "";

    public void reset(long now) {
        active = false;
        paused = false;
        revision = 0;
        anchorPositionMs = 0L;
        anchorWallMs = now;
        actor = "";
        mediaUrl = "";
        mediaTitle = "";
        queue.clear();
        queueRevision = 0;
        nextQueueId = 1L;
    }

    public void play(String url, String title, String by, long now) {
        active = true;
        paused = false;
        anchorPositionMs = 0L;
        anchorWallMs = now;
        mediaUrl = url;
        mediaTitle = clean(title);
        actor = by;
        revision++;
    }

    public void play(String url, String by, long now) {
        play(url, "", by, now);
    }

    public RequestResult request(String url, String title, String by, long now) {
        if (!active) {
            play(url, title, by, now);
            return RequestResult.STARTED;
        }
        if (queue.size() >= MAX_QUEUE_ENTRIES) {
            return RequestResult.FULL;
        }
        queue.addLast(new QueueItem(nextQueueId++, clean(by), clean(title), url));
        queueRevision++;
        return RequestResult.QUEUED;
    }

    public AdvanceResult advanceIfCurrent(int expectedRevision, String expectedMediaUrl,
                                          String nextMediaTitle, String nextMediaUrl,
                                          String by, long now) {
        if (!active || revision != expectedRevision
                || !mediaUrl.equals(expectedMediaUrl)) {
            return AdvanceResult.STALE;
        }
        QueueItem queued = queue.pollFirst();
        if (queued != null) {
            queueRevision++;
            play(queued.mediaUrl(), queued.title(), queued.requester(), now);
            return AdvanceResult.QUEUED;
        }
        if (nextMediaUrl == null || nextMediaUrl.isBlank()
                || nextMediaUrl.equals(expectedMediaUrl)) {
            stop(by, now);
            return AdvanceResult.NONE;
        }
        play(nextMediaUrl, nextMediaTitle, by, now);
        return AdvanceResult.FALLBACK;
    }

    public void pause(String by, long now) {
        if (!active || paused) {
            return;
        }
        anchorPositionMs = position(now);
        paused = true;
        actor = by;
        revision++;
    }

    public void resume(String by, long now) {
        if (!active || !paused) {
            return;
        }
        anchorWallMs = now;
        paused = false;
        actor = by;
        revision++;
    }

    public void seekRelative(long deltaMs, String by, long now) {
        if (!active) {
            return;
        }
        anchorPositionMs = Math.max(0L, position(now) + deltaMs);
        anchorWallMs = now;
        actor = by;
        revision++;
    }

    public void stop(String by, long now) {
        if (!active) {
            return;
        }
        active = false;
        paused = false;
        anchorPositionMs = 0L;
        anchorWallMs = now;
        mediaUrl = "";
        mediaTitle = "";
        actor = by;
        revision++;
        if (!queue.isEmpty()) {
            queue.clear();
            queueRevision++;
        }
    }

    public long position(long now) {
        if (!active || paused) {
            return Math.max(0L, anchorPositionMs);
        }
        return Math.max(0L, anchorPositionMs + Math.max(0L, now - anchorWallMs));
    }

    public State state(long now) {
        return new State(active, paused, revision, position(now), actor,
            mediaUrl, mediaTitle);
    }

    public QueueState queueState() {
        return new QueueState(queueRevision, List.copyOf(queue));
    }

    public boolean active() {
        return active;
    }
}
