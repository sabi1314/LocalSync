package dev.localsync.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public final class BilibiliResolverProbe {
    public static void main(String[] args) throws Exception {
        if (!BilibiliResolver.extractBvid(URI.create(
                "https://www.bilibili.com/video/BV1xx411c7mD?p=3")).equals("BV1xx411c7mD")) {
            throw new AssertionError("BV parser failed");
        }
        if (BilibiliResolver.extractPage(URI.create(
                "https://www.bilibili.com/video/BV1xx411c7mD?p=3")) != 3) {
            throw new AssertionError("page parser failed");
        }
        JsonObject fixture = JsonParser.parseString("""
            {"data":{"result":[{
              "bvid":"BV1test12345",
              "title":"<em class=\\"keyword\\">Minecraft</em> &amp; Friends",
              "author":"LocalSync UP",
              "duration":"3:21",
              "play":12580,
              "pic":"//i0.hdslb.com/test.jpg"
            }]}}
            """).getAsJsonObject();
        List<BilibiliResolver.SearchResult> results =
            BilibiliResolver.parseSearchResults(fixture);
        if (results.size() != 1
                || !results.getFirst().title().equals("Minecraft & Friends")
                || !results.getFirst().pageUrl().endsWith("BV1test12345")) {
            throw new AssertionError("search result parser failed: " + results);
        }
        if (args.length > 0 && args[0].equals("--search-network")) {
            List<BilibiliResolver.SearchResult> live =
                BilibiliResolver.searchVideos("Minecraft", 1);
            if (live.isEmpty() || live.getFirst().bvid().isBlank()) {
                throw new AssertionError("live Bilibili search returned no videos");
            }
            System.out.println("PASS BilibiliSearch: " + live.size()
                + " results, first=" + live.getFirst().bvid());
            return;
        }
        if (args.length == 0 || !args[0].equals("--network")) {
            System.out.println("PASS BilibiliResolver: BV, page, and search parsing");
            return;
        }
        String local = BilibiliResolver.resolveIfNeeded(
            "https://www.bilibili.com/video/BV1xx411c7mD");
        if (!local.startsWith("http://127.0.0.1:")) {
            throw new AssertionError("resolver did not return loopback proxy: " + local);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(local))
            .header("Range", "bytes=0-1023").GET().build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request,
            HttpResponse.BodyHandlers.ofByteArray());
        if ((response.statusCode() != 200 && response.statusCode() != 206)
                || response.body().length == 0) {
            throw new AssertionError("proxy response " + response.statusCode()
                + " bytes=" + response.body().length);
        }
        System.out.println("PASS BilibiliResolver: " + response.statusCode()
            + " bytes=" + response.body().length);
    }
}
