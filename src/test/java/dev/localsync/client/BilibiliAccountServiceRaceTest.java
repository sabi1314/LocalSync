package dev.localsync.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSession;

public final class BilibiliAccountServiceRaceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String QR_KEY = "0123456789abcdef0123456789abcdef";

    private BilibiliAccountServiceRaceTest() {
    }

    public static void main(String[] args) throws Exception {
        testLogoutRejectsInFlightCookieLogin();
        testNewLoginWinsAgainstOlderLogin();
        testLogoutRejectsInFlightQrPoll();
        testOldQrPollCannotRestoreOrReplaceSession();
        System.out.println("PASS BilibiliAccountRace: logout, superseding login, stale QR poll");
    }

    private static void testLogoutRejectsInFlightCookieLogin() throws Exception {
        try (TestContext context = new TestContext()) {
            ResponsePlan oldProfile = ResponsePlan.blocking(profileJson(101L, "Old account"));
            context.requester.profile("logout-old", oldProfile);

            CompletableFuture<BilibiliAccountData.Profile> oldLogin =
                context.service.loginWithCookie("SESSDATA=logout-old");
            oldProfile.awaitStarted();
            assertTrue(context.service.logout(), "logout should clear the local store");
            oldProfile.release();

            expectFutureFailure(oldLogin, "in-flight login completed after logout");
            assertTrue(!context.service.hasSession(), "logout session was restored");
            assertTrue(context.service.cachedProfile().isEmpty(),
                "logout profile was restored");
            assertTrue(context.store.load().isEmpty(), "logout Cookie was persisted again");
        }
    }

    private static void testNewLoginWinsAgainstOlderLogin() throws Exception {
        try (TestContext context = new TestContext()) {
            ResponsePlan oldProfile = ResponsePlan.blocking(profileJson(201L, "Old account"));
            context.requester.profile("superseded-old", oldProfile);
            context.requester.profile("winning-new",
                ResponsePlan.immediate(profileJson(202L, "New account")));

            CompletableFuture<BilibiliAccountData.Profile> oldLogin =
                context.service.loginWithCookie("SESSDATA=superseded-old");
            oldProfile.awaitStarted();
            BilibiliAccountData.Profile winner = context.service
                .loginWithCookie("SESSDATA=winning-new").get(TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS);
            oldProfile.release();

            expectFutureFailure(oldLogin, "older login overwrote the newer login");
            assertEquals(202L, winner.mid(), "new login result");
            assertEquals(202L, context.service.cachedProfile().orElseThrow().mid(),
                "cached profile after competing logins");
            assertEquals("SESSDATA=winning-new", authenticatedCookie(context.service),
                "active Cookie after competing logins");
            assertEquals("SESSDATA=winning-new", context.store.load().orElseThrow(),
                "persisted Cookie after competing logins");
        }
    }

    private static void testOldQrPollCannotRestoreOrReplaceSession() throws Exception {
        try (TestContext context = new TestContext()) {
            context.requester.profile("winning-cookie",
                ResponsePlan.immediate(profileJson(302L, "Cookie account")));
            context.requester.profile("qr-session",
                ResponsePlan.immediate(profileJson(303L, "QR account")));
            ResponsePlan qrPoll = ResponsePlan.blocking(qrPollJson(), List.of(
                "SESSDATA=qr-session; Path=/; Domain=.bilibili.com; HttpOnly",
                "bili_jct=qr-csrf; Path=/; Domain=.bilibili.com"
            ));
            context.requester.qrPoll(qrPoll);

            BilibiliAccountData.QrLogin qrLogin = context.service.requestQrLogin()
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("source=main-fe-header", context.requester.qrGenerateQuery(),
                "QR generation source marker");
            CompletableFuture<BilibiliAccountData.QrPoll> oldPoll =
                context.service.pollQrLogin(qrLogin.key());
            qrPoll.awaitStarted();

            context.service.loginWithCookie("SESSDATA=winning-cookie")
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            int requestsBeforeStaleRetry = context.requester.qrPollRequests();
            expectFutureFailure(context.service.pollQrLogin(qrLogin.key()),
                "old QR key was accepted after a newer login");
            assertEquals(requestsBeforeStaleRetry, context.requester.qrPollRequests(),
                "stale QR key should be rejected before HTTP");

            qrPoll.release();
            expectFutureFailure(oldPoll, "in-flight QR poll replaced the newer login");
            assertEquals(302L, context.service.cachedProfile().orElseThrow().mid(),
                "cached profile after stale QR poll");
            assertEquals("SESSDATA=winning-cookie", authenticatedCookie(context.service),
                "active Cookie after stale QR poll");
            assertEquals("SESSDATA=winning-cookie", context.store.load().orElseThrow(),
                "persisted Cookie after stale QR poll");
        }
    }

    private static void testLogoutRejectsInFlightQrPoll() throws Exception {
        try (TestContext context = new TestContext()) {
            context.requester.profile("qr-session",
                ResponsePlan.immediate(profileJson(301L, "QR account")));
            ResponsePlan qrPoll = ResponsePlan.blocking(qrPollJson(), List.of(
                "SESSDATA=qr-session; Path=/; Domain=.bilibili.com; HttpOnly",
                "bili_jct=qr-csrf; Path=/; Domain=.bilibili.com"
            ));
            context.requester.qrPoll(qrPoll);

            BilibiliAccountData.QrLogin qrLogin = context.service.requestQrLogin()
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            CompletableFuture<BilibiliAccountData.QrPoll> oldPoll =
                context.service.pollQrLogin(qrLogin.key());
            qrPoll.awaitStarted();
            assertTrue(context.service.logout(), "logout should clear the local store");
            qrPoll.release();

            expectFutureFailure(oldPoll, "in-flight QR poll completed after logout");
            assertTrue(!context.service.hasSession(), "QR session was restored after logout");
            assertTrue(context.service.cachedProfile().isEmpty(),
                "QR profile was restored after logout");
            assertTrue(context.store.load().isEmpty(),
                "QR Cookie was persisted again after logout");
        }
    }

    private static String authenticatedCookie(BilibiliAccountService service) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://example.test/"));
        service.applyAuthentication(request);
        return request.build().headers().firstValue("Cookie").orElse("");
    }

    private static String profileJson(long mid, String name) {
        return "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":" + mid
            + ",\"uname\":\"" + name + "\",\"face\":\"\",\"vipStatus\":0,"
            + "\"level_info\":{\"current_level\":6}}}";
    }

    private static String qrPollJson() {
        return "{\"code\":0,\"data\":{\"code\":0,\"message\":\"\","
            + "\"url\":\"https://passport.bilibili.com/login/success?DedeUserID=303\"}}";
    }

    private static void expectFutureFailure(CompletableFuture<?> future, String message)
            throws Exception {
        try {
            future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            throw new AssertionError(message);
        } catch (ExecutionException expected) {
            // Expected cancellation through the login epoch guard.
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

    private static final class TestContext implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("localsync-account-race-");
        private final BilibiliSessionStore store =
            new BilibiliSessionStore(directory.resolve("account.json"));
        private final ControlledRequester requester = new ControlledRequester();
        private final ExecutorService executor = Executors.newFixedThreadPool(3);
        private final BilibiliAccountService service =
            new BilibiliAccountService(store, requester, executor);

        private TestContext() throws IOException {
        }

        @Override
        public void close() throws Exception {
            executor.shutdownNow();
            executor.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Files.deleteIfExists(store.path());
            Files.deleteIfExists(directory);
        }
    }

    private static final class ControlledRequester implements BilibiliAccountService.Requester {
        private final Map<String, ResponsePlan> profiles = new ConcurrentHashMap<>();
        private final AtomicInteger qrPollRequests = new AtomicInteger();
        private volatile String qrGenerateQuery = "";
        private volatile ResponsePlan qrPoll;

        void profile(String cookieMarker, ResponsePlan response) {
            profiles.put(cookieMarker, response);
        }

        void qrPoll(ResponsePlan response) {
            qrPoll = response;
        }

        int qrPollRequests() {
            return qrPollRequests.get();
        }

        String qrGenerateQuery() {
            return qrGenerateQuery;
        }

        @Override
        public HttpResponse<String> get(URI uri, String cookies)
                throws IOException, InterruptedException {
            String path = uri.getPath();
            if (path.endsWith("/qrcode/generate")) {
                qrGenerateQuery = uri.getQuery();
                return response(uri, "{\"code\":0,\"data\":{"
                    + "\"url\":\"https://account.bilibili.com/h5/account-h5/auth/scan-web\","
                    + "\"qrcode_key\":\"" + QR_KEY + "\"}}", List.of());
            }
            if (path.endsWith("/qrcode/poll")) {
                qrPollRequests.incrementAndGet();
                ResponsePlan available = qrPoll;
                if (available == null) throw new IOException("Unexpected QR poll");
                return available.respond(uri);
            }
            if (path.endsWith("/nav")) {
                for (Map.Entry<String, ResponsePlan> entry : profiles.entrySet()) {
                    if (cookies.contains(entry.getKey())) return entry.getValue().respond(uri);
                }
            }
            throw new IOException("Unexpected test request: " + uri);
        }
    }

    private static final class ResponsePlan {
        private final String body;
        private final List<String> setCookies;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release;

        private ResponsePlan(String body, List<String> setCookies, boolean blocked) {
            this.body = body;
            this.setCookies = setCookies;
            release = new CountDownLatch(blocked ? 1 : 0);
        }

        static ResponsePlan immediate(String body) {
            return new ResponsePlan(body, List.of(), false);
        }

        static ResponsePlan blocking(String body) {
            return new ResponsePlan(body, List.of(), true);
        }

        static ResponsePlan blocking(String body, List<String> setCookies) {
            return new ResponsePlan(body, setCookies, true);
        }

        HttpResponse<String> respond(URI uri) throws IOException, InterruptedException {
            started.countDown();
            if (!release.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out waiting for the test response release");
            }
            return response(uri, body, setCookies);
        }

        void awaitStarted() throws InterruptedException {
            if (!started.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Timed out waiting for the controlled request");
            }
        }

        void release() {
            release.countDown();
        }
    }

    private static HttpResponse<String> response(URI uri, String body,
                                                  List<String> setCookies) {
        HttpHeaders headers = HttpHeaders.of(
            setCookies.isEmpty() ? Map.of() : Map.of("set-cookie", setCookies),
            (name, value) -> true);
        return new FakeResponse(uri, headers, body);
    }

    private record FakeResponse(URI uri, HttpHeaders headers, String body)
            implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(uri).GET().build();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
