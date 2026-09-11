package dev.localsync.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;

public final class BilibiliAutoplayResolverProbe {
    private static final String CURRENT = "BV1xx411c7mD";
    private static final String NEXT = "BV1GJ411x7h7";
    private static final String RELATED = "BV17x411w7KC";

    public static void main(String[] args) throws Exception {
        check(CURRENT.equals(BilibiliAutoplayResolver.extractBvid(URI.create(
            "https://www.bilibili.com/video/" + CURRENT + "?p=3"))),
            "BV parser failed");
        check(BilibiliAutoplayResolver.extractPage(URI.create(
            "https://www.bilibili.com/video/" + CURRENT + "?p=3")) == 3,
            "page parser failed");

        JsonObject multiPageAndSeason = json("""
            {"data":{
              "pages":[{"page":1},{"page":2},{"page":3}],
              "ugc_season":{"sections":[
                {"episodes":[
                  {"bvid":"BV1xx411c7mD","page":{"page":3}},
                  {"arc":{"bvid":"BV1GJ411x7h7"},"page":{"page":1}}
                ]}
              ]}
            }}
            """);
        BilibiliAutoplayResolver.ViewPlan nextPage =
            BilibiliAutoplayResolver.parseView(multiPageAndSeason, CURRENT, 1);
        check(nextPage.collection()
                && ("https://www.bilibili.com/video/" + CURRENT + "?p=2")
                    .equals(nextPage.nextUrl()),
            "multi-page must take priority");

        BilibiliAutoplayResolver.ViewPlan nextEpisode =
            BilibiliAutoplayResolver.parseView(multiPageAndSeason, CURRENT, 3);
        check(nextEpisode.collection()
                && ("https://www.bilibili.com/video/" + NEXT)
                    .equals(nextEpisode.nextUrl()),
            "season episode after last page failed");

        JsonObject seasonEnd = json("""
            {"data":{
              "pages":[{"page":1}],
              "ugc_season":{"sections":[
                {"episodes":[{"arc":{"bvid":"BV1xx411c7mD"}}]}
              ]}
            }}
            """);
        BilibiliAutoplayResolver.ViewPlan endedSeason =
            BilibiliAutoplayResolver.parseView(seasonEnd, CURRENT, 1);
        check(endedSeason.collection() && !endedSeason.hasNext(),
            "last season episode must not fall through to related");

        JsonObject multiPageEnd = json("""
            {"data":{"pages":[{"page":1},{"page":2}]}}
            """);
        BilibiliAutoplayResolver.ViewPlan endedPages =
            BilibiliAutoplayResolver.parseView(multiPageEnd, CURRENT, 2);
        check(endedPages.collection() && !endedPages.hasNext(),
            "last multi-page item must not fall through to related");

        JsonObject standalone = json("""
            {"data":{"pages":[{"page":1}]}}
            """);
        BilibiliAutoplayResolver.ViewPlan standalonePlan =
            BilibiliAutoplayResolver.parseView(standalone, CURRENT, 1);
        check(!standalonePlan.collection() && !standalonePlan.hasNext(),
            "single video must be marked standalone");

        JsonObject related = json("""
            {"data":[
              {"bvid":"BV1xx411c7mD"},
              {"bvid":"invalid"},
              {"bvid":"BV17x411w7KC"},
              {"bvid":"BV1Q541167Qg"}
            ]}
            """);
        check(("https://www.bilibili.com/video/" + RELATED).equals(
                BilibiliAutoplayResolver.parseRelated(related, CURRENT)),
            "related parser did not select first valid non-current BV");

        System.out.println(
            "PASS BilibiliAutoplay: multi-page, ugc_season, collection end, related");
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
