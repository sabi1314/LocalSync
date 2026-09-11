package dev.localsync.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class BilibiliHttpProbe {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/131.0 Safari/537.36";

    public static void main(String[] args) throws Exception {
        URI uri = BilibiliResolver.buildSearchUri("Minecraft", 1);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .GET().build();
        HttpResponse<String> response = BilibiliHttp.sendString(request, false);
        if (response.statusCode() != 200) {
            throw new AssertionError("live search HTTP " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("code") || root.get("code").getAsInt() != 0) {
            throw new AssertionError("live search API rejected the request");
        }
        List<BilibiliResolver.SearchResult> results =
            BilibiliResolver.parseSearchResults(root);
        if (results.isEmpty() || results.getFirst().bvid().isBlank()) {
            throw new AssertionError("live search returned no playable results");
        }
        System.out.println("PASS BilibiliSearchTransport: " + results.size()
            + " official-ranked results, first=" + results.getFirst().bvid());
    }
}
