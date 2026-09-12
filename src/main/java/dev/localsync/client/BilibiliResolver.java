package dev.localsync.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.localsync.LocalSyncMod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
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
    private static final int MAX_PROXY_HEADER_BYTES = 32 * 1024;
    private static final Map<String, ProxySource> SOURCES = new ConcurrentHashMap<>();
    private static volatile MediaProxy proxy;

    private BilibiliResolver() {
    }

    record SearchResult(String bvid, String title, String author,
                        String duration, long playCount, String coverUrl) {
        String pageUrl() {
            return "https://www.bilibili.com/video/" + bvid;
        }
    }

    static List<SearchResult> searchVideos(String keyword, int page) throws Exception {
        return parseSearchResults(requestJson(buildSearchUri(keyword, page)));
    }

    static URI buildSearchUri(String keyword, int page) {
        String query = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return URI.create("https://api.bilibili.com/x/web-interface/wbi/search/type"
            + "?search_type=video&order=totalrank&keyword=" + query
            + "&page=" + Math.max(1, page));
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
                readPlayCount(value),
                value.has("pic") ? value.get("pic").getAsString() : ""));
        }
        return List.copyOf(results);
    }

    private static long readPlayCount(JsonObject value) {
        if (!value.has("play") || value.get("play").isJsonNull()) {
            return 0L;
        }
        try {
            return Math.max(0L, value.get("play").getAsLong());
        } catch (RuntimeException ignored) {
            return 0L;
        }
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
        if (BilibiliLiveResolver.isLiveInput(input)) {
            return BilibiliLiveResolver.resolve(input);
        }
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
        String local = createProxyUri(direct).toString();
        LocalSyncMod.LOGGER.info("Resolved Bilibili {} page {} through local media proxy", bvid, page);
        return local;
    }

    private static URI expandShortLink(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12)).header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<Void> response = BilibiliHttp.discard(request, true);
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
        URI api = buildPlayUrlUri(bvid, cid);
        JsonObject root = requestJson(api);
        JsonObject data = root.getAsJsonObject("data");
        JsonArray durl = data == null ? null : data.getAsJsonArray("durl");
        if (durl == null || durl.isEmpty()) {
            throw new IOException("Bilibili 没有返回可播放地址");
        }
        return URI.create(durl.get(0).getAsJsonObject().get("url").getAsString());
    }

    static URI buildPlayUrlUri(String bvid, long cid) {
        return URI.create("https://api.bilibili.com/x/player/wbi/playurl?bvid=" + bvid
            + "&cid=" + cid + "&qn=127&otype=json&platform=html5"
            + "&high_quality=1&fourk=1");
    }

    private static JsonObject requestJson(URI uri) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com");
        BilibiliAccountService.instance().applyAuthentication(builder);
        HttpRequest request = builder.GET().build();
        // Authenticated API requests stay on the exact Bilibili endpoint.
        HttpResponse<String> response = BilibiliHttp.sendString(request, false);
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

    static URI createProxyUri(URI source) throws IOException {
        return createProxyUri(source, "https://www.bilibili.com/");
    }

    static URI createProxyUri(URI source, String referer) throws IOException {
        if (source == null || !source.isAbsolute()) {
            throw new IllegalArgumentException("媒体源地址无效");
        }
        MediaProxy server = ensureProxy();
        String id = UUID.randomUUID().toString();
        SOURCES.put(id, new ProxySource(source,
            referer == null || referer.isBlank() ? "https://www.bilibili.com/" : referer));
        return URI.create("http://127.0.0.1:" + server.port() + "/media/" + id);
    }

    private static synchronized MediaProxy ensureProxy() throws IOException {
        if (proxy != null) {
            return proxy;
        }
        MediaProxy created = new MediaProxy();
        proxy = created;
        return created;
    }

    private static void proxyMedia(Socket socket) {
        try (socket) {
            socket.setSoTimeout(30_000);
            OutputStream output = socket.getOutputStream();
            ProxyRequest request;
            try {
                request = readProxyRequest(socket.getInputStream());
            } catch (IOException error) {
                writeSimpleResponse(output, 400);
                return;
            }
            if (!(request.method().equals("GET") || request.method().equals("HEAD"))) {
                writeSimpleResponse(output, 405);
                return;
            }
            String path = request.path();
            if (!path.startsWith("/media/")) {
                writeSimpleResponse(output, 404);
                return;
            }
            String id = path.substring("/media/".length());
            ProxySource source = SOURCES.get(id);
            if (source == null) {
                writeSimpleResponse(output, 404);
                return;
            }

            boolean responseStarted = false;
            try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(source.uri())
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Referer", source.referer())
                .header("Origin", "https://www.bilibili.com");
            String range = request.range();
            if (range != null && !range.isBlank()) {
                builder.header("Range", range);
            }
            builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
            BilibiliHttp.StreamingResponse upstream =
                BilibiliHttp.openStream(builder.build(), true);
            try (upstream) {
                writeUpstreamResponse(output, upstream);
                responseStarted = true;
                if (!request.method().equals("HEAD")) {
                    upstream.body().transferTo(output);
                }
                output.flush();
            }
            } catch (Exception error) {
                if (error instanceof SocketException) {
                    LocalSyncMod.LOGGER.debug("Bilibili proxy client closed its media probe", error);
                } else {
                    LocalSyncMod.LOGGER.warn("Bilibili proxy request failed", error);
                }
                if (!responseStarted) {
                    writeSimpleResponse(output, 502);
                }
            }
        } catch (IOException error) {
            LocalSyncMod.LOGGER.debug("Bilibili proxy client disconnected", error);
        }
    }

    private static ProxyRequest readProxyRequest(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(1024);
        int matched = 0;
        while (header.size() < MAX_PROXY_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new IOException("连接在请求头结束前关闭");
            header.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) break;
        }
        if (matched != 4) throw new IOException("代理请求头过大");
        String[] lines = header.toString(StandardCharsets.ISO_8859_1).split("\\r\\n");
        if (lines.length == 0) throw new IOException("代理请求为空");
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length != 3) throw new IOException("代理请求行无效");
        String method = requestLine[0].toUpperCase(Locale.ROOT);
        String target = requestLine[1];
        String path;
        try {
            URI targetUri = URI.create(target);
            int query = target.indexOf('?');
            path = targetUri.isAbsolute() ? targetUri.getPath()
                : query >= 0 ? target.substring(0, query) : target;
        } catch (RuntimeException error) {
            throw new IOException("代理请求地址无效", error);
        }
        String range = null;
        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf(':');
            if (separator > 0 && lines[index].substring(0, separator)
                    .trim().equalsIgnoreCase("Range")) {
                range = lines[index].substring(separator + 1).trim();
            }
        }
        return new ProxyRequest(method, path, range);
    }

    private static void writeUpstreamResponse(OutputStream output,
                                              BilibiliHttp.StreamingResponse upstream)
            throws IOException {
        StringBuilder header = new StringBuilder(256)
            .append("HTTP/1.1 ").append(upstream.statusCode()).append(' ')
            .append(reason(upstream.statusCode())).append("\r\n");
        appendHeader(header, upstream, "Content-Type");
        appendHeader(header, upstream, "Content-Range");
        appendHeader(header, upstream, "Accept-Ranges");
        appendHeader(header, upstream, "Content-Length");
        header.append("Connection: close\r\n\r\n");
        output.write(header.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static void appendHeader(StringBuilder target,
                                     BilibiliHttp.StreamingResponse source,
                                     String name) {
        source.headers().firstValue(name).ifPresent(value -> target.append(name)
            .append(": ").append(value.replace("\r", "").replace("\n", ""))
            .append("\r\n"));
    }

    private static void writeSimpleResponse(OutputStream output, int status) throws IOException {
        String response = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 206 -> "Partial Content";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 416 -> "Range Not Satisfiable";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            default -> "Upstream Response";
        };
    }

    private record ProxyRequest(String method, String path, String range) {
    }

    private record ProxySource(URI uri, String referer) {
    }

    private static final class MediaProxy {
        private final ServerSocket server;
        private final java.util.concurrent.ExecutorService workers;

        private MediaProxy() throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32);
            workers = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "localsync-bilibili-proxy-worker");
                thread.setDaemon(true);
                return thread;
            });
            Thread acceptor = new Thread(this::acceptLoop, "localsync-bilibili-proxy-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    try {
                        workers.execute(() -> proxyMedia(client));
                    } catch (RuntimeException error) {
                        client.close();
                        throw error;
                    }
                } catch (SocketException error) {
                    if (!server.isClosed()) {
                        LocalSyncMod.LOGGER.warn("Bilibili proxy listener failed", error);
                    }
                    return;
                } catch (Exception error) {
                    LocalSyncMod.LOGGER.warn("Bilibili proxy could not accept a client", error);
                }
            }
        }
    }
}
