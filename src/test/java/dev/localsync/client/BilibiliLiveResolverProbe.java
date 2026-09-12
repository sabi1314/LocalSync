package dev.localsync.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;
import java.util.List;

public final class BilibiliLiveResolverProbe {
    public static void main(String[] args) throws Exception {
        check(BilibiliLiveResolver.isLiveInput("6"), "numeric room id detection failed");
        check(BilibiliLiveResolver.isLiveInput("https://live.bilibili.com/6"),
            "live URL detection failed");
        check(BilibiliLiveResolver.extractRoomId(
            "https://live.bilibili.com/blanc/23058?live_from=search") == 23058L,
            "room id extraction failed");
        String searchQuery = BilibiliLiveResolver.buildSearchUri("Minecraft 直播", 0)
            .getRawQuery();
        check(searchQuery.contains("search_type=live_room")
                && searchQuery.contains("order=online") && searchQuery.contains("page=1"),
            "live search query failed");

        JsonObject searchFixture = JsonParser.parseString("""
            {"data":{"result":[
              {"roomid":123,"title":"<em>方块</em>生存","uname":"主播 A",
               "online":4567,"live_status":1,"user_cover":"//i0.hdslb.com/live.jpg"},
              {"roomid":456,"title":"未开播","uname":"主播 B",
               "online":0,"live_status":0}
            ]}}
            """).getAsJsonObject();
        List<BilibiliLiveResolver.LiveSearchResult> search =
            BilibiliLiveResolver.parseSearchResults(searchFixture);
        check(search.size() == 1 && search.getFirst().roomId() == 123L
                && search.getFirst().title().equals("方块生存")
                && search.getFirst().coverUrl().startsWith("https://"),
            "live search parser failed");

        JsonObject playFixture = JsonParser.parseString("""
            {"data":{"playurl_info":{"playurl":{"stream":[
              {"protocol_name":"http_hls","format":[]},
              {"protocol_name":"http_stream","format":[{"format_name":"flv","codec":[
                {"codec_name":"hevc","current_qn":10000,"base_url":"/hevc.flv?",
                 "url_info":[{"host":"https://cdn.example","extra":"token=1"}]},
                {"codec_name":"avc","current_qn":10000,"base_url":"/avc.flv?",
                 "url_info":[{"host":"https://cdn.example","extra":"token=2"}]}
              ]}]}
            ]}}}}
            """).getAsJsonObject();
        BilibiliLiveResolver.StreamCandidate candidate =
            BilibiliLiveResolver.parseBestFlv(playFixture);
        check(candidate != null && candidate.quality() == 10000
                && candidate.codec().equals("avc")
                && candidate.source().toString().contains("avc.flv"),
            "highest-quality AVC FLV selection failed");

        if (args.length > 0 && args[0].equals("--live")) {
            List<BilibiliLiveResolver.LiveSearchResult> rooms =
                BilibiliLiveResolver.search("Minecraft", 1, false);
            check(!rooms.isEmpty(), "live room search returned no active room");
            BilibiliLiveResolver.LiveStream stream =
                BilibiliLiveResolver.resolveStream(rooms.getFirst().pageUrl(), false);
            check(stream.roomId() > 0L && stream.quality() > 0
                    && stream.source().isAbsolute(), "live stream resolution failed");
            HttpRequest request = HttpRequest.newBuilder(
                BilibiliResolver.createProxyUri(stream.source(), rooms.getFirst().pageUrl()))
                .GET().build();
            try (BilibiliHttp.StreamingResponse response =
                    BilibiliHttp.openStream(request, true)) {
                byte[] signature = response.body().readNBytes(3);
                check(response.statusCode() == 200
                        && signature.length == 3
                        && signature[0] == 'F' && signature[1] == 'L' && signature[2] == 'V',
                    "resolved live stream is not readable FLV");
            }
            System.out.println("PASS BilibiliLiveNetwork: rooms=" + rooms.size()
                + " quality=" + stream.quality() + " codec=" + stream.codec());
        } else {
            System.out.println("PASS BilibiliLiveResolver: room, search, and FLV selection");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
