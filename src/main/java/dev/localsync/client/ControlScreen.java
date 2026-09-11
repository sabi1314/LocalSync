package dev.localsync.client;

import dev.localsync.client.BilibiliAccountData.FavoriteFolder;
import dev.localsync.client.BilibiliAccountData.FavoritePage;
import dev.localsync.client.BilibiliAccountData.FavoriteVideo;
import dev.localsync.client.BilibiliAccountData.Profile;
import dev.localsync.client.BilibiliAccountData.QrLogin;
import dev.localsync.client.BilibiliAccountData.QrPoll;
import dev.localsync.client.BilibiliAccountData.QrState;
import dev.localsync.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public final class ControlScreen extends Screen {
    private enum Page { SEARCH, FAVORITES, CONTROLS, HUD, ACCOUNT }

    private static final int MAX_PANEL_WIDTH = 780;
    private static final int MAX_PANEL_HEIGHT = 430;
    private static final int EDGE = 12;
    private static final int TAB_HEIGHT = 23;
    private static final int ROW_HEIGHT = 54;
    private static final int BACKDROP_TOP = 0x8F080A0E;
    private static final int BACKDROP_BOTTOM = 0xB50C0F14;
    private static final int ROW = 0x92272C35;
    private static final int ROW_HOVER = 0xC13B424E;
    private static final int ERROR = 0xFFFF7979;
    private static final ExecutorService SEARCH_EXECUTOR =
        Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "localsync-bilibili-search");
            thread.setDaemon(true);
            return thread;
        });

    private final BilibiliAccountService account = BilibiliAccountService.instance();

    private Page page = Page.SEARCH;
    private EditBox searchBox;
    private EditBox urlBox;
    private EditBox cookieBox;
    private GlassButton searchButton;
    private List<BilibiliResolver.SearchResult> searchResults = List.of();
    private String searchText = "";
    private String urlText = "";
    private String cookieText = "";
    private String notice = "";
    private boolean searching;
    private int searchPage = 1;
    private int searchSerial;
    private int searchOffset;
    private int visibleResults;

    private Profile accountProfile;
    private List<FavoriteFolder> favoriteFolders = List.of();
    private FavoritePage favoritePageData;
    private long selectedFolderId;
    private int favoritePage = 1;
    private int favoriteOffset;
    private int folderOffset;
    private int visibleFavoriteRows;
    private int visibleFolderRows;
    private int folderSerial;
    private int favoriteSerial;
    private boolean foldersLoading;
    private boolean favoritesLoading;

    private QrLogin qrLogin;
    private QrState qrState;
    private QrTexture qrTexture;
    private int accountSerial;
    private boolean accountLoading;
    private boolean qrRequesting;
    private boolean qrPolling;
    private boolean showCookie;
    private long nextQrPollAt;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
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
        accountProfile = account.cachedProfile().orElse(null);
    }

    @Override
    protected void init() {
        captureInputs();
        panelWidth = Math.max(1, Math.min(MAX_PANEL_WIDTH, width - 16));
        panelHeight = Math.max(1, Math.min(MAX_PANEL_HEIGHT, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        contentTop = panelY + 72;
        contentBottom = panelY + panelHeight - 34;

        addTabs();
        switch (page) {
            case SEARCH -> initSearchPage();
            case FAVORITES -> initFavoritesPage();
            case CONTROLS -> initControlsPage();
            case HUD -> initHudPage();
            case ACCOUNT -> initAccountPage();
        }
    }

    private void addTabs() {
        int gap = 5;
        int available = Math.max(1, panelWidth - EDGE * 2);
        int tabWidth = Math.max(30, (available - gap * 4) / 5);
        int x = panelX + EDGE;
        int y = panelY + 38;
        addTab("搜索", Page.SEARCH, x, y, tabWidth);
        x += tabWidth + gap;
        addTab("收藏", Page.FAVORITES, x, y, tabWidth);
        x += tabWidth + gap;
        addTab("播放", Page.CONTROLS, x, y, tabWidth);
        x += tabWidth + gap;
        addTab("界面", Page.HUD, x, y, tabWidth);
        x += tabWidth + gap;
        addTab("账号", Page.ACCOUNT, x, y,
            Math.max(30, panelX + panelWidth - EDGE - x));
    }

    private void addTab(String label, Page target, int x, int y, int width) {
        addButton(label, x, y, width, TAB_HEIGHT, page == target,
            () -> openPage(target));
    }

    private void initSearchPage() {
        int x = panelX + EDGE;
        int searchWidth = Math.max(50, panelWidth - EDGE * 2 - 86);
        searchBox = glassEditBox(x, contentTop, searchWidth, 23,
            "搜索视频、UP 主或关键词", 100);
        searchBox.setValue(searchText);
        searchButton = addButton(searching ? "搜索中" : "搜索",
            x + searchWidth + 6, contentTop, 80, 23, true,
            () -> startSearch(1));
        searchButton.active = !searching;
        setInitialFocus(searchBox);

        listX = x;
        listY = contentTop + 31;
        listWidth = panelWidth - EDGE * 2;
        listHeight = Math.max(0, contentBottom - listY);
        visibleResults = Math.max(1, Math.min(5, listHeight / ROW_HEIGHT));
        int count = Math.min(visibleResults,
            Math.max(0, searchResults.size() - searchOffset));
        for (int row = 0; row < count; row++) {
            BilibiliResolver.SearchResult result = searchResults.get(searchOffset + row);
            int rowY = listY + row * ROW_HEIGHT;
            addButton("播放", listX + listWidth - 58, rowY + 14,
                48, 24, false, () -> playUrl(result.pageUrl()));
        }

        GlassButton previous = addButton("<", panelX + EDGE,
            panelY + panelHeight - 27, 28, 20, false,
            () -> startSearch(searchPage - 1));
        previous.active = !searching && searchPage > 1;
        GlassButton next = addButton(">", panelX + EDGE + 34,
            panelY + panelHeight - 27, 28, 20, false,
            () -> startSearch(searchPage + 1));
        next.active = !searching && !searchResults.isEmpty();
    }

    private void initFavoritesPage() {
        if (!account.hasSession()) {
            addButton("登录 Bilibili", panelX + panelWidth / 2 - 72,
                contentTop + 62, 144, 24, true, () -> openPage(Page.ACCOUNT));
            return;
        }
        if (favoriteFolders.isEmpty() && !foldersLoading) {
            loadFavoriteFolders();
        }

        boolean wide = panelWidth >= 560;
        if (wide) {
            initWideFavoriteFolders();
        } else {
            initCompactFavoriteFolders();
        }
        initFavoriteVideos(wide ? panelX + 174 : panelX + EDGE,
            wide ? contentTop : contentTop + 31,
            wide ? panelWidth - 186 : panelWidth - EDGE * 2);
    }

    private void initWideFavoriteFolders() {
        int x = panelX + EDGE;
        int width = 148;
        int controlsHeight = 26;
        visibleFolderRows = Math.max(1,
            Math.min(10, (contentBottom - contentTop - controlsHeight) / 27));
        int count = Math.min(visibleFolderRows,
            Math.max(0, favoriteFolders.size() - folderOffset));
        for (int row = 0; row < count; row++) {
            FavoriteFolder folder = favoriteFolders.get(folderOffset + row);
            String label = fit(folder.title() + "  " + folder.mediaCount(), width - 14);
            addButton(label, x, contentTop + row * 27, width, 23,
                folder.id() == selectedFolderId, () -> selectFolder(folder));
        }
        int y = contentBottom - 22;
        GlassButton up = addButton("<", x, y, 30, 20, false,
            () -> moveFolderWindow(-visibleFolderRows));
        up.active = folderOffset > 0;
        GlassButton down = addButton(">", x + 35, y, 30, 20, false,
            () -> moveFolderWindow(visibleFolderRows));
        down.active = folderOffset + visibleFolderRows < favoriteFolders.size();
    }

    private void initCompactFavoriteFolders() {
        FavoriteFolder selected = selectedFolder();
        int x = panelX + EDGE;
        int width = panelWidth - EDGE * 2;
        GlassButton previous = addButton("<", x, contentTop, 28, 23, false,
            () -> moveSelectedFolder(-1));
        previous.active = favoriteFolders.size() > 1;
        addButton(selected == null ? "收藏夹" : fit(selected.title(), width - 80),
            x + 34, contentTop, Math.max(30, width - 68), 23, true,
            () -> {
                if (selected != null) loadFavoritePage(selected.id(), favoritePage);
            });
        GlassButton next = addButton(">", x + width - 28, contentTop,
            28, 23, false, () -> moveSelectedFolder(1));
        next.active = favoriteFolders.size() > 1;
    }

    private void initFavoriteVideos(int x, int y, int width) {
        listX = x;
        listY = y;
        listWidth = Math.max(1, width);
        listHeight = Math.max(0, contentBottom - y);
        visibleFavoriteRows = Math.max(1,
            Math.min(5, (listHeight - 24) / ROW_HEIGHT));
        List<FavoriteVideo> videos = favoritePageData == null
            ? List.of() : favoritePageData.videos();
        int count = Math.min(visibleFavoriteRows,
            Math.max(0, videos.size() - favoriteOffset));
        for (int row = 0; row < count; row++) {
            FavoriteVideo video = videos.get(favoriteOffset + row);
            int rowY = listY + row * ROW_HEIGHT;
            GlassButton play = addButton(video.available() ? "播放" : "失效",
                listX + listWidth - 58, rowY + 14, 48, 24, false,
                () -> playUrl(video.pageUrl()));
            play.active = video.available();
        }

        int yControls = contentBottom - 21;
        GlassButton previous = addButton("<", listX, yControls, 28, 20, false,
            () -> loadFavoritePage(selectedFolderId, favoritePage - 1));
        previous.active = !favoritesLoading && favoritePage > 1;
        GlassButton next = addButton(">", listX + 34, yControls, 28, 20, false,
            () -> loadFavoritePage(selectedFolderId, favoritePage + 1));
        next.active = !favoritesLoading && favoritePageData != null
            && favoritePageData.hasMore();
    }

    private void initControlsPage() {
        int left = panelX + EDGE;
        int available = Math.max(1, panelWidth - EDGE * 2);
        boolean dense = panelHeight < 300;
        int urlY = dense ? contentTop : contentTop + 12;
        urlBox = glassEditBox(left, urlY, available, 23,
            "Bilibili 或 HTTP/HTTPS 媒体链接", 8192);
        if (urlText.isBlank()) {
            String current = PlaybackSession.instance().snapshot().mediaUrl();
            if (current != null) urlText = current;
        }
        urlBox.setValue(urlText);

        int y = urlY + 28;
        if (dense) {
            addButtonRow(left, y, available,
                new String[]{"播放", "暂停/继续", "-10s", "+10s", "停止"},
                new Runnable[]{() -> send(Packets.PLAY, 0L, urlBox.getValue()),
                    this::togglePause,
                    () -> send(Packets.SEEK_RELATIVE, -10_000L, ""),
                    () -> send(Packets.SEEK_RELATIVE, 10_000L, ""),
                    () -> send(Packets.STOP, 0L, "")});
            y += 28;
            addButtonRow(left, y, available,
                new String[]{"音量 -", "音量 +",
                    PlaybackSession.instance().visible() ? "隐藏画面" : "显示画面", "翻转画面"},
                new Runnable[]{() -> changeVolume(-5), () -> changeVolume(5),
                    this::toggleVideo, this::flipVideo});
            y += 28;
            addButtonRow(left, y, available,
                new String[]{"角点 1", "角点 2", "清除屏幕"},
                new Runnable[]{() -> selectCorner(Packets.SCREEN_POS1),
                    () -> selectCorner(Packets.SCREEN_POS2),
                    () -> send(Packets.SCREEN_CLEAR, 0L, "")});
            return;
        }

        boolean compact = panelWidth < 520;
        y = contentTop + 48;
        if (compact) {
            addButtonRow(left, y, available,
                new String[]{"播放", "暂停", "停止"},
                new Runnable[]{() -> send(Packets.PLAY, 0L, urlBox.getValue()),
                    this::togglePause, () -> send(Packets.STOP, 0L, "")});
            y += 29;
            addButtonRow(left, y, available,
                new String[]{"-10s", "+10s", "翻转"},
                new Runnable[]{() -> send(Packets.SEEK_RELATIVE, -10_000L, ""),
                    () -> send(Packets.SEEK_RELATIVE, 10_000L, ""), this::flipVideo});
        } else {
            addButtonRow(left, y, available,
                new String[]{"播放", "暂停/继续", "-10s", "+10s", "停止"},
                new Runnable[]{() -> send(Packets.PLAY, 0L, urlBox.getValue()),
                    this::togglePause,
                    () -> send(Packets.SEEK_RELATIVE, -10_000L, ""),
                    () -> send(Packets.SEEK_RELATIVE, 10_000L, ""),
                    () -> send(Packets.STOP, 0L, "")});
        }

        y += compact ? 41 : 38;
        addButtonRow(left, y, available,
            new String[]{"音量 -", "音量 +",
                PlaybackSession.instance().visible() ? "隐藏画面" : "显示画面", "翻转画面"},
            new Runnable[]{() -> changeVolume(-5), () -> changeVolume(5),
                this::toggleVideo, this::flipVideo});

        y += 61;
        addButtonRow(left, y, available,
            new String[]{"角点 1", "角点 2", "清除屏幕"},
            new Runnable[]{() -> selectCorner(Packets.SCREEN_POS1),
                () -> selectCorner(Packets.SCREEN_POS2),
                () -> send(Packets.SCREEN_CLEAR, 0L, "")});
    }

    private void initHudPage() {
        HudSettings settings = HudSettings.instance();
        int left = panelX + EDGE;
        boolean dense = panelHeight < 300;
        if (dense) {
            int gap = 8;
            int available = Math.max(1, panelWidth - EDGE * 2);
            int columnWidth = Math.max(60, (available - gap) / 2);
            int right = left + columnWidth + gap;
            int y = contentTop;
            addRenderableWidget(new SettingSlider(left, y, columnWidth,
                "水平", 0.0, 1.0, settings.xPosition(), settings::setXPosition,
                value -> Math.round(value * 100) + "%"));
            addRenderableWidget(new SettingSlider(right, y, columnWidth,
                "宽度", HudSettings.MIN_WIDTH, HudSettings.MAX_WIDTH,
                settings.panelWidth(), settings::setPanelWidth,
                value -> Math.round(value) + " px"));
            y += 27;
            addRenderableWidget(new SettingSlider(left, y, columnWidth,
                "垂直", 0.0, 1.0, settings.yPosition(), settings::setYPosition,
                value -> Math.round(value * 100) + "%"));
            addRenderableWidget(new SettingSlider(right, y, columnWidth,
                "缩放", HudSettings.MIN_SCALE, HudSettings.MAX_SCALE,
                settings.scale(), settings::setScale,
                value -> String.format(Locale.ROOT, "%.0f%%", value * 100)));
            y += 29;
            addButton("恢复默认", left, y, columnWidth, 22, false, () -> {
                settings.reset();
                notice = "状态栏布局已恢复默认";
                rebuildWidgets();
            });
            previewX = right;
            previewY = y;
            previewWidth = Math.max(1, panelX + panelWidth - EDGE - previewX);
            previewHeight = Math.max(16, contentBottom - previewY);
            updatePreviewBounds();
            return;
        }

        boolean compact = panelWidth < 520;
        int sliderWidth = compact ? panelWidth - EDGE * 2
            : Math.max(150, (panelWidth - EDGE * 2 - 20) / 2);
        int y = contentTop + 10;
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
        addButton("恢复默认", left, y, sliderWidth, 22, false, () -> {
            settings.reset();
            notice = "状态栏布局已恢复默认";
            rebuildWidgets();
        });

        previewX = compact ? left : left + sliderWidth + 20;
        previewY = compact ? y + 34 : contentTop + 10;
        previewWidth = compact ? sliderWidth
            : Math.max(80, panelX + panelWidth - EDGE - previewX);
        previewHeight = Math.max(42, contentBottom - previewY);
        updatePreviewBounds();
    }

    private void initAccountPage() {
        if (account.hasSession()) {
            if (accountProfile == null && !accountLoading) loadAccountProfile();
            addButton("退出登录", panelX + panelWidth - EDGE - 88,
                contentTop + 3, 88, 23, false, this::logout);
            return;
        }

        AccountLayout layout = accountLayout();
        int showButtonWidth = layout.sideBySide() ? 48 : 62;

        cookieBox = glassEditBox(layout.formX(), layout.formY() + 25,
            Math.max(50, layout.formWidth() - showButtonWidth - 6),
            23, "粘贴 Cookie", 8192);
        cookieBox.setValue(cookieText);
        cookieBox.addFormatter((text, offset) -> FormattedCharSequence.forward(
            showCookie ? text : "*".repeat(text.length()), Style.EMPTY));
        addButton(showCookie ? "隐藏" : "显示",
            layout.formX() + layout.formWidth() - showButtonWidth,
            layout.formY() + 25, showButtonWidth, 23, false, () -> {
                captureInputs();
                showCookie = !showCookie;
                rebuildWidgets();
            });
        GlassButton login = addButton(accountLoading ? "验证中" : "Cookie 登录",
            layout.formX(), layout.formY() + 57, layout.formWidth(), 23,
            true, this::loginWithCookie);
        login.active = !accountLoading;
        GlassButton refresh = addButton(qrRequesting ? "生成中" : "刷新二维码",
            layout.qrX(), layout.qrY() + layout.qrSize()
                + (layout.sideBySide() ? 4 : 7),
            layout.qrSize(), 22, false, this::requestQrLogin);
        refresh.active = !qrRequesting && !accountLoading;

        if (qrLogin == null && qrState == null && !qrRequesting) {
            requestQrLogin();
        }
    }

    private EditBox glassEditBox(int x, int y, int width, int height,
                                 String hint, int maxLength) {
        EditBox box = new EditBox(font, x + 7, y, Math.max(1, width - 14), height,
            Component.literal(hint));
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setHint(Component.literal(hint));
        addRenderableWidget(box);
        return box;
    }

    private void addButtonRow(int x, int y, int width,
                              String[] labels, Runnable[] actions) {
        int gap = 6;
        int buttonWidth = Math.max(24, (width - gap * (labels.length - 1)) / labels.length);
        int currentX = x;
        for (int index = 0; index < labels.length; index++) {
            int actualWidth = index == labels.length - 1
                ? Math.max(24, x + width - currentX) : buttonWidth;
            addButton(labels[index], currentX, y, actualWidth, 23,
                index == 0 && "播放".equals(labels[index]), actions[index]);
            currentX += buttonWidth + gap;
        }
    }

    private GlassButton addButton(String label, int x, int y, int width, int height,
                                  boolean accented, Runnable action) {
        return addRenderableWidget(new GlassButton(label, x, y,
            Math.max(1, width), Math.max(1, height), accented, action));
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
        if (cookieBox != null) cookieText = cookieBox.getValue();
        searchBox = null;
        urlBox = null;
        cookieBox = null;
    }

    private void startSearch(int requestedPage) {
        String query = searchBox == null ? searchText : searchBox.getValue().trim();
        if (query.isBlank()) {
            notice = "请输入搜索关键词";
            return;
        }
        searchText = query;
        searchPage = Math.max(1, requestedPage);
        searchOffset = 0;
        searching = true;
        notice = "正在按 B 站综合排序搜索";
        if (searchButton != null) searchButton.active = false;
        int serial = ++searchSerial;
        SEARCH_EXECUTOR.execute(() -> {
            try {
                List<BilibiliResolver.SearchResult> found =
                    BilibiliResolver.searchVideos(query, searchPage);
                Minecraft.getInstance().execute(() -> finishSearch(serial, found, null));
            } catch (Throwable error) {
                Minecraft.getInstance().execute(() -> finishSearch(serial, List.of(), error));
            }
        });
    }

    private void finishSearch(int serial, List<BilibiliResolver.SearchResult> found,
                              Throwable error) {
        if (serial != searchSerial) return;
        searching = false;
        searchResults = found;
        notice = error == null ? "第 " + searchPage + " 页 · " + found.size()
            + " 个结果 · B 站综合排序" : "搜索失败: " + readable(error);
        if (minecraft.screen == this) rebuildWidgets();
    }

    private void loadFavoriteFolders() {
        foldersLoading = true;
        int serial = ++folderSerial;
        account.profile().thenCompose(profile -> {
            accountProfile = profile;
            return account.favoriteFolders();
        }).whenComplete((folders, error) -> Minecraft.getInstance().execute(() -> {
            if (serial != folderSerial) return;
            foldersLoading = false;
            if (error != null) {
                notice = "收藏读取失败: " + readable(error);
            } else {
                favoriteFolders = folders;
                if (!folders.isEmpty()) {
                    FavoriteFolder selected = folders.stream()
                        .filter(folder -> folder.id() == selectedFolderId)
                        .findFirst().orElse(folders.getFirst());
                    selectedFolderId = selected.id();
                    loadFavoritePage(selectedFolderId, 1);
                } else {
                    favoritePageData = null;
                    notice = "收藏夹为空";
                }
            }
            if (minecraft.screen == this) rebuildWidgets();
        }));
    }

    private void loadFavoritePage(long folderId, int requestedPage) {
        if (folderId <= 0L || favoritesLoading) return;
        favoritesLoading = true;
        favoritePage = Math.max(1, requestedPage);
        favoriteOffset = 0;
        int serial = ++favoriteSerial;
        account.favoriteVideos(folderId, favoritePage).whenComplete((loaded, error) ->
            Minecraft.getInstance().execute(() -> {
                if (serial != favoriteSerial || folderId != selectedFolderId) return;
                favoritesLoading = false;
                if (error != null) {
                    notice = "收藏视频读取失败: " + readable(error);
                } else {
                    favoritePageData = loaded;
                    notice = "第 " + loaded.page() + " 页 · 共 " + loaded.total() + " 个视频";
                }
                if (minecraft.screen == this) rebuildWidgets();
            }));
    }

    private void selectFolder(FavoriteFolder folder) {
        if (folder.id() == selectedFolderId && favoritePageData != null) return;
        favoriteSerial++;
        favoritesLoading = false;
        selectedFolderId = folder.id();
        favoritePageData = null;
        favoritePage = 1;
        favoriteOffset = 0;
        loadFavoritePage(folder.id(), 1);
        rebuildWidgets();
    }

    private void moveFolderWindow(int delta) {
        int maximum = Math.max(0, favoriteFolders.size() - visibleFolderRows);
        folderOffset = Math.max(0, Math.min(maximum, folderOffset + delta));
        rebuildWidgets();
    }

    private void moveSelectedFolder(int delta) {
        if (favoriteFolders.isEmpty()) return;
        int current = 0;
        for (int index = 0; index < favoriteFolders.size(); index++) {
            if (favoriteFolders.get(index).id() == selectedFolderId) {
                current = index;
                break;
            }
        }
        int next = Math.floorMod(current + delta, favoriteFolders.size());
        selectFolder(favoriteFolders.get(next));
    }

    private FavoriteFolder selectedFolder() {
        return favoriteFolders.stream()
            .filter(folder -> folder.id() == selectedFolderId).findFirst().orElse(null);
    }

    private void loadAccountProfile() {
        accountLoading = true;
        int serial = ++accountSerial;
        account.profile().whenComplete((profile, error) -> Minecraft.getInstance().execute(() -> {
            if (serial != accountSerial) return;
            accountLoading = false;
            if (error == null) {
                accountProfile = profile;
                notice = "账户已连接";
            } else {
                notice = "账户读取失败: " + readable(error);
            }
            if (minecraft.screen == this) rebuildWidgets();
        }));
    }

    private void requestQrLogin() {
        int serial = ++accountSerial;
        qrRequesting = true;
        qrPolling = false;
        qrState = null;
        qrLogin = null;
        closeQrTexture();
        notice = "正在生成二维码";
        account.requestQrLogin().whenComplete((login, error) ->
            Minecraft.getInstance().execute(() -> {
                if (serial != accountSerial || minecraft.screen != this) return;
                qrRequesting = false;
                if (error != null) {
                    qrState = QrState.ERROR;
                    notice = "二维码生成失败: " + readable(error);
                } else {
                    try {
                        qrTexture = QrTexture.create(login.url(), accountLayout().qrSize());
                        qrLogin = login;
                        qrState = QrState.WAITING_FOR_SCAN;
                        nextQrPollAt = System.currentTimeMillis() + 1_000L;
                        notice = "等待扫码";
                    } catch (Throwable textureError) {
                        qrState = QrState.ERROR;
                        notice = "二维码绘制失败: " + readable(textureError);
                    }
                }
                rebuildWidgets();
            }));
    }

    private void pollQrLogin() {
        if (qrLogin == null || qrPolling || qrState == QrState.EXPIRED
                || qrState == QrState.CONFIRMED || qrState == QrState.ERROR) return;
        qrPolling = true;
        int serial = accountSerial;
        account.pollQrLogin(qrLogin.key()).whenComplete((result, error) ->
            Minecraft.getInstance().execute(() -> finishQrPoll(serial, result, error)));
    }

    private void finishQrPoll(int serial, QrPoll result, Throwable error) {
        if (serial != accountSerial || minecraft.screen != this) return;
        qrPolling = false;
        if (error != null) {
            qrState = QrState.ERROR;
            notice = "扫码登录失败: " + readable(error);
        } else {
            qrState = result.state();
            notice = switch (result.state()) {
                case WAITING_FOR_SCAN -> "等待扫码";
                case WAITING_FOR_CONFIRMATION -> "已扫码，请在手机确认";
                case CONFIRMED -> "登录成功";
                case EXPIRED -> "二维码已过期";
                case ERROR -> "扫码状态异常: " + result.message();
            };
            if (result.state() == QrState.CONFIRMED) {
                accountProfile = account.cachedProfile().orElse(null);
                qrLogin = null;
                closeQrTexture();
                clearFavoriteState();
            } else if (result.state() == QrState.WAITING_FOR_SCAN
                    || result.state() == QrState.WAITING_FOR_CONFIRMATION) {
                nextQrPollAt = System.currentTimeMillis() + 2_000L;
            }
        }
        rebuildWidgets();
    }

    private void loginWithCookie() {
        String raw = cookieBox == null ? cookieText : cookieBox.getValue();
        if (raw == null || raw.isBlank()) {
            notice = "请粘贴 Cookie";
            return;
        }
        int serial = ++accountSerial;
        accountLoading = true;
        qrPolling = false;
        notice = "正在验证 Cookie";
        account.loginWithCookie(raw).whenComplete((profile, error) ->
            Minecraft.getInstance().execute(() -> {
                if (serial != accountSerial || minecraft.screen != this) return;
                accountLoading = false;
                if (error != null) {
                    notice = "Cookie 登录失败: " + readable(error);
                } else {
                    accountProfile = profile;
                    cookieText = "";
                    if (cookieBox != null) cookieBox.setValue("");
                    qrLogin = null;
                    qrState = QrState.CONFIRMED;
                    closeQrTexture();
                    clearFavoriteState();
                    notice = "登录成功";
                }
                rebuildWidgets();
            }));
    }

    private void logout() {
        accountSerial++;
        boolean removed = account.logout();
        accountProfile = null;
        cookieText = "";
        qrLogin = null;
        qrState = null;
        closeQrTexture();
        clearFavoriteState();
        notice = removed ? "已退出登录"
            : "已退出本次会话，但本地登录文件删除失败";
        rebuildWidgets();
    }

    private void clearFavoriteState() {
        folderSerial++;
        favoriteSerial++;
        foldersLoading = false;
        favoritesLoading = false;
        favoriteFolders = List.of();
        favoritePageData = null;
        selectedFolderId = 0L;
        favoritePage = 1;
        favoriteOffset = 0;
        folderOffset = 0;
    }

    private void playUrl(String url) {
        if (url == null || url.isBlank()) {
            notice = "视频不可播放";
            return;
        }
        send(Packets.PLAY, 0L, url);
    }

    private void togglePause() {
        send(PlaybackSession.instance().snapshot().paused()
            ? Packets.RESUME : Packets.PAUSE, 0L, "");
    }

    private void changeVolume(int delta) {
        PlaybackSession session = PlaybackSession.instance();
        session.setVolume(session.volume() + delta);
        notice = "本地音量 " + session.volume();
    }

    private void toggleVideo() {
        PlaybackSession.instance().toggleVisible();
        notice = PlaybackSession.instance().visible() ? "画面已显示" : "画面已隐藏";
        rebuildWidgets();
    }

    private void flipVideo() {
        PlaybackSession.instance().toggleFlip();
        notice = "画面方向已切换";
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
            notice = "请输入媒体链接或选择视频";
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
    public void tick() {
        super.tick();
        if (page == Page.ACCOUNT && qrLogin != null && !qrPolling
                && System.currentTimeMillis() >= nextQrPollAt) {
            pollQrLogin();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (page == Page.SEARCH && searchBox != null && searchBox.isFocused()
                && enter(event)) {
            startSearch(1);
            return true;
        }
        if (page == Page.ACCOUNT && cookieBox != null && cookieBox.isFocused()
                && enter(event)) {
            loginWithCookie();
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
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        if (super.mouseScrolled(mouseX, mouseY, horizontal, vertical)) return true;
        if (!inside(mouseX, mouseY, listX, listY, listWidth, listHeight)) return false;
        int direction = vertical < 0.0 ? 1 : -1;
        if (page == Page.SEARCH && searchResults.size() > visibleResults) {
            int maximum = searchResults.size() - visibleResults;
            int updated = Math.max(0, Math.min(maximum, searchOffset + direction));
            if (updated != searchOffset) {
                searchOffset = updated;
                rebuildWidgets();
            }
            return true;
        }
        if (page == Page.FAVORITES && favoritePageData != null
                && favoritePageData.videos().size() > visibleFavoriteRows) {
            int maximum = favoritePageData.videos().size() - visibleFavoriteRows;
            int updated = Math.max(0, Math.min(maximum, favoriteOffset + direction));
            if (updated != favoriteOffset) {
                favoriteOffset = updated;
                rebuildWidgets();
            }
            return true;
        }
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX,
                                   int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, BACKDROP_TOP, BACKDROP_BOTTOM);
        boolean liquidGlass = ReGlassCompat.renderSurface(graphics,
            panelX, panelY, panelWidth, panelHeight, 14f, false, false);
        if (!liquidGlass) {
            GlassUi.roundedPanel(graphics, panelX + 3, panelY + 5,
                panelWidth, panelHeight, 0x66000000);
            GlassUi.roundedPanel(graphics, panelX, panelY,
                panelWidth, panelHeight, GlassUi.PANEL);
            graphics.fillGradient(panelX + 5, panelY + 1,
                panelX + panelWidth - 5, panelY + 58, 0x26FFFFFF, 0x00FFFFFF);
        } else {
            graphics.fillGradient(panelX + 14, panelY + 1,
                panelX + panelWidth - 14, panelY + 50, 0x12FFFFFF, 0x00FFFFFF);
        }
        graphics.text(font, "LocalSync", panelX + EDGE, panelY + 14,
            GlassUi.TEXT, false);
        GlassUi.pill(graphics, panelX + EDGE, panelY + 27, 46, 3,
            GlassUi.ACCENT, 0x40FFFFFF);
        String state = fit(PlaybackSession.instance().statusText(),
            Math.max(30, panelWidth - 126));
        graphics.text(font, state, panelX + panelWidth - EDGE - font.width(state),
            panelY + 14, GlassUi.MUTED, false);

        switch (page) {
            case SEARCH -> drawSearchPage(graphics, mouseX, mouseY);
            case FAVORITES -> drawFavoritesPage(graphics, mouseX, mouseY);
            case CONTROLS -> drawControlsPage(graphics);
            case HUD -> drawHudPreview(graphics);
            case ACCOUNT -> drawAccountPage(graphics);
        }
        drawNotice(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSearchPage(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        drawInputShell(graphics, panelX + EDGE, contentTop,
            panelWidth - EDGE * 2 - 86, 23);
        int count = Math.min(visibleResults,
            Math.max(0, searchResults.size() - searchOffset));
        for (int row = 0; row < count; row++) {
            BilibiliResolver.SearchResult result = searchResults.get(searchOffset + row);
            drawVideoRow(graphics, mouseX, mouseY, row,
                result.bvid(), result.title(), result.author(), result.duration(),
                result.playCount(), result.coverUrl(), true);
        }
        if (searching) {
            centered(graphics, "正在搜索...", contentTop + 78, GlassUi.ACCENT);
        } else if (searchResults.isEmpty()) {
            centered(graphics, searchText.isBlank() ? "Bilibili 视频搜索" : "没有找到视频",
                contentTop + 78, GlassUi.MUTED);
        }
        String range = searchResults.isEmpty() ? "0"
            : (searchOffset + 1) + "-" + Math.min(searchResults.size(),
                searchOffset + visibleResults) + "/" + searchResults.size();
        graphics.text(font, "第 " + searchPage + " 页 · " + range + " · B站综合排序",
            panelX + EDGE + 72, panelY + panelHeight - 22, GlassUi.MUTED, false);
    }

    private void drawFavoritesPage(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!account.hasSession()) {
            centered(graphics, "尚未登录 Bilibili", contentTop + 30, GlassUi.MUTED);
            return;
        }
        if (foldersLoading && favoriteFolders.isEmpty()) {
            centered(graphics, "正在读取收藏夹...", contentTop + 55, GlassUi.ACCENT);
            return;
        }
        if (favoriteFolders.isEmpty()) {
            centered(graphics, "收藏夹为空", contentTop + 55, GlassUi.MUTED);
            return;
        }
        List<FavoriteVideo> videos = favoritePageData == null
            ? List.of() : favoritePageData.videos();
        int count = Math.min(visibleFavoriteRows,
            Math.max(0, videos.size() - favoriteOffset));
        for (int row = 0; row < count; row++) {
            FavoriteVideo video = videos.get(favoriteOffset + row);
            drawVideoRow(graphics, mouseX, mouseY, row,
                "fav:" + video.bvid(), video.title(), video.ownerName(),
                formatDuration(video.durationSeconds()), video.playCount(),
                video.coverUrl(), video.available());
        }
        if (favoritesLoading || favoritePageData == null) {
            centeredInList(graphics, "正在读取收藏视频...", GlassUi.ACCENT);
        } else if (videos.isEmpty()) {
            centeredInList(graphics, "这个收藏夹还没有视频", GlassUi.MUTED);
        }
        if (favoritePageData != null) {
            String range = videos.isEmpty() ? "0"
                : (favoriteOffset + 1) + "-" + Math.min(videos.size(),
                    favoriteOffset + visibleFavoriteRows) + "/" + videos.size();
            graphics.text(font, "第 " + favoritePage + " 页 · " + range,
                listX + 72, contentBottom - 16, GlassUi.MUTED, false);
        }
    }

    private void drawVideoRow(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                              int row, String cacheKey, String title, String author,
                              String duration, long playCount, String coverUrl,
                              boolean available) {
        int rowY = listY + row * ROW_HEIGHT;
        boolean hovered = inside(mouseX, mouseY, listX, rowY,
            listWidth, ROW_HEIGHT - 4);
        if (!ReGlassCompat.renderSurface(graphics, listX, rowY,
                listWidth, ROW_HEIGHT - 4, 8f, hovered, false)) {
            GlassUi.roundedPanel(graphics, listX, rowY,
                listWidth, ROW_HEIGHT - 4, hovered ? ROW_HOVER : ROW);
        }
        Identifier cover = BilibiliCoverCache.getOrRequest(cacheKey, coverUrl);
        if (cover != null) {
            graphics.blit(cover, listX + 4, rowY + 3,
                listX + 82, rowY + 47,
                0f, 1f, 0f, 1f);
        } else {
            GlassUi.roundedPanel(graphics, listX + 4, rowY + 3,
                78, 44, 0xB52E343E);
            graphics.centeredText(font, "BILI", listX + 43, rowY + 20,
                available ? GlassUi.ACCENT : GlassUi.MUTED);
        }
        int textX = listX + 91;
        int textWidth = Math.max(24, listWidth - 159);
        graphics.text(font, fit(title, textWidth), textX, rowY + 8,
            available ? GlassUi.TEXT : GlassUi.MUTED, false);
        String meta = author + "  ·  " + duration + "  ·  " + formatPlayCount(playCount);
        graphics.text(font, fit(meta, textWidth), textX, rowY + 28,
            GlassUi.MUTED, false);
    }

    private void drawControlsPage(GuiGraphicsExtractor graphics) {
        boolean dense = panelHeight < 300;
        drawInputShell(graphics, panelX + EDGE, dense ? contentTop : contentTop + 12,
            panelWidth - EDGE * 2, 23);
        graphics.text(font, fit(PlaybackSession.instance().statusText(),
            (panelWidth - EDGE * 2) / 2), panelX + EDGE, contentTop - 4,
            GlassUi.MUTED, false);
        String screen = fit(ScreenState.instance().statusText(),
            (panelWidth - EDGE * 2) / 2);
        graphics.text(font, screen, panelX + panelWidth - EDGE - font.width(screen),
            contentTop - 4, GlassUi.MUTED, false);
        if (dense) return;
        int dividerY = contentTop + (panelWidth < 520 ? 153 : 124);
        graphics.horizontalLine(panelX + EDGE, panelX + panelWidth - EDGE - 1,
            dividerY, GlassUi.BORDER_SOFT);
        graphics.text(font, "影院屏幕", panelX + EDGE, dividerY + 9,
            GlassUi.TEXT, false);
    }

    private void drawHudPreview(GuiGraphicsExtractor graphics) {
        updatePreviewBounds();
        if (panelHeight >= 300) {
            graphics.text(font, "状态栏布局", panelX + EDGE, contentTop - 4,
                GlassUi.TEXT, false);
            graphics.text(font, "预览", previewX, previewY - 12,
                GlassUi.MUTED, false);
        }
        GlassUi.roundedPanel(graphics, previewX, previewY,
            previewWidth, previewHeight, 0xA3090C11);
        boolean liquidGlass = ReGlassCompat.renderPanel(
            graphics, sampleX, sampleY, sampleWidth, sampleHeight);
        if (!liquidGlass) {
            GlassUi.roundedPanel(graphics, sampleX, sampleY,
                sampleWidth, sampleHeight, 0xD51A1E26);
        }
        if (sampleHeight >= 18) {
            GlassUi.pill(graphics, sampleX + 6, sampleY + 5,
                Math.min(7, sampleHeight - 8), Math.min(7, sampleHeight - 8),
                GlassUi.ACCENT, 0x62FFFFFF);
            String title = fit("LocalSync · 视频标题", Math.max(20, sampleWidth - 24));
            graphics.text(font, title, sampleX + 18, sampleY + 4,
                GlassUi.TEXT, false);
            int progressY = sampleY + sampleHeight - 7;
            graphics.fill(sampleX + 7, progressY, sampleX + sampleWidth - 7,
                progressY + 2, 0xAA343B47);
            graphics.fill(sampleX + 7, progressY,
                sampleX + 7 + Math.max(1, (sampleWidth - 14) * 2 / 5),
                progressY + 2, GlassUi.ACCENT);
        }
    }

    private void drawAccountPage(GuiGraphicsExtractor graphics) {
        if (account.hasSession()) {
            Profile profile = accountProfile;
            if (profile == null) {
                centered(graphics, accountLoading ? "正在读取账户..." : "账户信息暂不可用",
                    contentTop + 70, accountLoading ? GlassUi.ACCENT : GlassUi.MUTED);
                return;
            }
            int avatarX = panelX + EDGE;
            int avatarY = contentTop + 3;
            Identifier avatar = BilibiliCoverCache.getOrRequest(
                "avatar:" + profile.mid(), profile.avatarUrl());
            if (avatar != null) {
                graphics.blit(avatar, avatarX, avatarY,
                    avatarX + 52, avatarY + 52, 0f, 1f, 0f, 1f);
            } else {
                GlassUi.roundedPanel(graphics, avatarX, avatarY, 52, 52, GlassUi.PANEL_SOFT);
                graphics.centeredText(font, "UP", avatarX + 26, avatarY + 21,
                    GlassUi.ACCENT);
            }
            graphics.text(font, fit(profile.name(), panelWidth - 190),
                avatarX + 64, avatarY + 7, GlassUi.TEXT, false);
            String badge = "UID " + profile.mid() + "  ·  LV" + profile.level()
                + (profile.vip() ? "  ·  VIP" : "");
            graphics.text(font, fit(badge, panelWidth - 190),
                avatarX + 64, avatarY + 27, GlassUi.MUTED, false);
            graphics.text(font, "收藏页已连接此账户", avatarX,
                avatarY + 78, GlassUi.MUTED, false);
            return;
        }

        AccountLayout layout = accountLayout();

        graphics.text(font, layout.sideBySide() ? fit(qrStatusText(), layout.qrSize())
                : "扫码登录",
            layout.qrX(), contentTop + 5, GlassUi.TEXT, false);
        GlassUi.roundedPanel(graphics, layout.qrX() - 5, layout.qrY() - 5,
            layout.qrSize() + 10, layout.qrSize() + 10, 0xEAF3F4F6);
        if (qrTexture != null) {
            qrTexture.draw(graphics, layout.qrX(), layout.qrY(), layout.qrSize());
        } else {
            graphics.centeredText(font, qrRequesting ? "生成中..." : "二维码不可用",
                layout.qrX() + layout.qrSize() / 2,
                layout.qrY() + layout.qrSize() / 2 - 4,
                qrRequesting ? GlassUi.ACCENT : 0xFF535A65);
        }
        if (!layout.sideBySide()) {
            graphics.text(font, qrStatusText(), layout.qrX(),
                layout.qrY() + layout.qrSize() + 34,
                qrState == QrState.ERROR ? ERROR : GlassUi.MUTED, false);
        }

        graphics.text(font, "Cookie 登录", layout.formX(), layout.formY() + 5,
            GlassUi.TEXT, false);
        int showButtonWidth = layout.sideBySide() ? 48 : 62;
        drawInputShell(graphics, layout.formX(), layout.formY() + 25,
            Math.max(50, layout.formWidth() - showButtonWidth - 6), 23);
        graphics.text(font, "凭据仅保存在本机", layout.formX(), layout.formY() + 88,
            GlassUi.MUTED, false);
    }

    private AccountLayout accountLayout() {
        int contentHeight = Math.max(1, contentBottom - contentTop);
        boolean sideBySide = panelWidth >= 540 || panelHeight < 400;
        if (sideBySide) {
            int qrSize = Math.max(48, Math.min(176, contentHeight - 43));
            int qrX = panelWidth >= 540 ? panelX + 42 : panelX + EDGE;
            int qrY = contentTop + 17;
            int formX = panelWidth >= 540
                ? panelX + panelWidth / 2 : qrX + qrSize + 14;
            int formWidth = Math.max(56,
                panelX + panelWidth - EDGE - formX);
            return new AccountLayout(true, qrX, qrY, qrSize,
                formX, contentTop + 2, formWidth);
        }

        int qrSize = Math.max(96, Math.min(176, panelHeight - 236));
        int qrX = panelX + (panelWidth - qrSize) / 2;
        int qrY = contentTop + 25;
        return new AccountLayout(false, qrX, qrY, qrSize,
            panelX + EDGE, qrY + qrSize + 28, panelWidth - EDGE * 2);
    }

    private void drawInputShell(GuiGraphicsExtractor graphics, int x, int y,
                                int width, int height) {
        int actualWidth = Math.max(1, width);
        if (!ReGlassCompat.renderSurface(graphics, x, y, actualWidth, height,
                height * 0.5f, false, false)) {
            GlassUi.pill(graphics, x, y, actualWidth, height,
                0xB1262B34, GlassUi.BORDER);
            graphics.horizontalLine(x + 5, x + Math.max(6, width - 6), y + 1,
                0x2EFFFFFF);
        }
    }

    private void drawNotice(GuiGraphicsExtractor graphics) {
        if (notice.isBlank()) return;
        int available = Math.max(20, panelWidth - (page == Page.SEARCH
            || page == Page.FAVORITES ? 190 : 28));
        String fitted = fit(notice, available);
        int x = panelX + panelWidth - EDGE - font.width(fitted);
        graphics.text(font, fitted, x, panelY + panelHeight - 21,
            notice.contains("失败") || notice.contains("异常") ? ERROR : GlassUi.MUTED,
            false);
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

    private void closeQrTexture() {
        QrTexture texture = qrTexture;
        qrTexture = null;
        if (texture != null) texture.close();
    }

    private void centered(GuiGraphicsExtractor graphics, String text, int y, int color) {
        graphics.centeredText(font, fit(text, panelWidth - EDGE * 2),
            panelX + panelWidth / 2, y, color);
    }

    private void centeredInList(GuiGraphicsExtractor graphics, String text, int color) {
        graphics.centeredText(font, fit(text, Math.max(20, listWidth - 16)),
            listX + listWidth / 2, listY + 42, color);
    }

    private String fit(String value, int width) {
        return font.plainSubstrByWidth(value == null ? "" : value, Math.max(1, width));
    }

    private static boolean enter(KeyEvent event) {
        return event.key() == GLFW.GLFW_KEY_ENTER
            || event.key() == GLFW.GLFW_KEY_KP_ENTER;
    }

    private static boolean inside(double x, double y, int left, int top,
                                  int width, int height) {
        return width > 0 && height > 0 && x >= left && x < left + width
            && y >= top && y < top + height;
    }

    private static String formatPlayCount(long value) {
        if (value >= 100_000_000L) {
            return String.format(Locale.ROOT, "%.1f亿播放", value / 100_000_000.0);
        }
        if (value >= 10_000L) {
            return String.format(Locale.ROOT, "%.1f万播放", value / 10_000.0);
        }
        return Math.max(0L, value) + " 播放";
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        if (safe >= 3_600L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d",
                safe / 3_600L, safe / 60L % 60L, safe % 60L);
        }
        return String.format(Locale.ROOT, "%02d:%02d", safe / 60L, safe % 60L);
    }

    private static String readable(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
            ? current.getClass().getSimpleName() : message;
    }

    private record AccountLayout(boolean sideBySide, int qrX, int qrY, int qrSize,
                                 int formX, int formY, int formWidth) {}

    private String qrStatusText() {
        if (qrState == null) return qrRequesting ? "正在生成" : "等待生成";
        return switch (qrState) {
            case WAITING_FOR_SCAN -> "等待扫码";
            case WAITING_FOR_CONFIRMATION -> "等待手机确认";
            case CONFIRMED -> "登录成功";
            case EXPIRED -> "二维码已过期";
            case ERROR -> "二维码异常";
        };
    }

    @Override
    public void removed() {
        accountSerial++;
        searchSerial++;
        folderSerial++;
        favoriteSerial++;
        cookieText = "";
        if (cookieBox != null) cookieBox.setValue("");
        closeQrTexture();
        super.removed();
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
