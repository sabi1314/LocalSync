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

        RoomTimeline autoplay = new RoomTimeline();
        autoplay.reset(20_000L);
        String current = "https://www.bilibili.com/video/BV1xx411c7mD";
        String next = "https://www.bilibili.com/video/BV1GJ411x7h7";
        autoplay.play(current, "host", 21_000L);
        int expectedRevision = autoplay.state(21_000L).revision();
        check(autoplay.advanceIfCurrent(expectedRevision + 1, current, "", next,
            "friend", 22_000L) == RoomTimeline.AdvanceResult.STALE,
            "stale revision must be rejected");
        check(autoplay.advanceIfCurrent(expectedRevision, current + "?p=2", "", next,
            "friend", 22_000L) == RoomTimeline.AdvanceResult.STALE,
            "wrong expected URL must be rejected");
        check(autoplay.advanceIfCurrent(expectedRevision, current, "", next,
            "friend", 22_000L) == RoomTimeline.AdvanceResult.FALLBACK,
            "matching auto-advance must succeed");
        RoomTimeline.State advanced = autoplay.state(22_000L);
        check(advanced.revision() == expectedRevision + 1
                && advanced.positionMs() == 0L && !advanced.paused()
                && next.equals(advanced.mediaUrl())
                && "friend".equals(advanced.actor()),
            "auto-advance must atomically start the next URL");
        check(autoplay.advanceIfCurrent(expectedRevision, current, "", next,
            "host", 23_000L) == RoomTimeline.AdvanceResult.STALE,
            "duplicate auto-advance must be rejected");

        RoomTimeline fifo = new RoomTimeline();
        fifo.reset(30_000L);
        check(fifo.request("https://example.test/a", "A", "Alice", 30_000L)
            == RoomTimeline.RequestResult.STARTED, "first request must start immediately");
        check(fifo.request("https://example.test/b", "B", "Bob", 30_100L)
            == RoomTimeline.RequestResult.QUEUED, "second request must enter the queue");
        check(fifo.request("https://example.test/c", "C", "Carol", 30_200L)
            == RoomTimeline.RequestResult.QUEUED, "third request must enter the queue");
        int fifoRevision = fifo.state(30_200L).revision();
        check(fifo.advanceIfCurrent(fifoRevision, "https://example.test/a", "related",
            "https://example.test/related", "Alice", 31_000L)
            == RoomTimeline.AdvanceResult.QUEUED, "queue must take priority over related media");
        check("https://example.test/b".equals(fifo.state(31_000L).mediaUrl())
            && "B".equals(fifo.state(31_000L).mediaTitle())
            && "Bob".equals(fifo.state(31_000L).actor()), "FIFO head must play first");
        int secondRevision = fifo.state(31_000L).revision();
        check(fifo.advanceIfCurrent(secondRevision, "https://example.test/b", "", "",
            "Bob", 32_000L) == RoomTimeline.AdvanceResult.QUEUED,
            "manual next must consume the next queued request");
        check("https://example.test/c".equals(fifo.state(32_000L).mediaUrl()),
            "FIFO order must be preserved");
        int thirdRevision = fifo.state(32_000L).revision();
        check(fifo.advanceIfCurrent(thirdRevision, "https://example.test/c", "", "",
            "Carol", 33_000L) == RoomTimeline.AdvanceResult.NONE,
            "empty queue without fallback must end advancement");
        check(!fifo.state(33_000L).active(),
            "natural completion without a queue or fallback must return to idle");
        fifo.request("https://example.test/d", "D", "Dan", 33_100L);
        fifo.request("https://example.test/e", "E", "Eve", 33_150L);
        fifo.stop("Alice", 33_200L);
        check(fifo.queueState().entries().isEmpty(), "stop must clear the queue");

        RoomTimeline capacity = new RoomTimeline();
        capacity.reset(40_000L);
        capacity.request("https://example.test/current", "Current", "Host", 40_000L);
        for (int index = 0; index < RoomTimeline.MAX_QUEUE_ENTRIES; index++) {
            check(capacity.request("https://example.test/" + index, "Item " + index,
                "Guest", 40_001L + index) == RoomTimeline.RequestResult.QUEUED,
                "queue must accept entry " + index);
        }
        check(capacity.request("https://example.test/full", "Full", "Guest", 41_000L)
            == RoomTimeline.RequestResult.FULL, "queue capacity must be enforced");

        System.out.println(
            "PASS RoomTimeline: transport, FIFO queue, and compare-and-set advance");

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
