package dev.localsync.server;

public final class RoomTimelineTest {
    public static void main(String[] args) {
        RoomTimeline timeline = new RoomTimeline();
        timeline.reset(1_000L);
        check(!timeline.state(1_000L).active(), "reset must be idle");

        timeline.play("https://example.test/video.mp4", "host", 2_000L);
        check(timeline.position(5_500L) == 3_500L, "playing clock must advance");

        timeline.pause("friend", 6_000L);
        check(timeline.position(9_000L) == 4_000L, "pause must freeze time");

        timeline.seekRelative(-1_500L, "friend", 9_000L);
        check(timeline.position(12_000L) == 2_500L, "seek while paused must stay frozen");

        timeline.resume("host", 12_000L);
        check(timeline.position(13_250L) == 3_750L, "resume must continue from anchor");

        timeline.seekRelative(-20_000L, "host", 13_250L);
        check(timeline.position(13_250L) == 0L, "negative seek must clamp to zero");

        timeline.stop("host", 14_000L);
        RoomTimeline.State stopped = timeline.state(20_000L);
        check(!stopped.active() && stopped.positionMs() == 0L
            && stopped.mediaUrl().isEmpty(), "stop must clear active media");

        System.out.println("PASS RoomTimeline: play/pause/seek/resume/stop");

        String shared = UrlNormalizer.normalize(
            "【测试视频】 https://b23.tv/AbCd123。 复制本条信息", 8192);
        check("https://b23.tv/AbCd123".equals(shared), "share text URL extraction failed");
        check(UrlNormalizer.normalize("没有链接", 8192) == null,
            "text without URL must be rejected");
        System.out.println("PASS UrlNormalizer: Bilibili share text and punctuation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
