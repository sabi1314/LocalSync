package dev.localsync.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.localsync.LocalSyncMod;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BilibiliResolver {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/131.0 Safari/537.36";
    private static final Pattern BVID = Pattern.compile("(BV[0-9A-Za-z]+)");
    private static final Pattern PAGE = Pattern.compile("(?:[?&])p=(\\d+)");
    private static final Map<String, URI> SOURCES = new ConcurrentHashMap<>();
    private static volatile HttpServer proxy;

    private BilibiliResolver() {
    }

    record SearchResult(String bvid, String title, String author,
                        String duration, long playCount, String coverUrl) {
        String pageUrl() {
            return "https://www.bilibili.com/video/" + bvid;
        }
    }

    static List<SearchResult> searchVideos(String keyword, int page) throws Exception {
        String query = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        URI api = URI.create("https://api.bilibili.com/x/web-interface/wbi/search/type"
            + "?search_type=video&keyword=" + query + "&page=" + Math.max(1, page));
        return parseSearchResults(requestJson(api));
    }

    static List<SearchResult> parseSearchResults(JsonObject root) {
        JsonObject data = root.getAsJsonObject("data");
        JsonArray values = data == null ? null : data.getAsJsonArray("result");
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            JsonObject value = values.get(index).getAsJsonObject();
            if (!value.has("bvid") || !value.has("title")) {
                continue;
            }
            results.add(new SearchResult(
                value.get("bvid").getAsString(),
                cleanSearchText(value.get("title").getAsString()),
                value.has("author") ? cleanSearchText(value.get("author").getAsString()) : "",
                value.has("duration") ? value.get("duration").getAsString() : "--:--",
                value.has("play") ? Math.max(0L, value.get("play").getAsLong()) : 0L,
                value.has("pic") ? value.get("pic").getAsString() : ""));
        }
        return List.copyOf(results);
    }

    private static String cleanSearchText(String value) {
        return value.replaceAll("<[^>]+>", "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">");
    }

    static String resolveIfNeeded(String input) throws Exception {
        URI uri = URI.create(input);
        String host = uri.getHost();
        if (host == null) {
            return input;
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (!(lowerHost.equals("b23.tv") || lowerHost.endsWith(".b23.tv")
                || lowerHost.equals("bilibili.com") || lowerHost.endsWith(".bilibili.com"))
                || uri.getPath().startsWith("/live")) {
            return input;
        }
        URI expanded = lowerHost.endsWith("b23.tv") ? expandShortLink(uri) : uri;
        String bvid = extractBvid(expanded);
        int page = extractPage(expanded);
        long cid = requestCid(bvid, page);
        URI direct = requestDirectMedia(bvid, cid);
        String id = UUID.randomUUID().toString();
        SOURCES.put(id, direct);
        HttpServer server = ensureProxy();
        String local = "http://127.0.0.1:" + server.getAddress().getPort() + "/media/" + id;
        LocalSyncMod.LOGGER.info("Resolved Bilibili {} page {} through local media proxy", bvid, page);
        return local;
    }

    private static URI expandShortLink(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12)).header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<Void> response = client().send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 400) {
            throw new IOException("Bilibili 短链展开失败: HTTP " + response.statusCode());
        }
        return response.uri();
    }

    static String extractBvid(URI uri) throws IOException {
        Matcher matcher = BVID.matcher(uri.toString());
        if (!matcher.find()) {
            throw new IOException("链接中没有找到 BV 号");
        }
        return matcher.group(1);
    }

    static int extractPage(URI uri) {
        Matcher matcher = PAGE.matcher(uri.toString());
        if (!matcher.find()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static long requestCid(String bvid, int page) throws Exception {
        URI api = URI.create("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
        JsonObject root = requestJson(api);
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) {
            throw new IOException("Bilibili 视频信息为空");
        }
        JsonArray pages = data.getAsJsonArray("pages");
        if (pages != null && !pages.isEmpty()) {
            int index = Math.min(page, pages.size()) - 1;
            return pages.get(index).getAsJsonObject().get("cid").getAsLong();
        }
        return data.get("cid").getAsLong();
    }

    private static URI requestDirectMedia(String bvid, long cid) throws Exception {
        URI api = URI.create("https://api.bilibili.com/x/player/wbi/playurl?bvid=" + bvid
            + "&cid=" + cid + "&qn=116&otype=json&platform=html5&high_quality=1");
        JsonObject root = requestJson(api);
        JsonObject data = root.getAsJsonObject("data");
        JsonArray durl = data == null ? null : data.getAsJsonArray("durl");
        if (durl == null || durl.isEmpty()) {
            throw new IOException("Bilibili 没有返回可播放地址");
        }
        return URI.create(durl.get(0).getAsJsonObject().get("url").getAsString());
    }

    private static JsonObject requestJson(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com").GET().build();
        HttpResponse<String> response = client().send(request,
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili API: HTTP " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("code") || root.get("code").getAsInt() != 0) {
            String message = root.has("message") ? root.get("message").getAsString() : "未知错误";
            throw new IOException("Bilibili API: " + message);
        }
        return root;
    }

    private static synchronized HttpServer ensureProxy() throws IOException {
        if (proxy != null) {
            return proxy;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(
            InetAddress.getLoopbackAddress(), 0), 0);
        created.createContext("/media/", BilibiliResolver::proxyMedia);
        created.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "localsync-bilibili-proxy");
            thread.setDaemon(true);
            return thread;
        }));
        created.start();
        proxy = created;
        return created;
    }

    private static void proxyMedia(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestURI().getPath().substring("/media/".length());
        URI source = SOURCES.get(id);
        if (source == null || !(exchange.getRequestMethod().equals("GET")
                || exchange.getRequestMethod().equals("HEAD"))) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(source)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com");
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && !range.isBlank()) {
                builder.header("Range", range);
            }
            builder.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.noBody());
            HttpResponse<InputStream> upstream = client().send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
            copyHeader(upstream, exchange, "Content-Type");
            copyHeader(upstream, exchange, "Content-Range");
            copyHeader(upstream, exchange, "Accept-Ranges");
            long length = upstream.headers().firstValueAsLong("Content-Length").orElse(0L);
            boolean head = exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(upstream.statusCode(), head ? -1L : length);
            try (InputStream body = upstream.body()) {
                if (!head) {
                    body.transferTo(exchange.getResponseBody());
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(503, -1);
        } catch (Exception error) {
            LocalSyncMod.LOGGER.warn("Bilibili proxy request failed", error);
            exchange.sendResponseHeaders(502, -1);
        } finally {
            exchange.close();
        }
    }

    private static void copyHeader(HttpResponse<?> source, HttpExchange target, String name) {
        source.headers().firstValue(name).ifPresent(value ->
            target.getResponseHeaders().set(name, value));
    }

    private static HttpClient client() {
        return ClientHolder.CLIENT;
    }

    private static final class ClientHolder {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(12))
            .build();
    }
}
