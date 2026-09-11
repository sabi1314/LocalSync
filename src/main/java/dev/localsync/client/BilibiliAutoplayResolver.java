package dev.localsync.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BilibiliAutoplayResolver {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/131.0 Safari/537.36";
    private static final Pattern BVID = Pattern.compile("(BV[0-9A-Za-z]{10})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE = Pattern.compile("(?:[?&])p=(\\d+)",
        Pattern.CASE_INSENSITIVE);

    private BilibiliAutoplayResolver() {
    }

    record ViewPlan(boolean collection, String nextUrl) {
        static ViewPlan standalone() {
            return new ViewPlan(false, "");
        }

        static ViewPlan collectionEnd() {
            return new ViewPlan(true, "");
        }

        static ViewPlan next(String url) {
            return new ViewPlan(true, url);
        }

        boolean hasNext() {
            return nextUrl != null && !nextUrl.isBlank();
        }
    }

    private record Episode(String bvid, int page) {
        String pageUrl() {
            return videoUrl(bvid, page);
        }
    }

    static Optional<String> resolveNext(String input) throws Exception {
        URI current = parseSupportedUri(input);
        if (current == null) {
            return Optional.empty();
        }
        if (isShortHost(current.getHost())) {
            current = expandShortLink(current);
        }
        String bvid = extractBvid(current);
        if (bvid == null) {
            return Optional.empty();
        }
        int page = extractPage(current);
        JsonObject view = requestJson(URI.create(
            "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid));
        ViewPlan plan = parseView(view, bvid, page);
        if (plan.hasNext()) {
            return Optional.of(plan.nextUrl());
        }
        if (plan.collection()) {
            return Optional.empty();
        }

        JsonObject related = requestJson(URI.create(
            "https://api.bilibili.com/x/web-interface/archive/related?bvid=" + bvid));
        return Optional.ofNullable(parseRelated(related, bvid));
    }

    static String extractBvid(URI uri) {
        if (uri == null) {
            return null;
        }
        Matcher matcher = BVID.matcher(uri.toString());
        return matcher.find() ? matcher.group(1) : null;
    }

    static int extractPage(URI uri) {
        if (uri == null) {
            return 1;
        }
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

    static ViewPlan parseView(JsonObject root, String currentBvid, int currentPage)
            throws IOException {
        JsonObject data = object(root, "data");
        if (data == null) {
            throw new IOException("Bilibili 视频信息为空");
        }

        int page = Math.max(1, currentPage);
        JsonArray pages = array(data, "pages");
        boolean multiPage = pages != null && pages.size() > 1;
        if (multiPage && page < pages.size()) {
            return ViewPlan.next(videoUrl(currentBvid, page + 1));
        }

        JsonObject season = object(data, "ugc_season");
        if (season != null) {
            List<Episode> episodes = readEpisodes(season);
            int currentIndex = findEpisode(episodes, currentBvid, page);
            if (currentIndex >= 0 && currentIndex + 1 < episodes.size()) {
                return ViewPlan.next(episodes.get(currentIndex + 1).pageUrl());
            }
            return ViewPlan.collectionEnd();
        }
        return multiPage ? ViewPlan.collectionEnd() : ViewPlan.standalone();
    }

    static String parseRelated(JsonObject root, String currentBvid) {
        JsonArray related = array(root, "data");
        if (related == null) {
            return null;
        }
        for (JsonElement element : related) {
            if (!element.isJsonObject()) {
                continue;
            }
            String candidate = string(element.getAsJsonObject(), "bvid");
            if (validBvid(candidate) && !candidate.equalsIgnoreCase(currentBvid)) {
                return videoUrl(candidate, 1);
            }
        }
        return null;
    }

    private static List<Episode> readEpisodes(JsonObject season) {
        JsonArray sections = array(season, "sections");
        if (sections == null) {
            return List.of();
        }
        List<Episode> result = new ArrayList<>();
        for (JsonElement sectionElement : sections) {
            if (!sectionElement.isJsonObject()) {
                continue;
            }
            JsonArray episodes = array(sectionElement.getAsJsonObject(), "episodes");
            if (episodes == null) {
                continue;
            }
            for (JsonElement episodeElement : episodes) {
                if (!episodeElement.isJsonObject()) {
                    continue;
                }
                JsonObject episode = episodeElement.getAsJsonObject();
                String bvid = string(episode, "bvid");
                JsonObject arc = object(episode, "arc");
                if (!validBvid(bvid) && arc != null) {
                    bvid = string(arc, "bvid");
                }
                if (!validBvid(bvid)) {
                    continue;
                }
                JsonObject page = object(episode, "page");
                result.add(new Episode(bvid, Math.max(1, integer(page, "page", 1))));
            }
        }
        return List.copyOf(result);
    }

    private static int findEpisode(List<Episode> episodes, String currentBvid, int currentPage) {
        int bvidMatch = -1;
        for (int index = 0; index < episodes.size(); index++) {
            Episode episode = episodes.get(index);
            if (!episode.bvid().equalsIgnoreCase(currentBvid)) {
                continue;
            }
            if (bvidMatch < 0) {
                bvidMatch = index;
            }
            if (episode.page() == currentPage) {
                return index;
            }
        }
        return bvidMatch;
    }

    private static URI parseSupportedUri(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(input.trim());
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            if (isShortHost(host) || lower.equals("bilibili.com")
                    || lower.endsWith(".bilibili.com")) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static boolean isShortHost(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("b23.tv") || lower.endsWith(".b23.tv");
    }

    private static URI expandShortLink(URI uri) throws Exception {
        HttpRequest request = requestBuilder(uri).GET().build();
        HttpResponse<Void> response = BilibiliHttp.discard(request, true);
        if (response.statusCode() < 200 || response.statusCode() >= 400) {
            throw new IOException("Bilibili 短链展开失败: HTTP " + response.statusCode());
        }
        return response.uri();
    }

    private static JsonObject requestJson(URI uri) throws Exception {
        HttpRequest request = requestBuilder(uri)
            .header("Accept", "application/json")
            .GET().build();
        HttpResponse<String> response = BilibiliHttp.sendString(request, true);
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili API: HTTP " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("code") || root.get("code").getAsInt() != 0) {
            String message = root.has("message") && !root.get("message").isJsonNull()
                ? root.get("message").getAsString() : "未知错误";
            throw new IOException("Bilibili API: " + message);
        }
        return root;
    }

    private static HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com");
    }

    private static String videoUrl(String bvid, int page) {
        String base = "https://www.bilibili.com/video/" + bvid;
        return page > 1 ? base + "?p=" + page : base;
    }

    private static boolean validBvid(String value) {
        return value != null && BVID.matcher(value).matches();
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(name);
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(name);
    }

    private static String string(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) {
            return "";
        }
        try {
            return parent.get(name).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject parent, String name, int fallback) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) {
            return fallback;
        }
        try {
            return parent.get(name).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

}
