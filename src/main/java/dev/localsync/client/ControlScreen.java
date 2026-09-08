package dev.localsync.client;

import dev.localsync.net.Packets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public final class ControlScreen extends Screen {
    private enum Page { SEARCH, CONTROLS, HUD }

    private static final int MAX_PANEL_WIDTH = 760;
    private static final int MAX_PANEL_HEIGHT = 410;
    private static final int BACKDROP = 0xD407090D;
    private static final int PANEL = 0xF014171E;
    private static final int PANEL_ALT = 0xFF1A1D25;
    private static final int ROW_HOVER = 0xFF242833;
    private static final int ACCENT = 0xFFFB7299;
    private static final int ACCENT_DARK = 0xFFB84E70;
    private static final int TEXT = 0xFFF5F6F8;
    private static final int MUTED = 0xFFAAB0BC;
    private static final ExecutorService SEARCH_EXECUTOR =
        Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "localsync-bilibili-search");
            thread.setDaemon(true);
            return thread;
        });

    private Page page = Page.SEARCH;
    private EditBox searchBox;
    private EditBox urlBox;
    private Button searchButton;
    private List<BilibiliResolver.SearchResult> searchResults = List.of();
    private String searchText = "";
    private String urlText = "";
    private String notice = "";
    private boolean searching;
    private int searchPage = 1;
    private int searchSerial;
    private int visibleResults;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;
    private int sampleX;
    private int sampleY;
    private int sampleWidth;
    private int sampleHeight;
    private boolean draggingPreview;
    private double dragOffsetX;
    private double dragOffsetY;

    public ControlScreen() {
        super(Component.literal("LocalSync"));
    }

    @Override
    protected void init() {
        captureInputs();
        panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(320, width - 24));
        panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(230, height - 20));
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(10, (height - panelHeight) / 2);

        addTabs();
        switch (page) {
            case SEARCH -> initSearchPage();
            case CONTROLS -> initControlsPage();
            case HUD -> initHudPage();
        }
    }

    private void addTabs() {
        int gap = 6;
        int available = panelWidth - 36;
        int tabWidth = (available - gap * 2) / 3;
        int x = panelX + 18;
        int y = panelY + 34;
        Button search = addButton("视频搜索", x, y, tabWidth,
            () -> openPage(Page.SEARCH));
        x += tabWidth + gap;
        Button controls = addButton("播放与屏幕", x, y, tabWidth,
            () -> openPage(Page.CONTROLS));
        x += tabWidth + gap;
        Button hud = addButton("状态栏", x, y, tabWidth,
            () -> openPage(Page.HUD));
        search.active = page != Page.SEARCH;
        controls.active = page != Page.CONTROLS;
        hud.active = page != Page.HUD;
    }

    private void initSearchPage() {
        int x = panelX + 18;
        int y = panelY + 68;
        int searchWidth = Math.max(160, panelWidth - 134);
        searchBox = new EditBox(font, x, y, searchWidth, 22,
            Component.literal("搜索 Bilibili 视频"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.literal("搜索视频、UP 主或关键词"));
        searchBox.setValue(searchText);
        addRenderableWidget(searchBox);
        searchButton = addButton(searching ? "搜索中" : "搜索", x + searchWidth + 6,
            y, 92, () -> startSearch(1));
        searchButton.active = !searching;
        setInitialFocus(searchBox);

        int resultsTop = panelY + 100;
        int resultsBottom = panelY + panelHeight - 36;
        visibleResults = Math.max(1, Math.min(5, (resultsBottom - resultsTop) / 54));
        int count = Math.min(visibleResults, searchResults.size());
        for (int index = 0; index < count; index++) {
            BilibiliResolver.SearchResult result = searchResults.get(index);
            int rowY = resultsTop + index * 54;
            addButton("播放", panelX + panelWidth - 76, rowY + 15, 52,
                () -> playResult(result));
        }

        Button previous = addButton("上一页", panelX + 18,
            panelY + panelHeight - 28, 72, () -> startSearch(searchPage - 1));
        previous.active = !searching && searchPage > 1;
        Button next = addButton("下一页", panelX + 96,
            panelY + panelHeight - 28, 72, () -> startSearch(searchPage + 1));
        next.active = !searching && !searchResults.isEmpty();
    }

    private void initControlsPage() {
        int left = panelX + 18;
        int available = panelWidth - 36;
        int gap = 6;
        int y = panelY + 79;
        urlBox = new EditBox(font, left, y, available, 22,
            Component.literal("媒体链接"));
        urlBox.setMaxLength(8192);
        urlBox.setHint(Component.literal("Bilibili 或 HTTP/HTTPS 媒体链接"));
        if (urlText.isBlank()) {
            String current = PlaybackSession.instance().snapshot().mediaUrl();
            if (current != null) urlText = current;
        }
        urlBox.setValue(urlText);
        addRenderableWidget(urlBox);

        y += 31;
        int transportWidth = (available - gap * 4) / 5;
        int x = left;
        addButton("播放", x, y, transportWidth,
            () -> send(Packets.PLAY, 0L, urlBox.getValue()));
        x += transportWidth + gap;
        addButton("暂停/继续", x, y, transportWidth,
            () -> send(PlaybackSession.instance().snapshot().paused()
                ? Packets.RESUME : Packets.PAUSE, 0L, ""));
        x += transportWidth + gap;
        addButton("-10s", x, y, transportWidth,
            () -> send(Packets.SEEK_RELATIVE, -10_000L, ""));
        x += transportWidth + gap;
        addButton("+10s", x, y, transportWidth,
            () -> send(Packets.SEEK_RELATIVE, 10_000L, ""));
        x += transportWidth + gap;
        addButton("停止", x, y, transportWidth,
            () -> send(Packets.STOP, 0L, ""));

        y += 31;
        int optionWidth = (available - gap * 3) / 4;
        x = left;
        addButton("音量 -", x, y, optionWidth, () -> changeVolume(-5));
        x += optionWidth + gap;
        addButton("音量 +", x, y, optionWidth, () -> changeVolume(5));
        x += optionWidth + gap;
        addButton(PlaybackSession.instance().visible() ? "隐藏画面" : "显示画面",
            x, y, optionWidth, () -> {
                PlaybackSession.instance().toggleVisible();
                notice = PlaybackSession.instance().visible() ? "画面已显示" : "画面已隐藏";
                rebuildWidgets();
            });
        x += optionWidth + gap;
        addButton("翻转画面", x, y, optionWidth, () -> {
            PlaybackSession.instance().toggleFlip();
            notice = "画面方向已切换";
        });

        y += 55;
        int screenWidth = (available - gap * 2) / 3;
        x = left;
        addButton("设置角点 1", x, y, screenWidth,
            () -> selectCorner(Packets.SCREEN_POS1));
        x += screenWidth + gap;
        addButton("设置角点 2", x, y, screenWidth,
            () -> selectCorner(Packets.SCREEN_POS2));
        x += screenWidth + gap;
        addButton("清除屏幕", x, y, screenWidth,
            () -> send(Packets.SCREEN_CLEAR, 0L, ""));
    }

    private void initHudPage() {
        HudSettings settings = HudSettings.instance();
        int left = panelX + 18;
        int sliderWidth = Math.max(130, (panelWidth - 60) / 2);
        int y = panelY + 84;
        addRenderableWidget(new SettingSlider(left, y, sliderWidth,
            "水平位置", 0.0, 1.0, settings.xPosition(), settings::setXPosition,
            value -> Math.round(value * 100) + "%"));
        y += 28;
        addRenderableWidget(new SettingSlider(left, y, sliderWidth,
            "垂直位置", 0.0, 1.0, settings.yPosition(), settings::setYPosition,
            value -> Math.round(value * 100) + "%"));
        y += 28;
        addRenderableWidget(new SettingSlider(left, y, sliderWidth,
            "状态栏宽度", HudSettings.MIN_WIDTH, HudSettings.MAX_WIDTH,
            settings.panelWidth(), settings::setPanelWidth,
            value -> Math.round(value) + " px"));
        y += 28;
        addRenderableWidget(new SettingSlider(left, y, sliderWidth,
            "界面缩放", HudSettings.MIN_SCALE, HudSettings.MAX_SCALE,
            settings.scale(), settings::setScale,
            value -> String.format(Locale.ROOT, "%.0f%%", value * 100)));
        y += 34;
        addButton("恢复默认", left, y, sliderWidth, () -> {
            settings.reset();
            notice = "状态栏布局已恢复默认";
            rebuildWidgets();
        });

        previewX = left + sliderWidth + 18;
        previewY = panelY + 84;
        previewWidth = Math.max(110, panelX + panelWidth - 18 - previewX);
        previewHeight = Math.max(60, panelHeight - 118);
        updatePreviewBounds();
    }

    private void openPage(Page target) {
        captureInputs();
        page = target;
        notice = "";
        rebuildWidgets();
    }

    private void captureInputs() {
        if (searchBox != null) searchText = searchBox.getValue();
        if (urlBox != null) urlText = urlBox.getValue();
    }

    private void startSearch(int requestedPage) {
        String query = searchBox == null ? searchText : searchBox.getValue().trim();
        if (query.isBlank()) {
            notice = "请输入搜索关键词";
            return;
        }
        searchText = query;
        searchPage = Math.max(1, requestedPage);
        searching = true;
        notice = "正在搜索 Bilibili";
        if (searchButton != null) searchButton.active = false;
        int serial = ++searchSerial;
        SEARCH_EXECUTOR.execute(() -> {
            try {
                List<BilibiliResolver.SearchResult> found =
                    BilibiliResolver.searchVideos(query, searchPage);
                minecraft.execute(() -> finishSearch(serial, found, null));
            } catch (Throwable error) {
                minecraft.execute(() -> finishSearch(serial, List.of(), error));
            }
        });
    }

    private void finishSearch(int serial, List<BilibiliResolver.SearchResult> found,
                              Throwable error) {
        if (serial != searchSerial) return;
        searching = false;
        searchResults = found;
        notice = error == null ? "第 " + searchPage + " 页 · " + found.size() + " 个结果"
            : "搜索失败: " + readable(error);
        if (minecraft.screen == this) rebuildWidgets();
    }

    private void playResult(BilibiliResolver.SearchResult result) {
        send(Packets.PLAY, 0L, result.pageUrl());
    }

    private void changeVolume(int delta) {
        PlaybackSession session = PlaybackSession.instance();
        session.setVolume(session.volume() + delta);
        notice = "本地音量 " + session.volume();
    }

    private Button addButton(String label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label),
            button -> action.run()).bounds(x, y, width, 22).build());
    }

    private void selectCorner(int action) {
        if (!LocalSyncCommands.selectLookedAtCorner(action)) {
            notice = "请先看向竖直墙面上的方块";
            return;
        }
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(action == Packets.SCREEN_POS1
                ? "LocalSync | 角点 1 已设置，请看向同一墙面的另一角并按 P"
                : "LocalSync | 角点 2 已提交，影院屏幕正在同步"));
        }
        onClose();
    }

    private void send(int action, long value, String valueText) {
        String text = valueText;
        if (action == Packets.PLAY && (text == null || text.isBlank())) {
            text = minecraft.keyboardHandler.getClipboard();
        }
        if (action == Packets.PLAY && (text == null || text.isBlank())) {
            notice = "请输入媒体链接或从搜索结果播放";
            return;
        }
        if (LocalSyncCommands.sendAction(action, value, text)) {
            notice = action == Packets.PLAY ? "已提交播放请求" : "控制已同步";
            if (action == Packets.PLAY) onClose();
        } else {
            notice = "当前世界未加载 LocalSync 服务端";
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (page == Page.SEARCH && searchBox != null && searchBox.isFocused()
                && (event.key() == GLFW.GLFW_KEY_ENTER
                    || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            startSearch(1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (page == Page.HUD && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            updatePreviewBounds();
            if (inside(event.x(), event.y(), sampleX, sampleY, sampleWidth, sampleHeight)) {
                draggingPreview = true;
                dragOffsetX = event.x() - sampleX;
                dragOffsetY = event.y() - sampleY;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!draggingPreview) return super.mouseDragged(event, dragX, dragY);
        int availableX = Math.max(1, previewWidth - sampleWidth);
        int availableY = Math.max(1, previewHeight - sampleHeight);
        double x = (event.x() - dragOffsetX - previewX) / availableX;
        double y = (event.y() - dragOffsetY - previewY) / availableY;
        HudSettings.instance().setPositionPreview(x, y);
        updatePreviewBounds();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingPreview) {
            draggingPreview = false;
            HudSettings.instance().save();
            rebuildWidgets();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX,
                                   int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        graphics.fill(panelX, panelY, panelX + 4, panelY + panelHeight, ACCENT);
        graphics.text(font, "LocalSync", panelX + 18, panelY + 13, TEXT, false);
        String state = font.plainSubstrByWidth(PlaybackSession.instance().statusText(),
            panelWidth - 110);
        graphics.text(font, state, panelX + panelWidth - 18 - font.width(state),
            panelY + 13, MUTED, false);

        if (page == Page.SEARCH) drawSearchResults(graphics, mouseX, mouseY);
        if (page == Page.CONTROLS) drawControlStatus(graphics);
        if (page == Page.HUD) drawHudPreview(graphics);

        if (!notice.isBlank()) {
            String fitted = font.plainSubstrByWidth(notice, panelWidth - 210);
            graphics.text(font, fitted, panelX + panelWidth - 18 - font.width(fitted),
                panelY + panelHeight - 20, MUTED, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSearchResults(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int count = Math.min(visibleResults, searchResults.size());
        int resultsTop = panelY + 100;
        for (int index = 0; index < count; index++) {
            BilibiliResolver.SearchResult result = searchResults.get(index);
            int rowY = resultsTop + index * 54;
            boolean hovered = inside(mouseX, mouseY, panelX + 18, rowY,
                panelWidth - 36, 50);
            graphics.fill(panelX + 18, rowY, panelX + panelWidth - 18, rowY + 50,
                hovered ? ROW_HOVER : PANEL_ALT);
            Identifier cover = BilibiliCoverCache.getOrRequest(result);
            if (cover != null) {
                graphics.blit(cover, panelX + 21, rowY + 3, 78, 44,
                    0f, 1f, 0f, 1f);
            } else {
                graphics.fill(panelX + 21, rowY + 3, panelX + 99, rowY + 47,
                    0xFF292D38);
                graphics.centeredText(font, "BILIBILI", panelX + 60, rowY + 21, ACCENT);
            }
            int textX = panelX + 108;
            int textWidth = panelWidth - 206;
            String title = font.plainSubstrByWidth(result.title(), Math.max(80, textWidth));
            graphics.text(font, title, textX, rowY + 8, TEXT, false);
            String meta = result.author() + "  ·  " + result.duration()
                + "  ·  " + formatPlayCount(result.playCount());
            meta = font.plainSubstrByWidth(meta, Math.max(80, textWidth));
            graphics.text(font, meta, textX, rowY + 28, MUTED, false);
        }
        if (!searching && searchResults.isEmpty()) {
            graphics.centeredText(font, searchText.isBlank()
                ? "搜索 Bilibili 视频" : "没有找到视频",
                panelX + panelWidth / 2, panelY + 154, MUTED);
        }
        if (searching) {
            graphics.centeredText(font, "正在搜索...",
                panelX + panelWidth / 2, panelY + 154, ACCENT);
        }
        graphics.text(font, "第 " + searchPage + " 页", panelX + 178,
            panelY + panelHeight - 21, MUTED, false);
    }

    private void drawControlStatus(GuiGraphicsExtractor graphics) {
        String playback = font.plainSubstrByWidth(PlaybackSession.instance().statusText(),
            (panelWidth - 48) / 2);
        String screen = font.plainSubstrByWidth(ScreenState.instance().statusText(),
            (panelWidth - 48) / 2);
        graphics.text(font, playback, panelX + 18, panelY + 66, MUTED, false);
        graphics.text(font, screen, panelX + panelWidth - 18 - font.width(screen),
            panelY + 66, MUTED, false);
        graphics.fill(panelX + 18, panelY + 176, panelX + panelWidth - 18,
            panelY + 177, 0xFF333844);
        graphics.text(font, "影院屏幕", panelX + 18, panelY + 183, TEXT, false);
    }

    private void drawHudPreview(GuiGraphicsExtractor graphics) {
        updatePreviewBounds();
        graphics.text(font, "状态栏布局", panelX + 18, panelY + 69, TEXT, false);
        graphics.text(font, "预览", previewX, panelY + 69, MUTED, false);
        graphics.fill(previewX, previewY, previewX + previewWidth,
            previewY + previewHeight, 0xFF090B10);
        graphics.outline(previewX, previewY, previewWidth, previewHeight, 0xFF3A404D);
        graphics.fill(sampleX, sampleY, sampleX + sampleWidth,
            sampleY + sampleHeight, 0xEE14171E);
        graphics.fill(sampleX, sampleY, sampleX + 3,
            sampleY + sampleHeight, ACCENT);
        if (sampleHeight >= 18) {
            String title = font.plainSubstrByWidth("LocalSync · 视频标题",
                Math.max(20, sampleWidth - 14));
            graphics.text(font, title, sampleX + 7, sampleY + 5, TEXT, false);
            int progressY = sampleY + sampleHeight - 7;
            graphics.fill(sampleX + 7, progressY, sampleX + sampleWidth - 7,
                progressY + 2, 0xFF343945);
            graphics.fill(sampleX + 7, progressY,
                sampleX + 7 + Math.max(1, (sampleWidth - 14) * 2 / 5),
                progressY + 2, ACCENT_DARK);
        }
    }

    private void updatePreviewBounds() {
        if (previewWidth <= 0 || previewHeight <= 0) return;
        HudSettings settings = HudSettings.instance();
        double widthRatio = (double) settings.panelWidth() / HudSettings.MAX_WIDTH;
        sampleWidth = Math.max(70, Math.min(previewWidth,
            (int) Math.round(previewWidth * widthRatio * settings.scale() * 0.78)));
        sampleHeight = Math.max(16, Math.min(previewHeight,
            (int) Math.round(sampleWidth * HudSettings.PANEL_HEIGHT
                / (double) settings.panelWidth())));
        sampleX = previewX + (int) Math.round(settings.xPosition()
            * Math.max(0, previewWidth - sampleWidth));
        sampleY = previewY + (int) Math.round(settings.yPosition()
            * Math.max(0, previewHeight - sampleHeight));
    }

    private static boolean inside(double x, double y, int left, int top,
                                  int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static String formatPlayCount(long value) {
        if (value >= 100_000_000L) {
            return String.format(Locale.ROOT, "%.1f亿播放", value / 100_000_000.0);
        }
        if (value >= 10_000L) {
            return String.format(Locale.ROOT, "%.1f万播放", value / 10_000.0);
        }
        return value + " 播放";
    }

    private static String readable(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank()
            ? current.getClass().getSimpleName() : message;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class SettingSlider extends AbstractSliderButton {
        private final String label;
        private final double minimum;
        private final double maximum;
        private final DoubleConsumer consumer;
        private final DoubleFunction<String> formatter;

        private SettingSlider(int x, int y, int width, String label,
                              double minimum, double maximum, double current,
                              DoubleConsumer consumer,
                              DoubleFunction<String> formatter) {
            super(x, y, width, 20, Component.empty(),
                (current - minimum) / (maximum - minimum));
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.consumer = consumer;
            this.formatter = formatter;
            updateMessage();
        }

        private double current() {
            return minimum + value * (maximum - minimum);
        }

        @Override
        protected void updateMessage() {
            if (label != null) {
                setMessage(Component.literal(label + "  " + formatter.apply(current())));
            }
        }

        @Override
        protected void applyValue() {
            if (consumer != null) consumer.accept(current());
        }
    }
}
