package dev.localsync.client;

import java.util.List;

public final class BilibiliCoverCacheTest {
    private BilibiliCoverCacheTest() {
    }

    public static void main(String[] args) {
        BilibiliCoverCache.AccessOrderCache<String, Integer> cache =
            new BilibiliCoverCache.AccessOrderCache<>(2);

        check(cache.put("one", 1).isEmpty(), "first insert must not evict");
        check(cache.put("two", 2).isEmpty(), "second insert must not evict");
        check(cache.get("one") == 1, "cache lookup failed");
        check(cache.put("three", 3).equals(List.of(2)),
            "least-recently-used entry was not evicted");
        check(cache.get("two") == null && cache.get("one") == 1,
            "access order was not retained");
        check(cache.put("one", 11).equals(List.of(1)),
            "replaced value must be released");
        check(cache.size() == 2, "replacement changed cache size");
        check(cache.clear().equals(List.of(3, 11)), "clear order or contents are wrong");
        check(cache.size() == 0, "clear did not empty cache");

        try {
            new BilibiliCoverCache.AccessOrderCache<String, Integer>(0);
            throw new AssertionError("non-positive capacity must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }

        System.out.println("PASS BilibiliCoverCache: bounded access-order eviction");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
