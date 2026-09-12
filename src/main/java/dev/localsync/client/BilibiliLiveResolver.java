package dev.localsync.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class BilibiliLiveResolver {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/131.0 Safari/537.36";

    record LiveSearchResult(long roomId, String title, String owner,
                            long online, String coverUrl) {
        String pageUrl() {
            return "https://live.bilibili.com/" + roomId;
        }
    }

    record LiveStream(long roomId, String title, int quality,
                      String codec, URI source) {
    }

    record StreamCandidate(int quality, int codecPriority, String codec,
                           URI source) {
    }

    private BilibiliLiveResolver() {
    }

    static boolean isLiveInput(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String trimmed = input.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return true;
        }
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            return host != null && (host.equalsIgnoreCase("live.bilibili.com")
                || host.toLowerCase(Locale.ROOT).endsWith(".live.bilibili.com"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String canonicalPageUrl(String input) throws IOException {
        return "https://live.bilibili.com/" + extractRoomId(input);
    }

    static long extractRoomId(String input) throws IOException {
        if (input == null || input.isBlank()) {
            throw new IOException("请输入直播房间号或链接");
        }
        String trimmed = input.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return positiveLong(trimmed);
        }
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("live.bilibili.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".live.bilibili.com"))) {
                throw new IOException("这不是 Bilibili 直播链接");
            }
            for (String part : uri.getPath().split("/")) {
                if (!part.isBlank() && part.chars().allMatch(Character::isDigit)) {
                    return positiveLong(part);
                }
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("直播链接格式无效", error);
        }
        throw new IOException("链接中没有找到直播房间号");
    }

    static List<LiveSearchResult> search(String keyword, int page) throws Exception {
        return search(keyword, page, true);
    }

    static List<LiveSearchResult> search(String keyword, int page,
                                         boolean authenticate) throws Exception {
        return parseSearchResults(requestJson(buildSearchUri(keyword, page),
            "https://search.bilibili.com/live", authenticate));
    }

    static URI buildSearchUri(String keyword, int page) {
        String query = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return URI.create("https://api.bilibili.com/x/web-interface/wbi/search/type"
            + "?search_type=live_room&order=online&keyword=" + query
            + "&page=" + Math.max(1, page));
    }

    static List<LiveSearchResult> parseSearchResults(JsonObject root) {
        JsonObject data = object(root, "data");
        JsonArray values = data == null ? null : array(data, "result");
        if (values == null) {
            return List.of();
        }
        List<LiveSearchResult> results = new ArrayList<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject value = element.getAsJsonObject();
            long roomId = longValue(value, "roomid", 0L);
            int liveStatus = integer(value, "live_status", 1);
            if (roomId <= 0L || liveStatus != 1) {
                continue;
            }
            String cover = string(value, "user_cover");
            if (cover.isBlank()) {
                cover = string(value, "pic");
            }
            results.add(new LiveSearchResult(roomId,
                clean(string(value, "title")), clean(string(value, "uname")),
                longValue(value, "online", 0L), absoluteImage(cover)));
        }
        return List.copyOf(results);
    }

    static String resolve(String input) throws Exception {
        LiveStream stream = resolveStream(input);
        return BilibiliResolver.createProxyUri(stream.source(),
            "https://live.bilibili.com/" + stream.roomId()).toString();
    }

    static LiveStream resolveStream(String input) throws Exception {
        return resolveStream(input, true);
    }

    static LiveStream resolveStream(String input, boolean authenticate) throws Exception {
        long requestedId = extractRoomId(input);
        JsonObject initialized = requestJson(URI.create(
            "https://api.live.bilibili.com/room/v1/Room/room_init?id=" + requestedId),
            "https://live.bilibili.com/" + requestedId, authenticate);
        JsonObject initData = object(initialized, "data");
        if (initData == null) {
            throw new IOException("直播房间信息为空");
        }
        long roomId = longValue(initData, "room_id", 0L);
        if (roomId <= 0L) {
            throw new IOException("直播房间不存在");
        }
        if (integer(initData, "live_status", 0) != 1) {
            throw new IOException("主播当前未开播");
        }
        if (booleanValue(initData, "is_locked") || booleanValue(initData, "encrypted")) {
            throw new IOException("该直播间需要额外验证");
        }

        JsonObject roomRoot = requestJson(URI.create(
            "https://api.live.bilibili.com/room/v1/Room/get_info?id=" + roomId),
            "https://live.bilibili.com/" + roomId, authenticate);
        JsonObject roomData = object(roomRoot, "data");
        String title = roomData == null ? "Bilibili 直播" : string(roomData, "title");

        JsonObject playRoot = requestJson(buildPlayInfoUri(roomId, 10000),
            "https://live.bilibili.com/" + roomId, authenticate);
        StreamCandidate selected = parseBestFlv(playRoot);
        if (selected == null) {
            throw new IOException("直播间没有返回兼容的 AVC/HEVC FLV 播放流");
        }
        return new LiveStream(roomId, title, selected.quality(),
            selected.codec(), selected.source());
    }

    static URI buildPlayInfoUri(long roomId, int quality) {
        return URI.create("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
            + "?room_id=" + roomId + "&protocol=0,1&format=0,1,2&codec=0,1"
            + "&qn=" + Math.max(0, quality) + "&platform=web&ptype=8"
            + "&mask=0&no_playurl=0");
    }

    static StreamCandidate parseBestFlv(JsonObject root) {
        JsonObject data = object(root, "data");
        JsonObject playurlInfo = object(data, "playurl_info");
        JsonObject playurl = object(playurlInfo, "playurl");
        JsonArray streams = array(playurl, "stream");
        if (streams == null) {
            return null;
        }
        List<StreamCandidate> candidates = new ArrayList<>();
        for (JsonElement streamElement : streams) {
            if (!streamElement.isJsonObject()) continue;
            JsonObject stream = streamElement.getAsJsonObject();
            if (!"http_stream".equals(string(stream, "protocol_name"))) continue;
            JsonArray formats = array(stream, "format");
            if (formats == null) continue;
            for (JsonElement formatElement : formats) {
                if (!formatElement.isJsonObject()) continue;
                JsonObject format = formatElement.getAsJsonObject();
                if (!"flv".equals(string(format, "format_name"))) continue;
                JsonArray codecs = array(format, "codec");
                if (codecs == null) continue;
                for (JsonElement codecElement : codecs) {
                    if (!codecElement.isJsonObject()) continue;
                    JsonObject codec = codecElement.getAsJsonObject();
                    String codecName = string(codec, "codec_name");
                    if (!(codecName.equals("avc") || codecName.equals("hevc"))) continue;
                    String base = string(codec, "base_url");
                    JsonArray urlInfo = array(codec, "url_info");
                    if (base.isBlank() || urlInfo == null || urlInfo.isEmpty()) continue;
                    JsonObject location = urlInfo.get(0).getAsJsonObject();
                    String full = string(location, "host") + base + string(location, "extra");
                    try {
                        candidates.add(new StreamCandidate(
                            integer(codec, "current_qn", 0),
                            codecName.equals("avc") ? 1 : 0, codecName, URI.create(full)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return candidates.stream().max(Comparator
            .comparingInt(StreamCandidate::quality)
            .thenComparingInt(StreamCandidate::codecPriority)).orElse(null);
    }

    private static JsonObject requestJson(URI uri, String referer,
                                          boolean authenticate) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", "https://live.bilibili.com");
        if (authenticate) {
            BilibiliAccountService.instance().applyAuthentication(builder);
        }
        HttpResponse<String> response = BilibiliHttp.sendString(builder.GET().build(), false);
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili 直播 API: HTTP " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("code") || root.get("code").getAsInt() != 0) {
            throw new IOException("Bilibili 直播 API: " + string(root, "message"));
        }
        return root;
    }

    private static long positiveLong(String value) throws IOException {
        try {
            long result = Long.parseLong(value);
            if (result > 0L) return result;
        } catch (NumberFormatException ignored) {
        }
        throw new IOException("直播房间号无效");
    }

    private static String clean(String value) {
        return value.replaceAll("<[^>]+>", "")
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String absoluteImage(String value) {
        return value.startsWith("//") ? "https:" + value : value;
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject()
            ? parent.getAsJsonObject(name) : null;
    }

    private static JsonArray array(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonArray()
            ? parent.getAsJsonArray(name) : null;
    }

    private static String string(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) return "";
        try { return parent.get(name).getAsString(); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static int integer(JsonObject parent, String name, int fallback) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) return fallback;
        try { return parent.get(name).getAsInt(); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static long longValue(JsonObject parent, String name, long fallback) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) return fallback;
        try { return Math.max(0L, parent.get(name).getAsLong()); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean booleanValue(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) return false;
        try { return parent.get(name).getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }
}
