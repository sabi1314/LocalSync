package dev.localsync.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BilibiliAccountData {
    private static final int MAX_COOKIE_HEADER_LENGTH = 16_384;
    private static final int MAX_COOKIE_VALUE_LENGTH = 8_192;
    private static final List<String> ALLOWED_COOKIES = List.of(
        "SESSDATA",
        "bili_jct",
        "DedeUserID",
        "DedeUserID__ckMd5",
        "sid",
        "buvid3",
        "buvid4",
        "buvid_fp",
        "b_nut",
        "b_lsid",
        "_uuid"
    );

    private BilibiliAccountData() {
    }

    public enum QrState {
        WAITING_FOR_SCAN,
        WAITING_FOR_CONFIRMATION,
        CONFIRMED,
        EXPIRED,
        ERROR
    }

    public static final class QrLogin {
        private final String url;
        private final String key;

        private QrLogin(String url, String key) {
            this.url = url;
            this.key = key;
        }

        public String url() {
            return url;
        }

        public String key() {
            return key;
        }

        @Override
        public String toString() {
            return "QrLogin[url=<redacted>, key=<redacted>]";
        }
    }

    public static final class QrPoll {
        private final QrState state;
        private final int code;
        private final String message;
        private final String successUrl;

        private QrPoll(QrState state, int code, String message, String successUrl) {
            this.state = state;
            this.code = code;
            this.message = message;
            this.successUrl = successUrl;
        }

        public QrState state() {
            return state;
        }

        public int code() {
            return code;
        }

        public String message() {
            return message;
        }

        String successUrl() {
            return successUrl;
        }

        @Override
        public String toString() {
            return "QrPoll[state=" + state + ", code=" + code + "]";
        }
    }

    public record Profile(long mid, String name, String avatarUrl, int level, boolean vip) {
    }

    public record FavoriteFolder(long id, String title, int mediaCount) {
    }

    public record FavoriteVideo(long avid, String bvid, String title, String coverUrl,
                                String ownerName, long durationSeconds, long playCount,
                                boolean available) {
        public String pageUrl() {
            return available && !bvid.isBlank()
                ? "https://www.bilibili.com/video/" + bvid : "";
        }
    }

    public record FavoritePage(List<FavoriteVideo> videos, int page, int total,
                               boolean hasMore) {
        public FavoritePage {
            videos = List.copyOf(videos);
        }
    }

    public static String normalizeCookieHeader(String rawHeader) {
        if (rawHeader == null) {
            return "";
        }
        String raw = rawHeader.trim();
        if (raw.length() > MAX_COOKIE_HEADER_LENGTH || containsLineBreak(raw)) {
            throw new IllegalArgumentException("Cookie 内容过长或包含换行");
        }
        if (raw.regionMatches(true, 0, "Cookie:", 0, "Cookie:".length())) {
            raw = raw.substring("Cookie:".length()).trim();
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            addCookiePair(values, part);
        }
        return formatCookies(values);
    }

    public static boolean hasAuthenticatedCookie(String cookieHeader) {
        return cookieValues(cookieHeader).containsKey("SESSDATA");
    }

    static String cookiesFromLogin(List<String> setCookieHeaders, String successUrl) {
        Map<String, String> values = new LinkedHashMap<>();
        if (setCookieHeaders != null) {
            for (String header : setCookieHeaders) {
                if (header == null || containsLineBreak(header)) continue;
                int end = header.indexOf(';');
                addCookiePair(values, end >= 0 ? header.substring(0, end) : header);
            }
        }
        addCookiesFromSuccessUrl(values, successUrl);
        return formatCookies(values);
    }

    static String mergeCookieHeaders(String first, String second) {
        Map<String, String> values = cookieValues(first);
        values.putAll(cookieValues(second));
        return formatCookies(values);
    }

    public static QrLogin parseQrLogin(String json) {
        JsonObject data = successfulData(json);
        String url = requiredString(data, "url");
        String key = requiredString(data, "qrcode_key");
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !(host.equals("bilibili.com") || host.endsWith(".bilibili.com"))) {
            throw new IllegalArgumentException("Bilibili 返回了无效二维码地址");
        }
        if (!key.matches("[0-9A-Za-z_-]{16,128}")) {
            throw new IllegalArgumentException("Bilibili 返回了无效二维码标识");
        }
        return new QrLogin(url, key);
    }

    public static QrPoll parseQrPoll(String json) {
        JsonObject data = successfulData(json);
        int code = integer(data, "code", Integer.MIN_VALUE);
        String message = string(data, "message", "");
        QrState state = switch (code) {
            case 86101 -> QrState.WAITING_FOR_SCAN;
            case 86090 -> QrState.WAITING_FOR_CONFIRMATION;
            case 86038 -> QrState.EXPIRED;
            case 0 -> QrState.CONFIRMED;
            default -> QrState.ERROR;
        };
        String successUrl = state == QrState.CONFIRMED ? string(data, "url", "") : "";
        return new QrPoll(state, code, message, successUrl);
    }

    public static Profile parseProfile(String json) {
        JsonObject data = successfulData(json);
        if (!booleanValue(data, "isLogin", false)) {
            throw new IllegalArgumentException("Bilibili 登录状态已失效");
        }
        JsonObject levelInfo = object(data, "level_info");
        int level = levelInfo == null ? 0 : integer(levelInfo, "current_level", 0);
        boolean vip = integer(data, "vipStatus", 0) == 1;
        return new Profile(longValue(data, "mid", 0L), string(data, "uname", ""),
            string(data, "face", ""), Math.max(0, level), vip);
    }

    public static List<FavoriteFolder> parseFavoriteFolders(String json) {
        JsonObject data = successfulData(json);
        JsonArray list = array(data, "list");
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<FavoriteFolder> folders = new ArrayList<>(list.size());
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            long id = longValue(value, "id", longValue(value, "media_id", 0L));
            if (id <= 0L) continue;
            folders.add(new FavoriteFolder(id, string(value, "title", "未命名收藏夹"),
                Math.max(0, integer(value, "media_count", 0))));
        }
        return List.copyOf(folders);
    }

    public static FavoritePage parseFavoritePage(String json, int page) {
        JsonObject data = successfulData(json);
        JsonArray medias = array(data, "medias");
        List<FavoriteVideo> videos = new ArrayList<>(medias == null ? 0 : medias.size());
        if (medias != null) {
            for (JsonElement element : medias) {
                if (!element.isJsonObject()) continue;
                JsonObject value = element.getAsJsonObject();
                JsonObject upper = object(value, "upper");
                JsonObject counters = object(value, "cnt_info");
                String bvid = string(value, "bvid", "");
                int attr = integer(value, "attr", 0);
                videos.add(new FavoriteVideo(
                    longValue(value, "id", 0L),
                    bvid,
                    string(value, "title", "已失效视频"),
                    string(value, "cover", ""),
                    upper == null ? "" : string(upper, "name", ""),
                    Math.max(0L, longValue(value, "duration", 0L)),
                    counters == null ? 0L : Math.max(0L, longValue(counters, "play", 0L)),
                    !bvid.isBlank() && attr == 0));
            }
        }

        int total = integer(data, "ttl", -1);
        JsonObject info = object(data, "info");
        if (info != null) total = integer(info, "media_count", total);
        if (total < 0) total = videos.size();
        boolean hasMore = booleanValue(data, "has_more", false);
        return new FavoritePage(videos, Math.max(1, page), total, hasMore);
    }

    static String encodeStoredCookie(String normalizedCookie, long savedAtEpochSeconds) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("cookie", normalizeCookieHeader(normalizedCookie));
        root.addProperty("saved_at", Math.max(0L, savedAtEpochSeconds));
        return root.toString();
    }

    static String decodeStoredCookie(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (integer(root, "version", 0) != 1) {
            throw new IllegalArgumentException("不支持的账户配置版本");
        }
        String normalized = normalizeCookieHeader(requiredString(root, "cookie"));
        if (!hasAuthenticatedCookie(normalized)) {
            throw new IllegalArgumentException("账户配置中没有有效登录 Cookie");
        }
        return normalized;
    }

    private static JsonObject successfulData(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Bilibili API 返回格式错误");
        }
        JsonObject root = parsed.getAsJsonObject();
        int code = integer(root, "code", Integer.MIN_VALUE);
        if (code != 0) {
            String message = string(root, "message", "未知错误");
            throw new IllegalArgumentException("Bilibili API 错误 " + code + ": " + message);
        }
        JsonObject data = object(root, "data");
        if (data == null) {
            throw new IllegalArgumentException("Bilibili API 未返回数据");
        }
        return data;
    }

    private static void addCookiesFromSuccessUrl(Map<String, String> values, String successUrl) {
        if (successUrl == null || successUrl.isBlank() || containsLineBreak(successUrl)) return;
        try {
            String query = URI.create(successUrl).getRawQuery();
            if (query == null) return;
            for (String part : query.split("&")) {
                int separator = part.indexOf('=');
                if (separator <= 0) continue;
                String name = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
                String canonical = canonicalCookieName(name);
                if (canonical == null) continue;
                String value = part.substring(separator + 1);
                if (validCookieValue(value)) values.put(canonical, value);
            }
        } catch (RuntimeException ignored) {
            // Set-Cookie is authoritative; an unusable fallback URL is simply ignored.
        }
    }

    private static Map<String, String> cookieValues(String header) {
        Map<String, String> values = new LinkedHashMap<>();
        String normalized = normalizeCookieHeader(header);
        if (normalized.isEmpty()) return values;
        for (String part : normalized.split("; ")) addCookiePair(values, part);
        return values;
    }

    private static void addCookiePair(Map<String, String> values, String pair) {
        if (pair == null) return;
        String trimmed = pair.trim();
        int separator = trimmed.indexOf('=');
        if (separator <= 0) return;
        String canonical = canonicalCookieName(trimmed.substring(0, separator).trim());
        if (canonical == null) return;
        String value = trimmed.substring(separator + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (validCookieValue(value)) values.put(canonical, value);
    }

    private static String canonicalCookieName(String candidate) {
        for (String allowed : ALLOWED_COOKIES) {
            if (allowed.equalsIgnoreCase(candidate)) return allowed;
        }
        return null;
    }

    private static boolean validCookieValue(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_COOKIE_VALUE_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x20 || current == 0x7F || current == ';') return false;
        }
        return true;
    }

    private static String formatCookies(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (String name : ALLOWED_COOKIES) {
            String value = values.get(name);
            if (value == null) continue;
            if (!result.isEmpty()) result.append("; ");
            result.append(name).append('=').append(value);
        }
        return result.toString();
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent == null ? null : parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String name) {
        JsonElement value = parent == null ? null : parent.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String requiredString(JsonObject parent, String name) {
        String result = string(parent, name, "");
        if (result.isBlank()) throw new IllegalArgumentException("Bilibili API 缺少字段: " + name);
        return result;
    }

    private static String string(JsonObject parent, String name, String fallback) {
        JsonElement value = parent == null ? null : parent.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject parent, String name, int fallback) {
        long value = longValue(parent, name, fallback);
        return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? fallback : (int) value;
    }

    private static long longValue(JsonObject parent, String name, long fallback) {
        JsonElement value = parent == null ? null : parent.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject parent, String name, boolean fallback) {
        JsonElement value = parent == null ? null : parent.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
