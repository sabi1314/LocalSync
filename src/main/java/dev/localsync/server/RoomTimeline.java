package dev.localsync.server;

public final class RoomTimeline {
    public record State(boolean active, boolean paused, int revision,
                        long positionMs, String actor, String mediaUrl) {
    }

    private boolean active;
    private boolean paused;
    private int revision;
    private long anchorPositionMs;
    private long anchorWallMs;
    private String actor = "";
    private String mediaUrl = "";

    public void reset(long now) {
        active = false;
        paused = false;
        revision = 0;
        anchorPositionMs = 0L;
        anchorWallMs = now;
        actor = "";
        mediaUrl = "";
    }

    public void play(String url, String by, long now) {
        active = true;
        paused = false;
        anchorPositionMs = 0L;
        anchorWallMs = now;
        mediaUrl = url;
        actor = by;
        revision++;
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
        actor = by;
        revision++;
    }

    public long position(long now) {
        if (!active || paused) {
            return Math.max(0L, anchorPositionMs);
        }
        return Math.max(0L, anchorPositionMs + Math.max(0L, now - anchorWallMs));
    }

    public State state(long now) {
        return new State(active, paused, revision, position(now), actor, mediaUrl);
    }

    public boolean active() {
        return active;
    }
}
