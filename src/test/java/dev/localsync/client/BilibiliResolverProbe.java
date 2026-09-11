package dev.localsync.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
        URI playUrl = BilibiliResolver.buildPlayUrlUri("BV1xx411c7mD", 12345L);
        String playQuery = playUrl.getRawQuery();
        if (!playQuery.contains("qn=127")
                || !playQuery.contains("high_quality=1")
                || !playQuery.contains("fourk=1")) {
            throw new AssertionError("maximum-quality play query failed: " + playUrl);
        }
        URI searchUri = BilibiliResolver.buildSearchUri("Minecraft 联机", 0);
        String searchQuery = searchUri.getRawQuery();
        if (!searchQuery.contains("search_type=video")
                || !searchQuery.contains("order=totalrank")
                || !searchQuery.contains("page=1")) {
            throw new AssertionError("official ranking search query failed: " + searchUri);
        }
        JsonObject fixture = JsonParser.parseString("""
            {"data":{"result":[{
              "bvid":"BV1test12345",
              "title":"<em class=\\"keyword\\">Minecraft</em> &amp; Friends",
              "author":"LocalSync UP",
              "duration":"3:21",
              "play":12580,
              "pic":"//i0.hdslb.com/test.jpg"
            },{
              "bvid":"BV1highFirst",
              "title":"First popular video",
              "play":900000
            },{
              "bvid":"BV1highSecond",
              "title":"Second popular video",
              "play":900000
            },{
              "bvid":"BV1unknownPlay",
              "title":"Unknown play count",
              "play":"--"
            }]}}
            """).getAsJsonObject();
        List<BilibiliResolver.SearchResult> results =
            BilibiliResolver.parseSearchResults(fixture);
        if (results.size() != 4
                || !results.get(0).title().equals("Minecraft & Friends")
                || results.get(0).playCount() != 12580L
                || !results.get(1).bvid().equals("BV1highFirst")
                || !results.get(2).bvid().equals("BV1highSecond")
                || results.get(3).playCount() != 0L) {
            throw new AssertionError("official API order or parser failed: " + results);
        }
        verifyLoopbackProxy();
        if (args.length > 0 && args[0].equals("--search-network")) {
            List<BilibiliResolver.SearchResult> live =
                BilibiliResolver.searchVideos("Minecraft", 1);
            if (live.isEmpty() || live.getFirst().bvid().isBlank()) {
                throw new AssertionError("live Bilibili search returned no videos");
            }
            System.out.println("PASS BilibiliSearch: " + live.size()
                + " official-ranked results, first=" + live.getFirst().bvid());
            return;
        }
        if (args.length == 0 || !args[0].equals("--network")) {
            System.out.println("PASS BilibiliResolver: BV, ordering, and socket proxy");
            return;
        }
        String local = BilibiliResolver.resolveIfNeeded(
            "https://www.bilibili.com/video/BV1xx411c7mD");
        if (!local.startsWith("http://127.0.0.1:")) {
            throw new AssertionError("resolver did not return loopback proxy: " + local);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(local))
            .header("Range", "bytes=0-1023").GET().build();
        HttpResponse<byte[]> response = BilibiliHttp.sendBytes(request, false);
        if ((response.statusCode() != 200 && response.statusCode() != 206)
                || response.body().length == 0) {
            throw new AssertionError("proxy response " + response.statusCode()
                + " bytes=" + response.body().length);
        }
        System.out.println("PASS BilibiliResolver: " + response.statusCode()
            + " bytes=" + response.body().length);
    }

    private static void verifyLoopbackProxy() throws Exception {
        byte[] expected = "LOCALSYNC".getBytes(StandardCharsets.US_ASCII);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>("");
        try (ServerSocket upstream = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            Thread source = new Thread(() -> {
                try (Socket client = upstream.accept()) {
                    received.set(readHeaders(client.getInputStream()));
                    String headers = "HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Type: video/mp4\r\n"
                        + "Content-Range: bytes 0-8/9\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "Content-Length: " + expected.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                    client.getOutputStream().write(headers.getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().write(expected);
                    client.getOutputStream().flush();
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "localsync-proxy-test-source");
            source.setDaemon(true);
            source.start();

            URI local = BilibiliResolver.createProxyUri(URI.create(
                "http://127.0.0.1:" + upstream.getLocalPort() + "/video"));
            HttpRequest request = HttpRequest.newBuilder(local)
                .header("Range", "bytes=0-8").GET().build();
            HttpResponse<byte[]> response = BilibiliHttp.sendBytes(request, false);
            source.join(3_000L);
            if (source.isAlive()) throw new AssertionError("proxy source did not finish");
            if (failure.get() != null) throw new AssertionError("proxy source failed", failure.get());
            if (response.statusCode() != 206 || !Arrays.equals(response.body(), expected)
                    || !received.get().toLowerCase().contains("range: bytes=0-8")) {
                throw new AssertionError("socket proxy response " + response.statusCode()
                    + " bytes=" + response.body().length + " request=" + received.get());
            }
        }
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (output.size() < 32 * 1024) {
            int value = input.read();
            if (value < 0) throw new AssertionError("source request ended early");
            output.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) {
                return output.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new AssertionError("source request header too large");
    }
}
