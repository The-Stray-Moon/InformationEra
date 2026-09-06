package cc.sighs.informationera.client;

import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.KeyEvent;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 文件资源管理器窗口的全部交互逻辑：
 * 多标签页、窗口拖拽/缩放、目录树、排序与视图、面包屑、右键菜单、框选、
 * 剪切/复制/粘贴/删除/重命名/新建/撤销、拖拽移动复制、复选框、悬停元数据、键盘快捷键。
 */
public final class FileExplorerController {

    private static final double DEFAULT_WIDTH = 980;
    private static final double DEFAULT_HEIGHT = 600;
    private static final double MIN_WIDTH = 480;
    private static final double MIN_HEIGHT = 320;
    private static final double TITLEBAR_HEIGHT = 40;
    private static final double MENU_WIDTH = 230;

    private static final List<String> SIDEBAR_ROOTS = List.of(
            "home", "desktop", "downloads", "documents", "pictures", "music", "videos", "thispc", "recyclebin");

    private static final String[] COL_FIELDS = {"name", "modified", "type", "size"};
    private static final String[] COL_LABELS = {"名称", "修改日期", "类型", "大小"};

    private static FileExplorerController ACTIVE;

    /** 每个标签页独立的状态。 */
    private static final class Tab {
        String title = "文件资源管理器";
        String currentKey = "home";
        final Deque<String> backStack = new ArrayDeque<>();
        final Deque<String> forwardStack = new ArrayDeque<>();
        final Set<VirtualFileSystem.Entry> selected = new HashSet<>();
        final Set<String> expandedSidebar = new HashSet<>();
        String viewMode = "details";
        int iconSize = 40;
        String sortField = "name";
        boolean sortAsc = true;
        String searchQuery = "";
        int lastClickedIndex = -1;
    }

    private final VirtualFileSystem fs;
    private Document document;

    private Element window;
    private Element capture;
    private Element selectionRect;
    private Element tooltip;

    private final List<Tab> tabs = new ArrayList<>();
    private int activeTab = 0;

    private double windowX;
    private double windowY;
    private double windowWidth = DEFAULT_WIDTH;
    private double windowHeight = DEFAULT_HEIGHT;
    private boolean minimized = false;
    private boolean maximized = false;
    private double restoreX;
    private double restoreY;
    private double restoreW;
    private double restoreH;

    private String currentKey = "home";
    private final Deque<String> backStack = new ArrayDeque<>();
    private final Deque<String> forwardStack = new ArrayDeque<>();
    private final Set<VirtualFileSystem.Entry> selected = new HashSet<>();
    private final List<VirtualFileSystem.Entry> renderedEntryList = new ArrayList<>();
    private final Set<String> expandedSidebar = new HashSet<>();
    private final Set<String> pinned = new HashSet<>();
    private final List<Double> columnWidths = new ArrayList<>(List.of(320.0, 160.0, 120.0, 100.0));
    private final boolean[] columnVisible = new boolean[]{true, true, true, true};

    private String viewMode = "details";
    private int iconSize = 40;
    private String sortField = "name";
    private boolean sortAsc = true;
    private boolean showNav = true;
    private boolean checkboxes = false;
    private boolean previewOpen = false;
    private boolean compact = false;
    private boolean detailsPaneOpen = false;
    private String searchQuery = "";
    private int lastClickedIndex = -1;

    private final List<ClipItem> clipboard = new ArrayList<>();
    private boolean clipboardCut = false;
    private final Deque<Runnable> undoStack = new ArrayDeque<>();

    private String interaction = "none";
    private String resizeHandle = "";
    private double startMouseX;
    private double startMouseY;
    private double startWinX;
    private double startWinY;
    private double startWinW;
    private double startWinH;
    private double rubberStartX;
    private double rubberStartY;
    private double rubberLeft;
    private double rubberTop;
    private double rubberWidth;
    private double rubberHeight;
    private boolean contextMenuOpen = false;
    private int resizeColIndex = -1;
    private double colResizeStartX;
    private double colResizeStartWidth;

    private boolean pendingDrag = false;
    private boolean dragActive = false;
    private double dragStartX;
    private double dragStartY;
    private VirtualFileSystem.Entry dragEntry;
    private String dragSourceKey;

    private VirtualFileSystem.Entry renamingEntry;

    public FileExplorerController(VirtualFileSystem fs) {
        this.fs = fs;
    }

    public static FileExplorerController getActive() {
        return ACTIVE;
    }

    public boolean isOpen() {
        return document != null && document.isActive();
    }

    public void closeWindow() {
        closeContextMenu();
        if (ACTIVE == this) {
            ACTIVE = null;
        }
        if (document != null) {
            document.remove();
            document = null;
        }
    }

    /** 把窗口挂到 Overlay Document 上。 */
    public void mount(Document document) {
        this.document = document;
        this.window = document.getElementById("fe-window");
        this.capture = document.getElementById("fe-capture");
        this.selectionRect = document.getElementById("fe-selection-rect");
        this.tooltip = document.getElementById("fe-tooltip");

        tabs.clear();
        tabs.add(new Tab());
        activeTab = 0;

        initWindowGeometry();
        bindStatic();
        bindNativeInterception();
        bindKeyboard();
        renderTabs();
        renderNavigation();
        renderContent();
        renderNavigationState();
        renderBreadcrumb();
        applyNavVisibility();
        applyPreviewVisibility();
        updatePreview();
        applyDetailsPaneVisibility();
        updateDetailsPane();
        updateCommandButtons();
        updateNavButtons();
        setStatus("");

        ACTIVE = this;
    }

    private void initWindowGeometry() {
        Size viewport = document.getViewportSize();
        double vw = Math.max(MIN_WIDTH, viewport.width());
        double vh = Math.max(MIN_HEIGHT, viewport.height());
        windowWidth = Math.min(DEFAULT_WIDTH, vw);
        windowHeight = Math.min(DEFAULT_HEIGHT, vh);
        windowX = (vw - windowWidth) / 2;
        windowY = (vh - windowHeight) / 2;
        applyWindowGeometry();
    }

    private void applyWindowGeometry() {
        if (window == null) {
            return;
        }
        window.setInlineStyleProperty("left", px(windowX));
        window.setInlineStyleProperty("top", px(windowY));
        window.setInlineStyleProperty("width", px(windowWidth));
        window.setInlineStyleProperty("height", px(windowHeight));
    }

    // ---------- 静态元素与事件绑定 ----------

    private void bindStatic() {
        Element titlebar = document.getElementById("fe-titlebar");
        if (titlebar != null) {
            titlebar.addEventListener("mousedown", e -> {
                if (e instanceof MouseEvent me && me.button == 0) {
                    beginDrag(me.clientX, me.clientY);
                }
            });
            titlebar.addEventListener("contextmenu", e -> {
                if (e instanceof MouseEvent me) {
                    showWindowContextMenu(me.clientX, me.clientY);
                }
            });
        }

        for (String id : new String[]{"fe-min", "fe-max", "fe-close"}) {
            Element button = document.getElementById(id);
            if (button != null) {
                button.addEventListener("mousedown", e -> e.stopPropagation());
                button.addEventListener("contextmenu", e -> e.stopPropagation());
            }
        }

        Element min = document.getElementById("fe-min");
        if (min != null) {
            min.addEventListener("click", e -> toggleMinimize());
        }
        Element max = document.getElementById("fe-max");
        if (max != null) {
            max.addEventListener("click", e -> toggleMaximize());
        }
        Element close = document.getElementById("fe-close");
        if (close != null) {
            close.addEventListener("click", e -> closeWindow());
        }

        bindToolButton("fe-back", this::navigateBack);
        bindToolButton("fe-forward", this::navigateForward);
        bindToolButton("fe-up", this::navigateUp);

        bindToolButton("fe-cmd-cut", this::cut);
        bindToolButton("fe-cmd-copy", this::copy);
        bindToolButton("fe-cmd-paste", this::paste);
        bindToolButton("fe-cmd-rename", this::renamePrimary);
        bindToolButton("fe-cmd-delete", this::delete);
        bindToolButton("fe-preview", this::togglePreview);

        bindMenuButton("fe-new", this::showNewMenu);
        bindMenuButton("fe-sort", this::showSortMenu);
        bindMenuButton("fe-layout", this::showLayoutMenu);

        bindButtonHint("fe-min", "最小化");
        bindButtonHint("fe-max", "最大化");
        bindButtonHint("fe-close", "关闭");
        bindButtonHint("fe-back", "后退");
        bindButtonHint("fe-forward", "前进");
        bindButtonHint("fe-up", "向上");
        bindButtonHint("fe-new", "新建");
        bindButtonHint("fe-cmd-cut", "剪切");
        bindButtonHint("fe-cmd-copy", "复制");
        bindButtonHint("fe-cmd-paste", "粘贴");
        bindButtonHint("fe-cmd-rename", "重命名");
        bindButtonHint("fe-cmd-delete", "删除");
        bindButtonHint("fe-sort", "排序");
        bindButtonHint("fe-layout", "查看");
        bindButtonHint("fe-preview", "预览窗格");

        Element search = document.getElementById("fe-search");
        if (search != null) {
            search.addEventListener("input", e -> applySearch(search.getValue()));
            search.addEventListener("keyup", e -> applySearch(search.getValue()));
        }

        for (Element handle : document.querySelectorAll(".fe-resize")) {
            String direction = handle.getAttribute("data-resize");
            handle.addEventListener("mousedown", e -> {
                if (e instanceof MouseEvent me && me.button == 0) {
                    beginResize(direction, me.clientX, me.clientY);
                }
            });
        }

        if (capture != null) {
            capture.addEventListener("mousemove", e -> {
                if (e instanceof MouseEvent me) {
                    onInteractionMove(me);
                }
            });
            capture.addEventListener("mouseup", e -> endInteraction());
        }

        Element content = document.getElementById("fe-content");
        if (content != null) {
            content.addEventListener("mousedown", e -> {
                if (!(e instanceof MouseEvent me)) {
                    return;
                }
                if (me.button != 0) {
                    return;
                }
                Element target = e.target instanceof Element ? (Element) e.target : null;
                if (target == null || (target.closest(".fe-item") == null && target.closest("th") == null)) {
                    beginRubber(me.clientX, me.clientY);
                }
            });
            content.addEventListener("contextmenu", e -> {
                if (!(e instanceof MouseEvent me)) {
                    return;
                }
                Element target = e.target instanceof Element ? (Element) e.target : null;
                if (target == null || target.closest(".fe-item") == null) {
                    showContentContextMenu(me.clientX, me.clientY);
                }
            });
        }

        Element contextMenu = document.getElementById("fe-context-menu");
        if (contextMenu != null) {
            for (String type : new String[]{"mousedown", "mouseup", "click", "wheel", "contextmenu"}) {
                contextMenu.addEventListener(type, e -> {
                    if (e instanceof MouseEvent me) {
                        me.consumeNative();
                    }
                });
            }
        }

        Element renameInput = document.getElementById("fe-rename-input");
        if (renameInput != null) {
            renameInput.addEventListener("keydown", e -> {
                e.stopPropagation();
                if (e instanceof KeyEvent ke) {
                    if ("Enter".equals(ke.key)) {
                        commitRename();
                    } else if ("Escape".equals(ke.key)) {
                        cancelRename();
                    }
                }
            });
            renameInput.addEventListener("blur", e -> commitRename());
        }

        // 左键点在菜单外时关闭菜单。
        document.addEventListener("mousedown", e -> {
            if (!(e instanceof MouseEvent me)) {
                return;
            }
            if (me.button != 0 || !contextMenuOpen) {
                return;
            }
            Element menu = document.getElementById("fe-context-menu");
            Element target = e.target instanceof Element ? (Element) e.target : null;
            if (menu == null) {
                contextMenuOpen = false;
                return;
            }
            if (target == null || !menu.contains(target)) {
                closeContextMenu();
            }
        });

        // 拖拽移动/复制。
        document.addEventListener("mousemove", e -> {
            if (!(e instanceof MouseEvent me)) {
                return;
            }
            if (pendingDrag && !dragActive) {
                double dx = me.clientX - dragStartX;
                double dy = me.clientY - dragStartY;
                if (dx * dx + dy * dy > 25) {
                    dragActive = true;
                }
            }
        });
        document.addEventListener("mouseup", e -> {
            if (!(e instanceof MouseEvent me)) {
                return;
            }
            if (dragActive) {
                dropAt(me.clientX, me.clientY, me.controlKey);
            }
            pendingDrag = false;
            dragActive = false;
            dragEntry = null;
            dragSourceKey = null;
        });
    }

    private void bindToolButton(String id, Runnable action) {
        Element button = document.getElementById(id);
        if (button != null) {
            button.addEventListener("click", e -> {
                if ("true".equals(button.getAttribute("aria-disabled"))) {
                    return;
                }
                action.run();
            });
        }
    }

    private void bindMenuButton(String id, BiConsumer<Double, Double> opener) {
        Element button = document.getElementById(id);
        if (button != null) {
            button.addEventListener("click", e -> {
                Element.DOMRect rect = button.getBoundingClientRect();
                if (rect != null) {
                    opener.accept(rect.left, rect.bottom + 2);
                }
            });
        }
    }

    private void applySearch(String value) {
        String next = value == null ? "" : value;
        if (next.equals(searchQuery)) {
            return;
        }
        searchQuery = next;
        renderContent();
    }

    /** 去掉页面级 intercept meta 后，手动拦截窗口上的原生输入。 */
    private void bindNativeInterception() {
        if (window == null) {
            return;
        }
        for (String type : new String[]{"mousedown", "mouseup", "click", "wheel", "contextmenu"}) {
            window.addEventListener(type, e -> {
                if (e instanceof MouseEvent me) {
                    me.consumeNative();
                }
            });
        }
    }

    /** 键盘快捷键（当键盘事件能到达本 Overlay 时生效）。 */
    private void bindKeyboard() {
        document.addEventListener("keydown", e -> {
            if (!(e instanceof KeyEvent ke)) {
                return;
            }
            String key = ke.key;
            boolean ctrl = ke.controlKey;
            if (ctrl && "a".equalsIgnoreCase(key)) {
                selectAll();
            } else if (ctrl && "c".equalsIgnoreCase(key)) {
                copy();
            } else if (ctrl && "x".equalsIgnoreCase(key)) {
                cut();
            } else if (ctrl && "v".equalsIgnoreCase(key)) {
                paste();
            } else if (ctrl && "z".equalsIgnoreCase(key)) {
                undo();
            } else if ("Delete".equals(key)) {
                delete();
            } else if ("Enter".equals(key)) {
                openPrimary();
            } else if ("Backspace".equals(key)) {
                navigateUp();
            } else if ("F2".equals(key)) {
                renamePrimary();
            }
        });
    }

    // ---------- 多标签页 ----------

    private void renderTabs() {
        Element strip = document.getElementById("fe-tabstrip");
        if (strip == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            sb.append("<div class=\"fe-tab").append(i == activeTab ? " active" : "").append("\" data-tab=\"").append(i).append("\">");
            sb.append("<svg viewBox=\"0 0 24 24\" class=\"fe-tab-icon\"><path d=\"M3 7h6l2 2h10v11H3z\" fill=\"currentColor\"/></svg>");
            sb.append("<span class=\"fe-tab-title\">").append(escape(tab.title)).append("</span>");
            sb.append("<span class=\"fe-tab-close\" data-close=\"").append(i).append("\"><svg viewBox=\"0 0 12 12\"><line x1=\"2\" y1=\"2\" x2=\"10\" y2=\"10\" stroke=\"currentColor\" stroke-width=\"1.5\"/><line x1=\"10\" y1=\"2\" x2=\"2\" y2=\"10\" stroke=\"currentColor\" stroke-width=\"1.5\"/></svg></span>");
            sb.append("</div>");
        }
        sb.append("<div class=\"fe-tab-new\" data-new=\"1\"><svg viewBox=\"0 0 12 12\"><line x1=\"6\" y1=\"1\" x2=\"6\" y2=\"11\" stroke=\"currentColor\" stroke-width=\"1.5\"/><line x1=\"1\" y1=\"6\" x2=\"11\" y2=\"6\" stroke=\"currentColor\" stroke-width=\"1.5\"/></svg></div>");
        strip.setInnerHTML(sb.toString());

        for (Element tabEl : strip.querySelectorAll(".fe-tab[data-tab]")) {
            tabEl.addEventListener("mousedown", e -> e.stopPropagation());
            tabEl.addEventListener("click", e -> {
                String idx = tabEl.getAttribute("data-tab");
                if (idx != null) {
                    switchToTab(Integer.parseInt(idx));
                }
            });
        }
        for (Element closeEl : strip.querySelectorAll(".fe-tab-close[data-close]")) {
            closeEl.addEventListener("mousedown", e -> e.stopPropagation());
            closeEl.addEventListener("click", e -> {
                e.stopPropagation();
                String idx = closeEl.getAttribute("data-close");
                if (idx != null) {
                    closeTab(Integer.parseInt(idx));
                }
            });
        }
        Element newBtn = strip.querySelector(".fe-tab-new");
        if (newBtn != null) {
            newBtn.addEventListener("mousedown", e -> e.stopPropagation());
            newBtn.addEventListener("click", e -> newTab());
        }
    }

    private void saveActiveTab() {
        Tab tab = tabs.get(activeTab);
        tab.currentKey = currentKey;
        tab.backStack.clear();
        tab.backStack.addAll(backStack);
        tab.forwardStack.clear();
        tab.forwardStack.addAll(forwardStack);
        tab.selected.clear();
        tab.selected.addAll(selected);
        tab.expandedSidebar.clear();
        tab.expandedSidebar.addAll(expandedSidebar);
        tab.viewMode = viewMode;
        tab.iconSize = iconSize;
        tab.sortField = sortField;
        tab.sortAsc = sortAsc;
        tab.searchQuery = searchQuery;
        tab.lastClickedIndex = lastClickedIndex;
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        tab.title = folder != null ? folder.name : "文件资源管理器";
    }

    private void loadTab(Tab tab) {
        currentKey = tab.currentKey;
        backStack.clear();
        backStack.addAll(tab.backStack);
        forwardStack.clear();
        forwardStack.addAll(tab.forwardStack);
        selected.clear();
        selected.addAll(tab.selected);
        expandedSidebar.clear();
        expandedSidebar.addAll(tab.expandedSidebar);
        viewMode = tab.viewMode;
        iconSize = tab.iconSize;
        sortField = tab.sortField;
        sortAsc = tab.sortAsc;
        searchQuery = tab.searchQuery;
        lastClickedIndex = tab.lastClickedIndex;
    }

    private void switchToTab(int index) {
        if (index == activeTab || index < 0 || index >= tabs.size()) {
            return;
        }
        saveActiveTab();
        activeTab = index;
        loadTab(tabs.get(activeTab));
        renderAllAfterTabChange();
    }

    private void newTab() {
        saveActiveTab();
        tabs.add(new Tab());
        activeTab = tabs.size() - 1;
        loadTab(tabs.get(activeTab));
        renderAllAfterTabChange();
    }

    private void closeTab(int index) {
        if (tabs.size() <= 1) {
            closeWindow();
            return;
        }
        saveActiveTab();
        tabs.remove(index);
        if (index == activeTab) {
            activeTab = Math.min(index, tabs.size() - 1);
        } else if (index < activeTab) {
            activeTab--;
        }
        loadTab(tabs.get(activeTab));
        renderAllAfterTabChange();
    }

    private void renderAllAfterTabChange() {
        renderTabs();
        renderNavigation();
        renderContent();
        renderNavigationState();
        renderBreadcrumb();
        applyNavVisibility();
        updateNavButtons();
        updateStatusBar();
        Element search = document.getElementById("fe-search");
        if (search != null) {
            search.setValue(searchQuery);
        }
        setStatus("");
    }

    // ---------- 窗口拖拽 / 缩放 / 框选 ----------

    private void beginDrag(double mouseX, double mouseY) {
        interaction = "drag";
        startMouseX = mouseX;
        startMouseY = mouseY;
        startWinX = windowX;
        startWinY = windowY;
        activateCapture();
    }

    private void beginResize(String handle, double mouseX, double mouseY) {
        interaction = "resize";
        resizeHandle = handle;
        startMouseX = mouseX;
        startMouseY = mouseY;
        startWinX = windowX;
        startWinY = windowY;
        startWinW = windowWidth;
        startWinH = windowHeight;
        activateCapture();
    }

    private void beginRubber(double mouseX, double mouseY) {
        interaction = "rubber";
        rubberStartX = mouseX;
        rubberStartY = mouseY;
        if (selectionRect != null) {
            selectionRect.setInlineStyleProperty("display", "block");
        }
        updateRubber(mouseX, mouseY);
        activateCapture();
    }

    private void beginColumnResize(int col, double mouseX) {
        if (col < 0 || col >= columnWidths.size()) {
            return;
        }
        interaction = "colresize";
        resizeColIndex = col;
        colResizeStartX = mouseX;
        colResizeStartWidth = columnWidths.get(col);
        activateCapture();
    }

    private void applyColumnResize(double mouseX) {
        if (resizeColIndex < 0 || resizeColIndex >= columnWidths.size()) {
            return;
        }
        double dx = mouseX - colResizeStartX;
        double newWidth = Math.max(48.0, colResizeStartWidth + dx);
        columnWidths.set(resizeColIndex, newWidth);
        renderContent();
    }

    private void activateCapture() {
        if (capture != null) {
            capture.setInlineStyleProperty("pointer-events", "auto");
        }
    }

    private void deactivateCapture() {
        if (capture != null) {
            capture.setInlineStyleProperty("pointer-events", "none");
        }
    }

    private void onInteractionMove(MouseEvent me) {
        switch (interaction) {
            case "drag" -> {
                windowX = startWinX + (me.clientX - startMouseX);
                windowY = startWinY + (me.clientY - startMouseY);
                applyWindowGeometry();
            }
            case "resize" -> applyResize(me.clientX, me.clientY);
            case "colresize" -> applyColumnResize(me.clientX);
            case "rubber" -> updateRubber(me.clientX, me.clientY);
            default -> {
            }
        }
    }

    private void applyResize(double mouseX, double mouseY) {
        double dx = mouseX - startMouseX;
        double dy = mouseY - startMouseY;
        double x = startWinX;
        double y = startWinY;
        double w = startWinW;
        double h = startWinH;

        switch (resizeHandle) {
            case "e" -> w = startWinW + dx;
            case "s" -> h = startWinH + dy;
            case "w" -> {
                x = startWinX + dx;
                w = startWinW - dx;
            }
            case "n" -> {
                y = startWinY + dy;
                h = startWinH - dy;
            }
            case "ne" -> {
                y = startWinY + dy;
                h = startWinH - dy;
                w = startWinW + dx;
            }
            case "nw" -> {
                x = startWinX + dx;
                w = startWinW - dx;
                y = startWinY + dy;
                h = startWinH - dy;
            }
            case "se" -> {
                w = startWinW + dx;
                h = startWinH + dy;
            }
            case "sw" -> {
                x = startWinX + dx;
                w = startWinW - dx;
                h = startWinH + dy;
            }
            default -> {
            }
        }

        if (w < MIN_WIDTH) {
            if (hasWest(resizeHandle)) {
                x = startWinX + startWinW - MIN_WIDTH;
            }
            w = MIN_WIDTH;
        }
        if (h < MIN_HEIGHT) {
            if (hasNorth(resizeHandle)) {
                y = startWinY + startWinH - MIN_HEIGHT;
            }
            h = MIN_HEIGHT;
        }

        windowX = x;
        windowY = y;
        windowWidth = w;
        windowHeight = h;
        applyWindowGeometry();
    }

    private void updateRubber(double mouseX, double mouseY) {
        rubberLeft = Math.min(rubberStartX, mouseX);
        rubberTop = Math.min(rubberStartY, mouseY);
        rubberWidth = Math.abs(mouseX - rubberStartX);
        rubberHeight = Math.abs(mouseY - rubberStartY);
        if (selectionRect != null) {
            selectionRect.setInlineStyleProperty("left", px(rubberLeft));
            selectionRect.setInlineStyleProperty("top", px(rubberTop));
            selectionRect.setInlineStyleProperty("width", px(rubberWidth));
            selectionRect.setInlineStyleProperty("height", px(rubberHeight));
        }
    }

    private void endInteraction() {
        if ("rubber".equals(interaction)) {
            finishRubber();
        }
        interaction = "none";
        resizeHandle = "";
        resizeColIndex = -1;
        if (selectionRect != null) {
            selectionRect.setInlineStyleProperty("display", "none");
        }
        deactivateCapture();
    }

    private void finishRubber() {
        selected.clear();
        for (Element item : document.querySelectorAll(".fe-item")) {
            VirtualFileSystem.Entry entry = entryFor(item);
            if (entry == null) {
                continue;
            }
            Element.DOMRect rect = item.getBoundingClientRect();
            if (rect != null && intersects(rect, rubberLeft, rubberTop, rubberWidth, rubberHeight)) {
                selected.add(entry);
            }
        }
        updateSelectionVisuals();
        updateStatusBar();
    }

    private static boolean intersects(Element.DOMRect r, double x, double y, double w, double h) {
        return r.left < x + w && r.right > x && r.top < y + h && r.bottom > y;
    }

    private static boolean hasWest(String handle) {
        return "w".equals(handle) || "nw".equals(handle) || "sw".equals(handle);
    }

    private static boolean hasNorth(String handle) {
        return "n".equals(handle) || "ne".equals(handle) || "nw".equals(handle);
    }

    private void toggleMinimize() {
        if (!minimized) {
            restoreW = windowWidth;
            restoreH = windowHeight;
            minimized = true;
            window.getClassList().add("fe-minimized");
            windowHeight = TITLEBAR_HEIGHT;
        } else {
            minimized = false;
            window.getClassList().remove("fe-minimized");
            windowWidth = restoreW;
            windowHeight = restoreH;
        }
        applyWindowGeometry();
    }

    private void toggleMaximize() {
        Size viewport = document.getViewportSize();
        if (!maximized) {
            restoreX = windowX;
            restoreY = windowY;
            restoreW = windowWidth;
            restoreH = windowHeight;
            maximized = true;
            windowX = 0;
            windowY = 0;
            windowWidth = viewport.width();
            windowHeight = viewport.height();
        } else {
            maximized = false;
            windowX = restoreX;
            windowY = restoreY;
            windowWidth = restoreW;
            windowHeight = restoreH;
        }
        applyWindowGeometry();
    }

    // ---------- 拖拽移动/复制 ----------

    private void dropAt(double x, double y, boolean copy) {
        if (dragEntry == null || dragSourceKey == null) {
            return;
        }
        Element target = document.hitTest(new Position(x, y));
        if (target == null) {
            return;
        }
        Element folderEl = target.closest("[data-path]");
        if (folderEl == null) {
            return;
        }
        String targetKey = folderEl.getAttribute("data-path");
        if (targetKey == null) {
            return;
        }
        if (dragEntry.folder && dragEntry.childKey != null && targetKey.startsWith(dragEntry.childKey)) {
            setStatus("不能移动到自身内部");
            return;
        }
        if (targetKey.equals(dragSourceKey) && !copy) {
            return;
        }
        if (copy) {
            pushUndo(fs.duplicateInto(targetKey, dragEntry));
            setStatus("已复制到目标文件夹");
        } else {
            pushUndo(fs.move(dragSourceKey, targetKey, dragEntry));
            setStatus("已移动到目标文件夹");
        }
        afterMutation();
    }

    // ---------- 右键菜单（自绘，Ore 主题） ----------

    private record CtxEntry(int kind, String label, boolean danger, boolean checked, Runnable action) {
        static CtxEntry header(String label) {
            return new CtxEntry(0, label, false, false, null);
        }

        static CtxEntry separator() {
            return new CtxEntry(1, "", false, false, null);
        }

        static CtxEntry action(String label, Runnable action) {
            return new CtxEntry(2, label, false, false, action);
        }

        static CtxEntry actionChecked(String label, boolean checked, Runnable action) {
            return new CtxEntry(2, label, false, checked, action);
        }

        static CtxEntry actionDanger(String label, Runnable action) {
            return new CtxEntry(2, label, true, false, action);
        }
    }

    private record ClipItem(String sourceFolderKey, VirtualFileSystem.Entry entry) {
    }

    private void showWindowContextMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.action("新建标签页", this::newTab));
        entries.add(CtxEntry.action("最大化", this::toggleMaximize));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionDanger("关闭窗口", this::closeWindow));
        openContextMenu(x, y, entries);
    }

    private void showContentContextMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.action("刷新", this::renderContent));
        entries.add(CtxEntry.action("新建文件夹", this::createFolder));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action("粘贴", this::paste));
        entries.add(CtxEntry.action("撤销", this::undo));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action("全选", this::selectAll));
        entries.add(CtxEntry.action("反向选择", this::invertSelection));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action("属性", () -> setStatus(pathText(currentKey))));
        openContextMenu(x, y, entries);
    }

    private void showItemContextMenu(Element item, VirtualFileSystem.Entry entry, double x, double y) {
        if (entry == null) {
            return;
        }
        if (!selected.contains(entry)) {
            selected.clear();
            selected.add(entry);
            updateSelectionVisuals();
            updateStatusBar();
        }
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.header(entry.name));
        entries.add(CtxEntry.action("打开", () -> openEntry(entry)));
        entries.add(CtxEntry.action("重命名", () -> beginRename(item, entry)));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action("剪切", this::cut));
        entries.add(CtxEntry.action("复制", this::copy));
        if (entry.folder && entry.childKey != null) {
            String key = entry.childKey;
            entries.add(CtxEntry.action(pinned.contains(key) ? "从快速访问取消固定" : "固定到快速访问", () -> togglePin(key)));
        }
        entries.add(CtxEntry.actionDanger("删除", this::delete));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action("属性", () -> setStatus(entry.name + " —— " + entry.type + "，" + (entry.folder ? "文件夹" : formatSize(entry.size)))));
        openContextMenu(x, y, entries);
    }

    private void showNewMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.header("新建"));
        entries.add(CtxEntry.action("新建文件夹", this::createFolder));
        openContextMenu(x, y, entries);
    }

    private void showSortMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.actionChecked("名称", "name".equals(sortField), () -> setSort("name")));
        entries.add(CtxEntry.actionChecked("修改日期", "modified".equals(sortField), () -> setSort("modified")));
        entries.add(CtxEntry.actionChecked("类型", "type".equals(sortField), () -> setSort("type")));
        entries.add(CtxEntry.actionChecked("大小", "size".equals(sortField), () -> setSort("size")));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked("升序", sortAsc, () -> setSortAscending(true)));
        entries.add(CtxEntry.actionChecked("降序", !sortAsc, () -> setSortAscending(false)));
        openContextMenu(x, y, entries);
    }

    private void showLayoutMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.actionChecked("超大图标", "xlarge".equals(viewMode), () -> setViewMode("xlarge")));
        entries.add(CtxEntry.actionChecked("大图标", "large".equals(viewMode), () -> setViewMode("large")));
        entries.add(CtxEntry.actionChecked("中等图标", "medium".equals(viewMode), () -> setViewMode("medium")));
        entries.add(CtxEntry.actionChecked("小图标", "small".equals(viewMode), () -> setViewMode("small")));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked("列表", "list".equals(viewMode), () -> setViewMode("list")));
        entries.add(CtxEntry.actionChecked("详细信息", "details".equals(viewMode), () -> setViewMode("details")));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked("紧凑视图", compact, this::toggleCompact));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked("导航窗格", showNav, this::toggleNavPane));
        entries.add(CtxEntry.actionChecked("预览窗格", previewOpen, this::togglePreview));
        entries.add(CtxEntry.actionChecked("详细信息窗格", detailsPaneOpen, this::toggleDetailsPane));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked("复选框选择", checkboxes, this::toggleCheckboxes));
        openContextMenu(x, y, entries);
    }

    private void openContextMenu(double x, double y, List<CtxEntry> entries) {
        Element menu = document.getElementById("fe-context-menu");
        if (menu == null) {
            return;
        }
        List<Runnable> actions = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int estimatedHeight = 0;
        for (CtxEntry entry : entries) {
            switch (entry.kind) {
                case 0 -> {
                    sb.append("<div class=\"fe-ctx-header\">").append(escape(entry.label)).append("</div>");
                    estimatedHeight += 30;
                }
                case 1 -> {
                    sb.append("<div class=\"fe-ctx-sep\"></div>");
                    estimatedHeight += 10;
                }
                case 2 -> {
                    actions.add(entry.action);
                    int index = actions.size() - 1;
                    sb.append("<div class=\"fe-ctx-item")
                      .append(entry.danger ? " danger" : "")
                      .append("\" data-i=\"").append(index).append("\">");
                    sb.append("<span class=\"fe-ctx-mark\">").append(entry.checked ? "✓" : "").append("</span>");
                    sb.append("<span class=\"fe-ctx-label\">").append(escape(entry.label)).append("</span>");
                    sb.append("</div>");
                    estimatedHeight += 30;
                }
                default -> {
                }
            }
        }
        menu.setInnerHTML(sb.toString());

        Size viewport = document.getViewportSize();
        double left = Math.max(2, Math.min(x, viewport.width() - MENU_WIDTH - 4));
        double top = Math.max(2, Math.min(y, viewport.height() - estimatedHeight - 4));
        menu.setInlineStyleProperty("left", px(left));
        menu.setInlineStyleProperty("top", px(top));
        menu.setInlineStyleProperty("display", "block");
        contextMenuOpen = true;

        for (Element item : menu.querySelectorAll(".fe-ctx-item")) {
            item.addEventListener("click", e -> {
                closeContextMenu();
                String idx = item.getAttribute("data-i");
                if (idx == null) {
                    return;
                }
                try {
                    int index = Integer.parseInt(idx);
                    if (index >= 0 && index < actions.size() && actions.get(index) != null) {
                        actions.get(index).run();
                    }
                } catch (NumberFormatException ignored) {
                }
            });
        }
    }

    private void closeContextMenu() {
        contextMenuOpen = false;
        Element menu = document.getElementById("fe-context-menu");
        if (menu != null) {
            menu.setInnerHTML("");
            menu.setInlineStyleProperty("display", "none");
        }
    }

    // ---------- 重命名 ----------

    private void beginRename(Element item, VirtualFileSystem.Entry entry) {
        Element input = document.getElementById("fe-rename-input");
        if (input == null) {
            return;
        }
        renamingEntry = entry;
        Element.DOMRect rect = item.getBoundingClientRect();
        input.setValue(entry.name);
        input.setInlineStyleProperty("left", px(rect.left));
        input.setInlineStyleProperty("top", px(rect.top));
        input.setInlineStyleProperty("display", "block");
        input.focus();
    }

    private void commitRename() {
        Element input = document.getElementById("fe-rename-input");
        if (renamingEntry == null || input == null) {
            cancelRename();
            return;
        }
        Runnable undo = fs.rename(currentKey, renamingEntry, input.getValue());
        cancelRename();
        if (undo != null) {
            pushUndo(undo);
        }
        afterMutation();
        setStatus(undo != null ? "已重命名" : "重命名无效（为空或重名）");
    }

    private void cancelRename() {
        renamingEntry = null;
        Element input = document.getElementById("fe-rename-input");
        if (input != null) {
            input.setInlineStyleProperty("display", "none");
        }
    }

    // ---------- 文件操作 ----------

    private void cut() {
        if (selected.isEmpty()) {
            setStatus("未选中任何项目");
            return;
        }
        clipboard.clear();
        for (VirtualFileSystem.Entry entry : selected) {
            clipboard.add(new ClipItem(currentKey, entry));
        }
        clipboardCut = true;
        setStatus("已剪切 " + clipboard.size() + " 个项目");
        updateCommandButtons();
    }

    private void copy() {
        if (selected.isEmpty()) {
            setStatus("未选中任何项目");
            return;
        }
        clipboard.clear();
        for (VirtualFileSystem.Entry entry : selected) {
            clipboard.add(new ClipItem(null, entry));
        }
        clipboardCut = false;
        setStatus("已复制 " + clipboard.size() + " 个项目");
        updateCommandButtons();
    }

    private void paste() {
        if (clipboard.isEmpty()) {
            setStatus("剪贴板为空");
            return;
        }
        List<Runnable> undos = new ArrayList<>();
        int count = 0;
        if (clipboardCut) {
            for (ClipItem item : new ArrayList<>(clipboard)) {
                if (item.sourceFolderKey() != null && !item.sourceFolderKey().equals(currentKey)) {
                    undos.add(fs.move(item.sourceFolderKey(), currentKey, item.entry()));
                    count++;
                }
            }
            clipboard.clear();
            clipboardCut = false;
        } else {
            for (ClipItem item : clipboard) {
                undos.add(fs.duplicateInto(currentKey, item.entry()));
                count++;
            }
        }
        if (count == 0) {
            setStatus("无法粘贴到此位置");
            return;
        }
        pushUndo(() -> undos.forEach(Runnable::run));
        afterMutation();
        setStatus("已粘贴 " + count + " 个项目");
    }

    private void delete() {
        if (selected.isEmpty()) {
            setStatus("未选中任何项目");
            return;
        }
        List<Runnable> undos = new ArrayList<>();
        int count = 0;
        for (VirtualFileSystem.Entry entry : selected) {
            undos.add(fs.delete(currentKey, entry));
            count++;
        }
        pushUndo(() -> undos.forEach(Runnable::run));
        afterMutation();
        setStatus("已删除 " + count + " 个项目");
    }

    private void createFolder() {
        pushUndo(fs.createFolder(currentKey, "新建文件夹"));
        afterMutation();
        setStatus("已创建文件夹");
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            setStatus("没有可撤销的操作");
            return;
        }
        undoStack.pop().run();
        afterMutation();
        setStatus("已撤销");
    }

    private void pushUndo(Runnable undo) {
        undoStack.push(undo);
    }

    private void afterMutation() {
        selected.clear();
        lastClickedIndex = -1;
        renderContent();
        renderNavigation();
        renderNavigationState();
        renderBreadcrumb();
        updateNavButtons();
        updateStatusBar();
    }

    // ---------- 选择 ----------

    private void selectAll() {
        selected.clear();
        selected.addAll(renderedEntryList);
        updateSelectionVisuals();
        updateStatusBar();
    }

    private void invertSelection() {
        for (VirtualFileSystem.Entry entry : renderedEntryList) {
            if (!selected.remove(entry)) {
                selected.add(entry);
            }
        }
        updateSelectionVisuals();
        updateStatusBar();
    }

    private void openPrimary() {
        if (!selected.isEmpty()) {
            openEntry(selected.iterator().next());
        } else if (!renderedEntryList.isEmpty()) {
            openEntry(renderedEntryList.get(0));
        }
    }

    private void renamePrimary() {
        if (!renderedEntryList.isEmpty()) {
            int index = selected.isEmpty() ? 0 : indexOfEntry(selected.iterator().next());
            if (index >= 0 && index < renderedEntryList.size()) {
                Element item = findItemByIndex(index);
                if (item != null) {
                    beginRename(item, renderedEntryList.get(index));
                }
            }
        }
    }

    private int indexOfEntry(VirtualFileSystem.Entry entry) {
        return renderedEntryList.indexOf(entry);
    }

    private Element findItemByIndex(int index) {
        for (Element item : document.querySelectorAll(".fe-item")) {
            if (indexOf(item) == index) {
                return item;
            }
        }
        return null;
    }

    private void openEntry(VirtualFileSystem.Entry entry) {
        if (entry.folder && entry.childKey != null) {
            navigateTo(entry.childKey);
        } else if (!entry.folder) {
            setStatus("无法打开虚拟文件：" + entry.name);
        }
    }

    // ---------- 视图 / 排序 / 布局 ----------

    private void setViewMode(String mode) {
        viewMode = mode;
        iconSize = iconSizeFor(mode);
        renderContent();
    }

    private int iconSizeFor(String mode) {
        return switch (mode) {
            case "small" -> 40;
            case "medium" -> 64;
            case "large" -> 96;
            case "xlarge" -> 128;
            default -> 16;
        };
    }

    private void setSort(String field) {
        sortField = field;
        renderContent();
    }

    private void toggleSort(String field) {
        if (sortField.equals(field)) {
            sortAsc = !sortAsc;
        } else {
            sortField = field;
            sortAsc = true;
        }
        renderContent();
    }

    private void setSortAscending(boolean ascending) {
        sortAsc = ascending;
        renderContent();
    }

    private void toggleNavPane() {
        showNav = !showNav;
        applyNavVisibility();
    }

    private void toggleCheckboxes() {
        checkboxes = !checkboxes;
        renderContent();
    }

    private void toggleCompact() {
        compact = !compact;
        renderContent();
    }

    private void applyCompact() {
        Element content = document.getElementById("fe-content");
        if (content == null) {
            return;
        }
        if (compact) {
            content.getClassList().add("fe-compact");
        } else {
            content.getClassList().remove("fe-compact");
        }
    }

    private void toggleDetailsPane() {
        detailsPaneOpen = !detailsPaneOpen;
        applyDetailsPaneVisibility();
        updateDetailsPane();
    }

    private void applyDetailsPaneVisibility() {
        Element pane = document.getElementById("fe-details-pane");
        if (pane != null) {
            pane.setInlineStyleProperty("display", detailsPaneOpen ? "block" : "none");
        }
    }

    private void updateDetailsPane() {
        Element pane = document.getElementById("fe-details-pane");
        if (pane == null) {
            return;
        }
        if (selected.size() == 1) {
            VirtualFileSystem.Entry entry = selected.iterator().next();
            StringBuilder sb = new StringBuilder();
            sb.append("<div class=\"fe-details-name\">").append(escape(entry.name)).append("</div>");
            sb.append("<div class=\"fe-details-line\">类型：").append(escape(entry.type)).append("</div>");
            if (entry.folder) {
                sb.append("<div class=\"fe-details-line\">文件夹</div>");
            } else {
                sb.append("<div class=\"fe-details-line\">大小：").append(formatSize(entry.size)).append("</div>");
            }
            sb.append("<div class=\"fe-details-line\">修改：").append(escape(entry.modified)).append("</div>");
            sb.append("<div class=\"fe-details-line\">位置：").append(escape(pathText(currentKey))).append("</div>");
            pane.setInnerHTML(sb.toString());
        } else if (selected.size() > 1) {
            pane.setInnerHTML("<div class=\"fe-details-empty\">已选择 " + selected.size() + " 个项目</div>");
        } else {
            pane.setInnerHTML("<div class=\"fe-details-empty\">未选择任何项目</div>");
        }
    }

    private void applyNavVisibility() {
        Element nav = document.getElementById("fe-nav");
        if (nav != null) {
            nav.setInlineStyleProperty("display", showNav ? "block" : "none");
        }
    }

    private void togglePreview() {
        previewOpen = !previewOpen;
        applyPreviewVisibility();
        updatePreview();
    }

    private void applyPreviewVisibility() {
        Element pane = document.getElementById("fe-preview-pane");
        if (pane != null) {
            pane.setInlineStyleProperty("display", previewOpen ? "block" : "none");
        }
    }

    private void updatePreview() {
        Element pane = document.getElementById("fe-preview-pane");
        if (pane == null) {
            return;
        }
        if (selected.size() == 1) {
            VirtualFileSystem.Entry entry = selected.iterator().next();
            StringBuilder sb = new StringBuilder();
            sb.append("<div class=\"fe-preview-icon\">").append(iconFor(entry, 64)).append("</div>");
            sb.append("<div class=\"fe-preview-name\">").append(escape(entry.name)).append("</div>");
            sb.append("<div class=\"fe-preview-line\">类型：").append(escape(entry.type)).append("</div>");
            if (entry.folder) {
                sb.append("<div class=\"fe-preview-line\">文件夹</div>");
            } else {
                sb.append("<div class=\"fe-preview-line\">大小：").append(formatSize(entry.size)).append("</div>");
            }
            sb.append("<div class=\"fe-preview-line\">修改：").append(escape(entry.modified)).append("</div>");
            pane.setInnerHTML(sb.toString());
        } else if (selected.size() > 1) {
            pane.setInnerHTML("<div class=\"fe-preview-empty\">已选择 " + selected.size() + " 个项目</div>");
        } else {
            pane.setInnerHTML("<div class=\"fe-preview-empty\">未选择任何项目</div>");
        }
    }

    // ---------- 导航 ----------

    private void navigateTo(String key) {
        if (fs.folder(key) == null) {
            setStatus("路径不存在：" + key);
            return;
        }
        backStack.push(currentKey);
        forwardStack.clear();
        currentKey = key;
        afterNavigate();
    }

    private void navigateBack() {
        if (backStack.isEmpty()) {
            return;
        }
        forwardStack.push(currentKey);
        currentKey = backStack.pop();
        afterNavigate();
    }

    private void navigateForward() {
        if (forwardStack.isEmpty()) {
            return;
        }
        backStack.push(currentKey);
        currentKey = forwardStack.pop();
        afterNavigate();
    }

    private void navigateUp() {
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        if (folder != null && folder.parentKey != null) {
            navigateTo(folder.parentKey);
        }
    }

    private void afterNavigate() {
        selected.clear();
        lastClickedIndex = -1;
        searchQuery = "";
        Element search = document.getElementById("fe-search");
        if (search != null) {
            search.setValue("");
        }
        renderContent();
        renderNavigationState();
        renderBreadcrumb();
        updateNavButtons();
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        tabs.get(activeTab).title = folder != null ? folder.name : "文件资源管理器";
        renderTabs();
        setStatus("");
    }

    // ---------- 面包屑 ----------

    private void renderBreadcrumb() {
        Element breadcrumb = document.getElementById("fe-breadcrumb");
        if (breadcrumb == null) {
            return;
        }
        Deque<String> keys = new ArrayDeque<>();
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        while (folder != null) {
            keys.addFirst(folder.key);
            folder = folder.parentKey == null ? null : fs.folder(folder.parentKey);
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                sb.append("<span class=\"fe-crumb-sep\">&gt;</span>");
            }
            first = false;
            VirtualFileSystem.Folder f = fs.folder(key);
            String cls = key.equals(currentKey) ? "fe-crumb current" : "fe-crumb";
            sb.append("<div class=\"").append(cls).append("\" data-key=\"").append(escape(key)).append("\">")
              .append(escape(f.name)).append("</div>");
        }
        breadcrumb.setInnerHTML(sb.toString());
        for (Element crumb : breadcrumb.querySelectorAll(".fe-crumb[data-key]")) {
            String key = crumb.getAttribute("data-key");
            crumb.addEventListener("click", e -> {
                if (key != null && !key.equals(currentKey)) {
                    navigateTo(key);
                }
            });
        }
    }

    // ---------- 左侧导航树 ----------

    private void renderNavigation() {
        Element nav = document.getElementById("fe-nav");
        if (nav == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (!pinned.isEmpty()) {
            sb.append("<div class=\"fe-nav-title\">快速访问</div>");
            for (String key : pinned) {
                renderPinnedNode(sb, key);
            }
        }
        for (String root : SIDEBAR_ROOTS) {
            renderSidebarNode(sb, root, 0);
        }
        nav.setInnerHTML(sb.toString());

        for (Element item : document.querySelectorAll(".fe-nav-item")) {
            String key = item.getAttribute("data-path");
            item.addEventListener("click", e -> {
                if (key != null) {
                    navigateTo(key);
                }
            });
            Element chevron = item.querySelector(".fe-chevron");
            if (chevron != null && chevron.getAttribute("data-toggle") != null) {
                chevron.addEventListener("click", e -> {
                    e.stopPropagation();
                    String toggleKey = chevron.getAttribute("data-toggle");
                    if (toggleKey != null) {
                        toggleSidebar(toggleKey);
                    }
                });
            }
        }
    }

    private void renderPinnedNode(StringBuilder sb, String key) {
        VirtualFileSystem.Folder folder = fs.folder(key);
        if (folder == null) {
            return;
        }
        sb.append("<div class=\"fe-nav-item\" data-path=\"").append(escape(key))
          .append("\" style=\"padding-left:8px;\">");
        sb.append("<span class=\"fe-chevron fe-chevron-empty\"></span>");
        sb.append(folderIcon(16));
        sb.append("<span class=\"fe-nav-label\">").append(escape(folder.name)).append("</span>");
        sb.append("</div>");
    }

    private void renderSidebarNode(StringBuilder sb, String key, int depth) {
        VirtualFileSystem.Folder folder = fs.folder(key);
        if (folder == null) {
            return;
        }
        boolean expandable = !"home".equals(key) && fs.hasChildFolders(key);
        boolean expanded = expandable && expandedSidebar.contains(key);
        int pad = 8 + depth * 16;
        sb.append("<div class=\"fe-nav-item\" data-path=\"").append(escape(key))
          .append("\" style=\"padding-left:").append(pad).append("px;\">");
        if (expandable) {
            sb.append("<span class=\"fe-chevron\" data-toggle=\"").append(escape(key)).append("\">")
              .append(expanded ? chevronDown() : chevronRight()).append("</span>");
        } else {
            sb.append("<span class=\"fe-chevron fe-chevron-empty\"></span>");
        }
        sb.append(folderIcon(16));
        sb.append("<span class=\"fe-nav-label\">").append(escape(folder.name)).append("</span>");
        sb.append("</div>");

        if (expanded) {
            for (VirtualFileSystem.Entry child : fs.childFolders(key)) {
                renderSidebarNode(sb, child.childKey, depth + 1);
            }
        }
    }

    private void toggleSidebar(String key) {
        if (expandedSidebar.contains(key)) {
            expandedSidebar.remove(key);
        } else {
            expandedSidebar.add(key);
        }
        renderNavigation();
    }

    private void togglePin(String key) {
        if (pinned.contains(key)) {
            pinned.remove(key);
        } else {
            pinned.add(key);
        }
        renderNavigation();
        renderNavigationState();
    }

    private void renderNavigationState() {
        for (Element item : document.querySelectorAll(".fe-nav-item")) {
            String path = item.getAttribute("data-path");
            boolean active = path != null && (currentKey.equals(path) || currentKey.startsWith(path + "/"));
            if (active) {
                item.getClassList().add("selected");
            } else {
                item.getClassList().remove("selected");
            }
        }
    }

    // ---------- 内容区渲染 ----------

    private void renderContent() {
        Element content = document.getElementById("fe-content");
        if (content == null) {
            return;
        }
        renderedEntryList.clear();
        lastClickedIndex = -1;
        content.setInnerHTML(switch (viewMode) {
            case "details" -> buildTable();
            case "list" -> buildList();
            default -> buildGrid();
        });
        bindItemListeners();
        bindTableHeaders();
        updateSelectionVisuals();
        updateStatusBar();
        applyCompact();
    }

    private List<VirtualFileSystem.Entry> topLevelEntries() {
        List<VirtualFileSystem.Entry> entries = new ArrayList<>();
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        if (folder == null) {
            return entries;
        }
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase();
        for (VirtualFileSystem.Entry entry : folder.entries) {
            if (query.isEmpty() || entry.name.toLowerCase().contains(query)) {
                entries.add(entry);
            }
        }
        sortEntries(entries);
        return entries;
    }

    private void sortEntries(List<VirtualFileSystem.Entry> entries) {
        entries.sort((a, b) -> {
            if (a.folder != b.folder) {
                return a.folder ? -1 : 1;
            }
            int cmp;
            if (a.folder) {
                cmp = a.name.compareToIgnoreCase(b.name);
            } else {
                cmp = switch (sortField) {
                    case "modified" -> a.modified.compareTo(b.modified);
                    case "type" -> a.type.compareToIgnoreCase(b.type);
                    case "size" -> Long.compare(a.size, b.size);
                    default -> a.name.compareToIgnoreCase(b.name);
                };
            }
            if (cmp == 0) {
                cmp = a.name.compareToIgnoreCase(b.name);
            }
            return sortAsc ? cmp : -cmp;
        });
    }

    private String buildTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"table fe-table\" style=\"").append(tableWidthStyle()).append("\">");
        sb.append("<thead><tr style=\"").append(gridTemplateStyle()).append("\">");
        for (int col = 0; col < 4; col++) {
            if (columnVisible[col]) {
                sb.append(headerCell(COL_LABELS[col], COL_FIELDS[col], col));
            }
        }
        sb.append("</tr></thead><tbody>");
        for (VirtualFileSystem.Entry entry : topLevelEntries()) {
            renderTableRow(sb, entry);
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String headerCell(String label, String field, int col) {
        boolean active = sortField.equals(field);
        return "<th class=\"sortable" + (active ? " sort-active" : "") + "\" data-sort=\"" + field + "\" data-col=\"" + col + "\">"
                + label
                + "<span class=\"fe-col-resizer\" data-col=\"" + col + "\"></span></th>";
    }

    private void bindTableHeaders() {
        for (Element th : document.querySelectorAll(".fe-table th[data-sort]")) {
            String field = th.getAttribute("data-sort");
            th.addEventListener("click", e -> {
                if (field != null) {
                    toggleSort(field);
                }
            });
            th.addEventListener("contextmenu", e -> {
                e.stopPropagation();
                if (e instanceof MouseEvent me) {
                    showHeaderContextMenu(me.clientX, me.clientY);
                }
            });
        }
        for (Element handle : document.querySelectorAll(".fe-col-resizer")) {
            String colStr = handle.getAttribute("data-col");
            int col = -1;
            if (colStr != null) {
                try {
                    col = Integer.parseInt(colStr);
                } catch (NumberFormatException ignored) {
                }
            }
            int c = col;
            handle.addEventListener("mousedown", e -> {
                if (e instanceof MouseEvent me && me.button == 0) {
                    e.stopPropagation();
                    beginColumnResize(c, me.clientX);
                }
            });
            handle.addEventListener("click", e -> e.stopPropagation());
        }
    }

    private void renderTableRow(StringBuilder sb, VirtualFileSystem.Entry entry) {
        int index = renderedEntryList.size();
        renderedEntryList.add(entry);

        sb.append("<tr class=\"fe-item\" data-id=\"e").append(index).append("\"")
          .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
          .append(folderPathAttr(entry))
          .append(" style=\"").append(gridTemplateStyle()).append("\">");
        for (int col = 0; col < 4; col++) {
            if (columnVisible[col]) {
                sb.append(cellFor(entry, col, index));
            }
        }
        sb.append("</tr>");
    }

    private String cellFor(VirtualFileSystem.Entry entry, int col, int index) {
        switch (col) {
            case 0 -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<td><div class=\"fe-cell\">");
                if (checkboxes) {
                    sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
                }
                sb.append(iconFor(entry, 16))
                  .append("<span class=\"fe-name-text\">").append(escape(entry.name)).append("</span>")
                  .append("</div></td>");
                return sb.toString();
            }
            case 1 -> {
                return "<td>" + escape(entry.modified) + "</td>";
            }
            case 2 -> {
                return "<td>" + escape(entry.type) + "</td>";
            }
            case 3 -> {
                return "<td>" + (entry.folder ? "" : formatSize(entry.size)) + "</td>";
            }
            default -> {
                return "<td></td>";
            }
        }
    }

    private String gridTemplateStyle() {
        StringBuilder sb = new StringBuilder("grid-template-columns:");
        boolean first = true;
        for (int col = 0; col < 4; col++) {
            if (!columnVisible[col]) {
                continue;
            }
            if (!first) {
                sb.append(' ');
            }
            first = false;
            sb.append(Math.round(columnWidths.get(col))).append("px");
        }
        sb.append(';');
        return sb.toString();
    }

    private String tableWidthStyle() {
        double sum = 0;
        for (int col = 0; col < 4; col++) {
            if (columnVisible[col]) {
                sum += columnWidths.get(col);
            }
        }
        return "width:" + Math.round(sum) + "px;";
    }

    private int visibleColumnCount() {
        int n = 0;
        for (boolean visible : columnVisible) {
            if (visible) {
                n++;
            }
        }
        return n;
    }

    private void toggleColumn(int col) {
        if (columnVisible[col] && visibleColumnCount() <= 1) {
            setStatus("至少保留一列");
            return;
        }
        columnVisible[col] = !columnVisible[col];
        renderContent();
    }

    private void resetColumnWidths() {
        columnWidths.set(0, 320.0);
        columnWidths.set(1, 160.0);
        columnWidths.set(2, 120.0);
        columnWidths.set(3, 100.0);
        renderContent();
    }

    private void showHeaderContextMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        for (int col = 0; col < 4; col++) {
            int c = col;
            entries.add(CtxEntry.actionChecked(COL_LABELS[col], columnVisible[col], () -> toggleColumn(c)));
        }
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action("重置列宽", this::resetColumnWidths));
        openContextMenu(x, y, entries);
    }

    private String buildGrid() {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-grid\">");
        for (VirtualFileSystem.Entry entry : topLevelEntries()) {
            int index = renderedEntryList.size();
            renderedEntryList.add(entry);
            sb.append("<div class=\"fe-item fe-grid-item\" data-id=\"e").append(index).append("\"")
              .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
              .append(folderPathAttr(entry)).append(">");
            if (checkboxes) {
                sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
            }
            sb.append("<div class=\"fe-icon\">").append(iconFor(entry, iconSize)).append("</div>");
            sb.append("<div class=\"fe-name\">").append(escape(entry.name)).append("</div>");
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String buildList() {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-list\">");
        for (VirtualFileSystem.Entry entry : topLevelEntries()) {
            int index = renderedEntryList.size();
            renderedEntryList.add(entry);
            sb.append("<div class=\"fe-item fe-list-item\" data-id=\"e").append(index).append("\"")
              .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
              .append(folderPathAttr(entry)).append(">");
            if (checkboxes) {
                sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
            }
            sb.append("<span class=\"fe-icon\">").append(iconFor(entry, 16)).append("</span>");
            sb.append("<span class=\"fe-name-text\">").append(escape(entry.name)).append("</span>");
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String folderPathAttr(VirtualFileSystem.Entry entry) {
        return (entry.folder && entry.childKey != null) ? " data-path=\"" + escape(entry.childKey) + "\"" : "";
    }

    private void bindItemListeners() {
        for (Element item : document.querySelectorAll(".fe-item")) {
            item.addEventListener("click", e -> handleItemClick(item, e));
            item.addEventListener("contextmenu", e -> {
                e.stopPropagation();
                if (e instanceof MouseEvent me) {
                    showItemContextMenu(item, entryFor(item), me.clientX, me.clientY);
                }
            });
            item.addEventListener("mousedown", e -> {
                if (e instanceof MouseEvent me && me.button == 0) {
                    pendingDrag = true;
                    dragActive = false;
                    dragStartX = me.clientX;
                    dragStartY = me.clientY;
                    dragEntry = entryFor(item);
                    dragSourceKey = currentKey;
                }
            });
            item.addEventListener("mouseover", e -> {
                if (e instanceof MouseEvent me) {
                    showTooltip(entryFor(item), me.clientX, me.clientY);
                }
            });
            item.addEventListener("mousemove", e -> {
                if (e instanceof MouseEvent me) {
                    moveTooltip(me.clientX, me.clientY);
                }
            });
            item.addEventListener("mouseout", e -> hideTooltip());
        }

        for (Element check : document.querySelectorAll(".fe-check")) {
            check.addEventListener("click", e -> {
                e.stopPropagation();
                VirtualFileSystem.Entry entry = entryFor(check);
                if (entry == null) {
                    return;
                }
                if (!selected.remove(entry)) {
                    selected.add(entry);
                }
                updateSelectionVisuals();
                updateStatusBar();
            });
        }
    }

    private void handleItemClick(Element item, Event event) {
        if (event instanceof MouseEvent me && me.button != 0) {
            return;
        }
        VirtualFileSystem.Entry entry = entryFor(item);
        if (entry == null) {
            return;
        }
        int index = indexOf(item);
        boolean ctrl = event instanceof MouseEvent me && me.controlKey;
        boolean shift = event instanceof MouseEvent me && me.shiftKey;
        if (shift && lastClickedIndex >= 0) {
            selected.clear();
            int lo = Math.min(lastClickedIndex, index);
            int hi = Math.max(lastClickedIndex, index);
            for (int i = lo; i <= hi; i++) {
                selected.add(renderedEntryList.get(i));
            }
        } else if (ctrl) {
            if (!selected.remove(entry)) {
                selected.add(entry);
            }
        } else {
            selected.clear();
            selected.add(entry);
        }
        lastClickedIndex = index;

        if (event instanceof MouseEvent me && me.clickCount >= 2) {
            openEntry(entry);
            return;
        }
        updateSelectionVisuals();
        updateStatusBar();
    }

    private VirtualFileSystem.Entry entryFor(Element item) {
        int index = indexOf(item);
        return index >= 0 && index < renderedEntryList.size() ? renderedEntryList.get(index) : null;
    }

    private int indexOf(Element item) {
        String id = item.getAttribute("data-id");
        if (id == null || !id.startsWith("e")) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void updateSelectionVisuals() {
        for (Element item : document.querySelectorAll(".fe-item")) {
            VirtualFileSystem.Entry entry = entryFor(item);
            boolean isSelected = entry != null && selected.contains(entry);
            if (isSelected) {
                item.getClassList().add("selected");
            } else {
                item.getClassList().remove("selected");
            }
            item.setAttribute("aria-selected", isSelected ? "true" : "false");
        }
        for (Element check : document.querySelectorAll(".fe-check")) {
            VirtualFileSystem.Entry entry = entryFor(check);
            if (entry != null && selected.contains(entry)) {
                check.getClassList().add("checked");
            } else {
                check.getClassList().remove("checked");
            }
        }
        updateCommandButtons();
        updatePreview();
        updateDetailsPane();
    }

    // ---------- 悬停元数据 ----------

    private void showTooltip(VirtualFileSystem.Entry entry, double x, double y) {
        if (entry == null || tooltip == null) {
            return;
        }
        tooltip.setTextContent(metadataText(entry));
        moveTooltip(x, y);
        tooltip.setInlineStyleProperty("display", "block");
    }

    private void moveTooltip(double x, double y) {
        if (tooltip == null) {
            return;
        }
        tooltip.setInlineStyleProperty("left", px(x + 14));
        tooltip.setInlineStyleProperty("top", px(y + 18));
    }

    private String metadataText(VirtualFileSystem.Entry entry) {
        return entry.name
                + "\n类型：" + entry.type
                + "\n" + (entry.folder ? "文件夹" : "大小：" + formatSize(entry.size))
                + "\n修改：" + entry.modified;
    }

    private void hideTooltip() {
        if (tooltip != null) {
            tooltip.setInlineStyleProperty("display", "none");
        }
    }

    private void bindButtonHint(String id, String text) {
        Element button = document.getElementById(id);
        if (button == null) {
            return;
        }
        button.addEventListener("mouseover", e -> showButtonHint(button, text));
        button.addEventListener("mouseout", e -> hideTooltip());
    }

    private void showButtonHint(Element button, String text) {
        if (tooltip == null) {
            return;
        }
        tooltip.setTextContent(text);
        Element.DOMRect rect = button.getBoundingClientRect();
        tooltip.setInlineStyleProperty("left", px(rect.left));
        tooltip.setInlineStyleProperty("top", px(rect.bottom + 6));
        tooltip.setInlineStyleProperty("display", "block");
    }

    // ---------- 状态栏 / 按钮 ----------

    private void updateStatusBar() {
        setText("fe-item-count", renderedEntryList.size() + " 个项目");
        setText("fe-selection-count", selected.isEmpty() ? "" : "已选择 " + selected.size() + " 个项目" + selectedSizeText());
    }

    private String selectedSizeText() {
        long total = 0L;
        boolean hasFiles = false;
        for (VirtualFileSystem.Entry entry : selected) {
            if (!entry.folder) {
                total += entry.size;
                hasFiles = true;
            }
        }
        return hasFiles ? "  " + formatSize(total) : "";
    }

    private void updateNavButtons() {
        setButtonEnabled("fe-back", !backStack.isEmpty());
        setButtonEnabled("fe-forward", !forwardStack.isEmpty());
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        setButtonEnabled("fe-up", folder != null && folder.parentKey != null);
    }

    private void setButtonEnabled(String id, boolean enabled) {
        Element element = document.getElementById(id);
        if (element == null) {
            return;
        }
        element.setAttribute("aria-disabled", enabled ? "false" : "true");
        if (enabled) {
            element.getClassList().remove("disabled");
        } else {
            element.getClassList().add("disabled");
        }
    }

    private void updateCommandButtons() {
        boolean hasSelection = !selected.isEmpty();
        setButtonEnabled("fe-cmd-cut", hasSelection);
        setButtonEnabled("fe-cmd-copy", hasSelection);
        setButtonEnabled("fe-cmd-rename", hasSelection);
        setButtonEnabled("fe-cmd-delete", hasSelection);
        setButtonEnabled("fe-cmd-paste", !clipboard.isEmpty());
    }

    private void setText(String id, String text) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(text);
        }
    }

    private void setStatus(String message) {
        setText("fe-message", message);
    }

    private String pathText(String key) {
        Deque<String> names = new ArrayDeque<>();
        VirtualFileSystem.Folder folder = fs.folder(key);
        while (folder != null) {
            names.addFirst(folder.name);
            folder = folder.parentKey == null ? null : fs.folder(folder.parentKey);
        }
        return String.join(" > ", names);
    }

    // ---------- 工具方法 ----------

    private static String iconFor(VirtualFileSystem.Entry entry, int size) {
        if ("本地磁盘".equals(entry.type)) {
            return driveIcon(size);
        }
        return entry.folder ? folderIcon(size) : fileIcon(size);
    }

    private static String folderIcon(int size) {
        return "<svg viewBox=\"0 0 24 24\" style=\"width:" + size + "px;height:" + size + "px;color:var(--ore-gold)\"><path d=\"M3 7h6l2 2h10v11H3z\" fill=\"currentColor\"/></svg>";
    }

    private static String fileIcon(int size) {
        return "<svg viewBox=\"0 0 24 24\" style=\"width:" + size + "px;height:" + size + "px;color:#d0d1d4\"><path d=\"M6 2h8l4 4v16H6z\" fill=\"currentColor\"/></svg>";
    }

    private static String driveIcon(int size) {
        return "<svg viewBox=\"0 0 24 24\" style=\"width:" + size + "px;height:" + size + "px;color:#58a6d2\"><rect x=\"3\" y=\"8\" width=\"18\" height=\"8\" fill=\"currentColor\"/><circle cx=\"16\" cy=\"12\" r=\"2\" fill=\"#202124\"/></svg>";
    }

    private static String chevronRight() {
        return "<svg viewBox=\"0 0 24 24\"><polyline points=\"9,6 15,12 9,18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"/></svg>";
    }

    private static String chevronDown() {
        return "<svg viewBox=\"0 0 24 24\"><polyline points=\"6,9 12,15 18,9\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"/></svg>";
    }

    private static String px(double value) {
        return Math.round(value) + "px";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.2f GB", gb);
    }
}
