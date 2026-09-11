package dev.localsync.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class BilibiliCoverCache {
    private static final int MAX_TEXTURES = 64;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 Chrome/131.0 Safari/537.36";
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "localsync-cover-loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final AccessOrderCache<String, Identifier> TEXTURES =
        new AccessOrderCache<>(MAX_TEXTURES);
    private static final Map<String, Integer> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Object STATE_LOCK = new Object();

    private BilibiliCoverCache() {
    }

    static Identifier getOrRequest(BilibiliResolver.SearchResult result) {
        return getOrRequest(result.bvid(), result.coverUrl());
    }

    static Identifier getOrRequest(String cacheKey, String coverUrl) {
        if (cacheKey == null || cacheKey.isBlank() || coverUrl == null) {
            return null;
        }
        final int requestGeneration;
        synchronized (STATE_LOCK) {
            Identifier loaded = TEXTURES.get(cacheKey);
            if (loaded != null || coverUrl.isBlank()) {
                return loaded;
            }
            requestGeneration = GENERATION.get();
            if (PENDING.putIfAbsent(cacheKey, requestGeneration) != null) {
                return null;
            }
        }
        try {
            LOADER.execute(() -> load(cacheKey, coverUrl, requestGeneration));
        } catch (RuntimeException error) {
            PENDING.remove(cacheKey, requestGeneration);
        }
        return null;
    }

    private static void load(String cacheKey, String coverUrl, int requestGeneration) {
        try {
            String address = coverUrl.startsWith("//") ? "https:" + coverUrl : coverUrl;
            HttpRequest request = HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).GET().build();
            byte[] bytes = BilibiliHttp.sendBytes(request, true).body();
            NativeImage image = NativeImage.read(bytes);
            try {
                Minecraft.getInstance().execute(() -> register(cacheKey, image,
                    requestGeneration));
            } catch (RuntimeException error) {
                image.close();
                PENDING.remove(cacheKey, requestGeneration);
            }
        } catch (Exception ignored) {
            PENDING.remove(cacheKey, requestGeneration);
        }
    }

    private static void register(String cacheKey, NativeImage image, int requestGeneration) {
        String safeKey = UUID.nameUUIDFromBytes(cacheKey.getBytes(StandardCharsets.UTF_8))
            .toString().replace("-", "");
        Identifier id = Identifier.fromNamespaceAndPath("localsync",
            "covers/" + safeKey);
        DynamicTexture texture = new DynamicTexture(() -> "LocalSync remote image", image);
        Minecraft client = Minecraft.getInstance();
        List<Identifier> removed;
        synchronized (STATE_LOCK) {
            PENDING.remove(cacheKey, requestGeneration);
            if (requestGeneration != GENERATION.get()) {
                texture.close();
                return;
            }
            try {
                client.getTextureManager().register(id, texture);
            } catch (RuntimeException error) {
                texture.close();
                return;
            }
            removed = TEXTURES.put(cacheKey, id);
        }
        releaseOnClientThread(removed);
    }

    static void release() {
        List<Identifier> loaded;
        synchronized (STATE_LOCK) {
            GENERATION.incrementAndGet();
            loaded = TEXTURES.clear();
            PENDING.clear();
        }
        releaseOnClientThread(loaded);
    }

    private static void releaseOnClientThread(List<Identifier> identifiers) {
        if (identifiers.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        Runnable release = () -> identifiers.forEach(client.getTextureManager()::release);
        if (client.isSameThread()) {
            release.run();
        } else {
            client.execute(release);
        }
    }

    static final class AccessOrderCache<K, V> {
        private final int capacity;
        private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, 0.75f, true);

        AccessOrderCache(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
        }

        synchronized V get(K key) {
            return values.get(key);
        }

        synchronized List<V> put(K key, V value) {
            List<V> removed = new ArrayList<>(1);
            V previous = values.put(key, value);
            if (previous != null && !previous.equals(value)) removed.add(previous);
            while (values.size() > capacity) {
                Map.Entry<K, V> eldest = values.entrySet().iterator().next();
                values.remove(eldest.getKey());
                removed.add(eldest.getValue());
            }
            return List.copyOf(removed);
        }

        synchronized List<V> clear() {
            List<V> removed = List.copyOf(values.values());
            values.clear();
            return removed;
        }

        synchronized int size() {
            return values.size();
        }
    }
}
