package dev.localsync.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BilibiliAccountDataTest {
    private BilibiliAccountDataTest() {
    }

    public static void main(String[] args) throws Exception {
        testCookieNormalization();
        testLoginCookieExtraction();
        testQrStatesAndRedaction();
        testProfileAndFavorites();
        testSessionStoreRoundTrip();
        System.out.println("PASS BilibiliAccount: cookie, QR, profile, favorites, persistence");
    }

    private static void testCookieNormalization() {
        String normalized = BilibiliAccountData.normalizeCookieHeader(
            "Cookie: unknown=drop; dedeuserid=42; SESSDATA=test%2Csession; "
                + "bili_jct=csrf-token; sid=\"sid-value\"; Path=/; buvid3=device-value");
        assertEquals("SESSDATA=test%2Csession; bili_jct=csrf-token; DedeUserID=42; "
            + "sid=sid-value; buvid3=device-value", normalized, "normalized Cookie");
        assertTrue(BilibiliAccountData.hasAuthenticatedCookie(normalized),
            "SESSDATA should mark an authenticated session");
        assertTrue(!BilibiliAccountData.hasAuthenticatedCookie("bili_jct=only-csrf"),
            "bili_jct alone must not mark a session authenticated");
        expectFailure(() -> BilibiliAccountData.normalizeCookieHeader(
            "SESSDATA=value\r\nX-Injected: yes"), "line breaks must be rejected");
    }

    private static void testLoginCookieExtraction() {
        String normalized = BilibiliAccountData.cookiesFromLogin(List.of(
            "SESSDATA=qr%2Csession; Path=/; Domain=.bilibili.com; HttpOnly",
            "bili_jct=qr-csrf; Path=/; Domain=.bilibili.com",
            "ignored=value; Path=/"
        ), "https://passport.bilibili.com/login/success.html?DedeUserID=7788"
            + "&DedeUserID__ckMd5=checksum&gourl=https%3A%2F%2Fwww.bilibili.com");
        assertEquals("SESSDATA=qr%2Csession; bili_jct=qr-csrf; DedeUserID=7788; "
            + "DedeUserID__ckMd5=checksum", normalized, "QR Cookie extraction");
        assertEquals("SESSDATA=new-value; bili_jct=old-token",
            BilibiliAccountData.mergeCookieHeaders(
                "SESSDATA=old-value; bili_jct=old-token", "SESSDATA=new-value"),
            "Cookie merge should prefer the second header");
    }

    private static void testQrStatesAndRedaction() {
        BilibiliAccountData.QrLogin login = BilibiliAccountData.parseQrLogin("""
            {"code":0,"message":"OK","data":{
              "url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1",
              "qrcode_key":"0123456789abcdef0123456789abcdef"
            }}
            """);
        assertTrue(login.url().startsWith("https://account.bilibili.com/"),
            "QR URL should be retained");
        assertTrue(!login.toString().contains(login.key()), "QR key leaked from toString");
        expectFailure(() -> BilibiliAccountData.parseQrLogin("""
            {"code":0,"data":{"url":"https://evilbilibili.com/login",
            "qrcode_key":"0123456789abcdef0123456789abcdef"}}
            """), "lookalike QR host must be rejected");

        assertQrState(86101, BilibiliAccountData.QrState.WAITING_FOR_SCAN);
        assertQrState(86090, BilibiliAccountData.QrState.WAITING_FOR_CONFIRMATION);
        assertQrState(86038, BilibiliAccountData.QrState.EXPIRED);
        assertQrState(12345, BilibiliAccountData.QrState.ERROR);
        BilibiliAccountData.QrPoll confirmed = BilibiliAccountData.parseQrPoll("""
            {"code":0,"data":{"code":0,"message":"","url":
            "https://passport.bilibili.com/login/success?SESSDATA=secret-test-value"}}
            """);
        assertEquals(BilibiliAccountData.QrState.CONFIRMED, confirmed.state(),
            "confirmed QR state");
        assertTrue(!confirmed.toString().contains("secret-test-value"),
            "success URL leaked from QR result toString");
    }

    private static void testProfileAndFavorites() {
        BilibiliAccountData.Profile profile = BilibiliAccountData.parseProfile("""
            {"code":0,"data":{"isLogin":true,"mid":7788,"uname":"Local User",
            "face":"https://i.example/avatar.jpg","vipStatus":1,
            "level_info":{"current_level":6}}}
            """);
        assertEquals(7788L, profile.mid(), "profile mid");
        assertEquals("Local User", profile.name(), "profile name");
        assertEquals(6, profile.level(), "profile level");
        assertTrue(profile.vip(), "profile VIP status");

        List<BilibiliAccountData.FavoriteFolder> folders =
            BilibiliAccountData.parseFavoriteFolders("""
                {"code":0,"data":{"count":2,"list":[
                  {"id":101,"title":"稍后观看","media_count":12},
                  {"media_id":102,"title":"音乐","media_count":8}
                ]}}
                """);
        assertEquals(2, folders.size(), "favorite folder count");
        assertEquals(102L, folders.get(1).id(), "fallback media_id");

        BilibiliAccountData.FavoritePage page = BilibiliAccountData.parseFavoritePage("""
            {"code":0,"data":{"info":{"media_count":31},"has_more":true,"medias":[
              {"id":11,"bvid":"BV1TEST111","title":"可播放视频","cover":"//cover/1",
               "duration":125,"attr":0,"upper":{"name":"UP A"},"cnt_info":{"play":9001}},
              {"id":12,"bvid":"BV1LOST222","title":"已失效视频","duration":0,
               "attr":9,"upper":{"name":"UP B"},"cnt_info":{"play":0}}
            ]}}
            """, 2);
        assertEquals(2, page.page(), "favorite page number");
        assertEquals(31, page.total(), "favorite total");
        assertTrue(page.hasMore(), "favorite has_more");
        assertTrue(page.videos().get(0).available(), "normal favorite video available");
        assertTrue(!page.videos().get(1).available(), "invalid favorite video unavailable");
        assertEquals("https://www.bilibili.com/video/BV1TEST111",
            page.videos().get(0).pageUrl(), "favorite page URL");
        expectFailure(() -> BilibiliAccountData.parseProfile(
            "{\"code\":-101,\"message\":\"账号未登录\",\"data\":null}"),
            "API failure should be surfaced");
    }

    private static void testSessionStoreRoundTrip() throws Exception {
        Path directory = Files.createTempDirectory("localsync-account-test-");
        Path path = directory.resolve("account.json");
        BilibiliSessionStore store = new BilibiliSessionStore(path);
        try {
            store.save("unknown=drop; SESSDATA=persisted-test; bili_jct=csrf-test");
            assertTrue(Files.isRegularFile(path), "session file should exist");
            assertEquals("SESSDATA=persisted-test; bili_jct=csrf-test",
                store.load().orElseThrow(), "session file round-trip");
            String disk = Files.readString(path);
            assertTrue(!disk.contains("unknown=drop"), "non-whitelisted Cookie persisted");
            store.clear();
            assertTrue(!Files.exists(path), "session file should be removed on logout");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    private static void assertQrState(int code, BilibiliAccountData.QrState expected) {
        BilibiliAccountData.QrPoll result = BilibiliAccountData.parseQrPoll(
            "{\"code\":0,\"data\":{\"code\":" + code + ",\"message\":\"state\",\"url\":\"\"}}");
        assertEquals(expected, result.state(), "QR state " + code);
    }

    private static void expectFailure(ThrowingRunnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (AssertionError error) {
            throw error;
        } catch (Exception expected) {
            // Expected.
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
