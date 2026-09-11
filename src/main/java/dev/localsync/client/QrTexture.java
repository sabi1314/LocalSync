package dev.localsync.client;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QrTexture implements AutoCloseable {
    public static final int DEFAULT_SIZE = 192;
    private static final int MIN_SIZE = 96;
    private static final int MAX_SIZE = 384;

    private final Identifier identifier;
    private final int pixelSize;
    private final AtomicBoolean closed = new AtomicBoolean();

    private QrTexture(Identifier identifier, int pixelSize) {
        this.identifier = identifier;
        this.pixelSize = pixelSize;
    }

    public static QrTexture create(String content) throws WriterException {
        return create(content, DEFAULT_SIZE);
    }

    public static QrTexture create(String content, int requestedSize) throws WriterException {
        if (content == null || content.isBlank() || content.length() > 4_096) {
            throw new IllegalArgumentException("二维码内容为空或过长");
        }
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            throw new IllegalStateException("二维码纹理必须在 Minecraft 客户端线程创建");
        }

        int size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, requestedSize));
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE,
            size, size, Map.of(
                EncodeHintType.MARGIN, 3,
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        NativeImage image = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setPixelABGR(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }

        Identifier identifier = Identifier.fromNamespaceAndPath("localsync",
            "qr/" + UUID.randomUUID().toString().replace("-", ""));
        DynamicTexture texture = new DynamicTexture(() -> "LocalSync Bilibili QR", image);
        try {
            client.getTextureManager().register(identifier, texture);
        } catch (RuntimeException error) {
            texture.close();
            throw error;
        }
        return new QrTexture(identifier, size);
    }

    public Identifier identifier() {
        return identifier;
    }

    public int pixelSize() {
        return pixelSize;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void draw(GuiGraphicsExtractor graphics, int x, int y, int displaySize) {
        if (closed.get()) return;
        int size = Math.max(1, displaySize);
        graphics.blit(identifier, x, y, x + size, y + size,
            0f, 1f, 0f, 1f);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            client.getTextureManager().release(identifier);
        } else {
            client.execute(() -> client.getTextureManager().release(identifier));
        }
    }
}
