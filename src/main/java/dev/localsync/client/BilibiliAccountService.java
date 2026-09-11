package dev.localsync.client;

import dev.localsync.LocalSyncMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BilibiliAccountService {
    private static final URI QR_GENERATE = URI.create(
        "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
            + "?source=main-fe-header");
    private static final URI NAV = URI.create(
        "https://api.bilibili.com/x/web-interface/nav");
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/131.0 Safari/537.36";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "localsync-bilibili-account");
        thread.setDaemon(true);
        return thread;
    });
    private final Requester requester;
    private final BilibiliSessionStore store;
    private final Executor executor;
    private volatile String cookieHeader;
    private volatile BilibiliAccountData.Profile cachedProfile;
    private long loginEpoch;
    private String activeQrKey = "";
    private long activeQrEpoch = -1L;

    private BilibiliAccountService() {
        this(defaultStore(), defaultRequester(), EXECUTOR);
    }

    BilibiliAccountService(BilibiliSessionStore store, Requester requester,
                           Executor executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.requester = Objects.requireNonNull(requester, "requester");
        this.executor = Objects.requireNonNull(executor, "executor");
        cookieHeader = loadStoredCookie();
    }

    private static BilibiliSessionStore defaultStore() {
        return new BilibiliSessionStore(FabricLoader.getInstance().getConfigDir()
            .resolve("localsync-bilibili-account.json"));
    }

    private static Requester defaultRequester() {
        return BilibiliAccountService::get;
    }

    public static BilibiliAccountService instance() {
        return InstanceHolder.INSTANCE;
    }

    public boolean hasSession() {
        return !cookieHeader.isBlank();
    }

    public Optional<BilibiliAccountData.Profile> cachedProfile() {
        return Optional.ofNullable(cachedProfile);
    }

    public CompletableFuture<BilibiliAccountData.QrLogin> requestQrLogin() {
        long expectedEpoch = beginLoginAttempt();
        return async("生成登录二维码", () -> {
            HttpResponse<String> response = requester.get(QR_GENERATE, "");
            BilibiliAccountData.QrLogin login =
                BilibiliAccountData.parseQrLogin(response.body());
            activateQrLogin(login.key(), expectedEpoch);
            return login;
        });
    }

    public CompletableFuture<BilibiliAccountData.QrPoll> pollQrLogin(String key) {
        return pollQrLogin(key, true);
    }

    public CompletableFuture<BilibiliAccountData.QrPoll> pollQrLogin(String key,
                                                                     boolean remember) {
        if (key == null || !key.matches("[0-9A-Za-z_-]{16,128}")) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("二维码登录标识无效"));
        }
        final long expectedEpoch;
        try {
            expectedEpoch = qrEpoch(key);
        } catch (IOException error) {
            return CompletableFuture.failedFuture(error);
        }
        return async("轮询二维码登录", () -> {
            String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8);
            URI uri = URI.create("https://passport.bilibili.com/"
                + "x/passport-login/web/qrcode/poll?qrcode_key=" + encoded);
            HttpResponse<String> response = requester.get(uri, "");
            BilibiliAccountData.QrPoll result = BilibiliAccountData.parseQrPoll(response.body());
            if (result.state() == BilibiliAccountData.QrState.CONFIRMED) {
                String cookies = BilibiliAccountData.cookiesFromLogin(
                    response.headers().allValues("set-cookie"), result.successUrl());
                if (!BilibiliAccountData.hasAuthenticatedCookie(cookies)) {
                    throw new IOException("Bilibili 登录成功，但未返回有效会话");
                }
                BilibiliAccountData.Profile profile = fetchProfile(cookies);
                installSession(cookies, profile, remember, expectedEpoch);
            }
            return result;
        });
    }

    public CompletableFuture<BilibiliAccountData.Profile> loginWithCookie(String rawCookie) {
        return loginWithCookie(rawCookie, true);
    }

    public CompletableFuture<BilibiliAccountData.Profile> loginWithCookie(String rawCookie,
                                                                           boolean remember) {
        long expectedEpoch = beginLoginAttempt();
        final String normalized;
        try {
            normalized = BilibiliAccountData.normalizeCookieHeader(rawCookie);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        if (!BilibiliAccountData.hasAuthenticatedCookie(normalized)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Cookie 中缺少 SESSDATA"));
        }
        return async("验证 Cookie", () -> {
            BilibiliAccountData.Profile profile = fetchProfile(normalized);
            installSession(normalized, profile, remember, expectedEpoch);
            return profile;
        });
    }

    public CompletableFuture<BilibiliAccountData.Profile> profile() {
        BilibiliAccountData.Profile available = cachedProfile;
        if (available != null) return CompletableFuture.completedFuture(available);
        return async("读取账户信息", () -> {
            String cookies = requireSession();
            BilibiliAccountData.Profile loaded = fetchProfile(cookies);
            if (cookies.equals(cookieHeader)) cachedProfile = loaded;
            return loaded;
        });
    }

    public CompletableFuture<List<BilibiliAccountData.FavoriteFolder>> favoriteFolders() {
        return async("读取收藏夹", () -> {
            String cookies = requireSession();
            BilibiliAccountData.Profile profile = cachedProfile;
            if (profile == null) {
                profile = fetchProfile(cookies);
                if (cookies.equals(cookieHeader)) cachedProfile = profile;
            }
            URI uri = URI.create("https://api.bilibili.com/x/v3/fav/folder/created/list-all"
                + "?up_mid=" + profile.mid() + "&type=2");
            return BilibiliAccountData.parseFavoriteFolders(
                requester.get(uri, cookies).body());
        });
    }

    public CompletableFuture<BilibiliAccountData.FavoritePage> favoriteVideos(long folderId,
                                                                                int page) {
        return favoriteVideos(folderId, page, 20);
    }

    public CompletableFuture<BilibiliAccountData.FavoritePage> favoriteVideos(long folderId,
                                                                                int page,
                                                                                int pageSize) {
        if (folderId <= 0L) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("收藏夹 ID 无效"));
        }
        int requestedPage = Math.max(1, page);
        int requestedSize = Math.max(1, Math.min(20, pageSize));
        return async("读取收藏视频", () -> {
            String cookies = requireSession();
            URI uri = URI.create("https://api.bilibili.com/x/v3/fav/resource/list"
                + "?media_id=" + folderId
                + "&pn=" + requestedPage
                + "&ps=" + requestedSize
                + "&order=mtime&type=0&tid=0&platform=web");
            return BilibiliAccountData.parseFavoritePage(
                requester.get(uri, cookies).body(), requestedPage);
        });
    }

    public synchronized boolean logout() {
        loginEpoch++;
        clearActiveQrLogin();
        cookieHeader = "";
        cachedProfile = null;
        try {
            store.clear();
            return true;
        } catch (IOException error) {
            LocalSyncMod.LOGGER.warn("Could not remove the local Bilibili session file");
            return false;
        }
    }

    void applyAuthentication(HttpRequest.Builder request) {
        String available = cookieHeader;
        if (!available.isBlank()) request.header("Cookie", available);
    }

    private BilibiliAccountData.Profile fetchProfile(String cookies) throws Exception {
        BilibiliAccountData.Profile profile = BilibiliAccountData.parseProfile(
            requester.get(NAV, cookies).body());
        if (profile.mid() <= 0L || profile.name().isBlank()) {
            throw new IOException("Bilibili 账户信息不完整");
        }
        return profile;
    }

    private static HttpResponse<String> get(URI uri, String cookies)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .GET();
        if (cookies != null && !cookies.isBlank()) request.header("Cookie", cookies);
        // Account cookies must never be forwarded to a redirected host.
        HttpResponse<String> response = BilibiliHttp.sendString(request.build(), false);
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili HTTP " + response.statusCode());
        }
        return response;
    }

    private synchronized void installSession(String cookies,
                                             BilibiliAccountData.Profile profile,
                                             boolean remember,
                                             long expectedEpoch) throws IOException {
        if (loginEpoch != expectedEpoch) {
            throw new IOException("登录请求已被新的账户操作取消");
        }
        if (remember) {
            store.save(cookies);
        } else {
            store.clear();
        }
        cookieHeader = cookies;
        cachedProfile = profile;
        loginEpoch++;
        clearActiveQrLogin();
    }

    private synchronized long beginLoginAttempt() {
        loginEpoch++;
        clearActiveQrLogin();
        return loginEpoch;
    }

    private synchronized void activateQrLogin(String key, long expectedEpoch)
            throws IOException {
        if (loginEpoch != expectedEpoch) {
            throw new IOException("二维码登录已被新的账户操作取消");
        }
        activeQrKey = key;
        activeQrEpoch = expectedEpoch;
    }

    private synchronized long qrEpoch(String key) throws IOException {
        if (activeQrEpoch != loginEpoch || !activeQrKey.equals(key)) {
            throw new IOException("二维码登录已失效");
        }
        return activeQrEpoch;
    }

    private void clearActiveQrLogin() {
        activeQrKey = "";
        activeQrEpoch = -1L;
    }

    private String requireSession() throws IOException {
        String available = cookieHeader;
        if (available.isBlank()) throw new IOException("请先登录 Bilibili");
        return available;
    }

    private String loadStoredCookie() {
        try {
            return store.load().orElse("");
        } catch (IOException error) {
            LocalSyncMod.LOGGER.warn("Could not load the local Bilibili session file");
            return "";
        }
    }

    private <T> CompletableFuture<T> async(String operation,
                                           ThrowingSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new IOException(operation + "已取消"));
            } catch (Throwable error) {
                throw new CompletionException(sanitizedFailure(operation, error));
            }
        }, executor);
    }

    private static IOException sanitizedFailure(String operation, Throwable error) {
        String detail = safeDetail(error);
        return new IOException(operation + "失败: " + detail);
    }

    private static String safeDetail(Throwable error) {
        String message = error.getMessage();
        if (message != null && (message.startsWith("Bilibili API ")
                || message.startsWith("Bilibili HTTP ")
                || message.startsWith("Bilibili 登录")
                || message.startsWith("Bilibili 账户")
                || message.startsWith("登录请求")
                || message.startsWith("二维码登录")
                || message.startsWith("请先登录 Bilibili")
                || message.startsWith("没有可保存")
                || message.startsWith("账户配置")
                || message.startsWith("拒绝覆盖"))) {
            return message;
        }
        return error.getClass().getSimpleName();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface Requester {
        HttpResponse<String> get(URI uri, String cookies)
            throws IOException, InterruptedException;
    }

    private static final class InstanceHolder {
        private static final BilibiliAccountService INSTANCE =
            new BilibiliAccountService();
    }
}
