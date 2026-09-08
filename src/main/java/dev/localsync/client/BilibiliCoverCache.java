package dev.localsync.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BilibiliCoverCache {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/131.0 Safari/537.36";
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "localsync-cover-loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, Identifier> TEXTURES = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static volatile int generation;

    private BilibiliCoverCache() {
    }

    static Identifier getOrRequest(BilibiliResolver.SearchResult result) {
        Identifier loaded = TEXTURES.get(result.bvid());
        if (loaded != null || result.coverUrl().isBlank() || !PENDING.add(result.bvid())) {
            return loaded;
        }
        int requestGeneration = generation;
        LOADER.execute(() -> load(result, requestGeneration));
        return null;
    }

    private static void load(BilibiliResolver.SearchResult result, int requestGeneration) {
        try {
            String address = result.coverUrl().startsWith("//")
                ? "https:" + result.coverUrl() : result.coverUrl();
            HttpRequest request = HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).GET().build();
            byte[] bytes = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofByteArray()).body();
            NativeImage image = NativeImage.read(bytes);
            Minecraft.getInstance().execute(() -> register(result.bvid(), image,
                requestGeneration));
        } catch (Exception ignored) {
            PENDING.remove(result.bvid());
        }
    }

    private static void register(String bvid, NativeImage image, int requestGeneration) {
        PENDING.remove(bvid);
        if (requestGeneration != generation) {
            image.close();
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath("localsync",
            "covers/" + bvid.toLowerCase());
        Minecraft.getInstance().getTextureManager().register(id,
            new DynamicTexture(() -> "LocalSync cover " + bvid, image));
        TEXTURES.put(bvid, id);
    }

    static void release() {
        generation++;
        Minecraft client = Minecraft.getInstance();
        TEXTURES.values().forEach(client.getTextureManager()::release);
        TEXTURES.clear();
        PENDING.clear();
    }
}
