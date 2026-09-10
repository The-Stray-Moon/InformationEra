package cc.sighs.informationera.client;

import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.KeyEvent;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import net.minecraft.client.Minecraft;

import static cc.sighs.informationera.client.FileExplorerText.t;

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
    /** 线性视图（details/list/content）每行高度，用于虚拟滚动窗口计算。 */
    private static final double ROW_HEIGHT = 34.0;
    /** 虚拟滚动缓冲行数。 */
    private static final int VIRTUAL_BUFFER = 12;

    private static final List<String> SIDEBAR_ROOTS = List.of(
            "home", "desktop", "downloads", "documents", "pictures", "music", "videos",
            "onedrive", "thispc", "thispc/libraries", "thispc/network", "recyclebin");

    private static final String[] COL_FIELDS = {"name", "modified", "type", "size", "created", "author"};

    private static String[] columnLabels() {
        return new String[]{t("informationera.fe.name"), t("informationera.fe.modified"),
                t("informationera.fe.type"), t("informationera.fe.size"),
                t("informationera.fe.created"), t("informationera.fe.author")};
    }

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
        boolean foldersFirst = true;
        String searchQuery = "";
        int lastClickedIndex = -1;
    }

    private final VirtualFileSystem fs;
    private Document document;

    private Element window;
    private Element capture;
    private Element selectionRect;
    private Element tooltip;
    private Element dragGhost;

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
    private final List<Double> columnWidths = new ArrayList<>(List.of(320.0, 160.0, 120.0, 100.0, 140.0, 100.0));
    private final boolean[] columnVisible = new boolean[]{true, true, true, true, false, false};

    private String viewMode = "details";
    private int iconSize = 40;
    private String sortField = "name";
    private boolean sortAsc = true;
    private boolean foldersFirst = true;
    private boolean showNav = true;
    private boolean checkboxes = false;
    private boolean previewOpen = false;
    private int previewSize = 64;
    private boolean compact = false;
    private boolean detailsPaneOpen = false;
    private String groupBy = null;
    private final Set<String> collapsedGroups = new HashSet<>();
    private boolean showExtensions = true;
    private String searchQuery = "";
    private int lastClickedIndex = -1;
    private int selectionAnchor = -1;
    private final List<String> navHistory = new ArrayList<>();
    private long lastClickTime = 0L;
    private VirtualFileSystem.Entry lastClickEntry = null;


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
    private boolean rubberAdditive = false;
    private double scrollTop = 0.0;
    private boolean contextMenuOpen = false;
    private int contextFocusIndex = -1;
    private int resizeColIndex = -1;
    private double colResizeStartX;
    private double colResizeStartWidth;
    private double navWidth = 240;
    private double previewWidth = 240;

    private List<VirtualFileSystem.Entry> rightDragEntries = new ArrayList<>();
    private String rightDragSourceKey;
    private String rightDropTargetKey;

    private int tabDragIndex = -1;
    private boolean tabDragActive = false;
    private boolean tabDragMoved = false;
    private double tabDragStartX;
    private double tabDragStartY;

    private VirtualFileSystem.Entry renamingEntry;

    public FileExplorerController(VirtualFileSystem fs) {
        this.fs = fs;
    }

    public static FileExplorerController getActive() {
        return ACTIVE;
    }

    public static void setActive(FileExplorerController controller) {
        ACTIVE = controller;
    }

    public boolean isOpen() {
        return document != null && document.isActive();
    }

    Document document() {
        return document;
    }

    boolean isMinimized() {
        return minimized;
    }

    String currentKey() {
        return currentKey;
    }

    String currentFolderName() {
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        return folder != null ? folder.name : t("informationera.fe.title");
    }

    /** 供任务栏逻辑调用：最小化/恢复，返回操作后的最小化状态。 */
    boolean toggleMinimizeForTaskbar() {
        toggleMinimize();
        return minimized;
    }

    /** 供任务栏/最近路径跳转使用，直接导航到 key。 */
    void navigateToExternal(String key) {
        if (fs.folder(key) == null) {
            return;
        }
        currentKey = key;
        afterNavigate();
    }

    public void closeWindow() {
        closeContextMenu();
        FileExplorerWindow.unregister(this);
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
        this.dragGhost = document.getElementById("fe-drag-ghost");

        tabs.clear();
        tabs.add(new Tab());
        activeTab = 0;

        initWindowGeometry();
        bindStatic();
        bindNativeInterception();
        bindKeyboard();
        localizeStaticLabels();
        renderTabs();
        renderNavigation();
        renderContent();
        renderNavigationState();
        renderBreadcrumb();
        applyPaneWidths();
        applyNavVisibility();
        applyPreviewVisibility();
        updatePreview();
        applyDetailsPaneVisibility();
        updateDetailsPane();
        updateCommandButtons();
        updateNavButtons();
        updateSearchPlaceholder();
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

    /** 把 HTML 里静态命令栏文案替换为当前语言。 */
    private void localizeStaticLabels() {
        setCmdLabel("fe-new", t("informationera.fe.new"));
        setCmdLabel("fe-sort", t("informationera.fe.sort"));
        setCmdLabel("fe-layout", t("informationera.fe.layout"));
        setCmdLabel("fe-preview", t("informationera.fe.preview"));
    }

    private void setCmdLabel(String id, String text) {
        Element button = document.getElementById(id);
        if (button == null) {
            return;
        }
        Element label = button.querySelector(".fe-cmd-label");
        if (label != null) {
            label.setTextContent(text);
        }
    }

    // ---------- 静态元素与事件绑定 ----------

    private void bindStatic() {
        // 点击窗口任意位置即聚焦到最顶层。
        if (window != null) {
            window.addEventListener("mousedown", e -> {
                FileExplorerWindow.focus(FileExplorerController.this);
            });
        }

        Element titlebar = document.getElementById("fe-titlebar");
        if (titlebar != null) {
            titlebar.addEventListener("mousedown", e -> {
                if (e instanceof MouseEvent me && me.button == 0) {
                    if (me.clickCount >= 2) {
                        toggleMaximize();
                    } else {
                        beginDrag(me.clientX, me.clientY);
                    }
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
        bindAddressInput();
        bindHistory();

        bindToolButton("fe-cmd-cut", this::cut);
        bindToolButton("fe-cmd-copy", this::copy);
        bindToolButton("fe-cmd-paste", this::paste);
        bindToolButton("fe-cmd-rename", this::renamePrimary);
        bindToolButton("fe-cmd-delete", this::delete);
        bindToolButton("fe-preview", this::togglePreview);
        bindToolButton("fe-view-details", () -> setViewMode("details"));
        bindToolButton("fe-view-thumb", () -> setViewMode("large"));

        bindMenuButton("fe-new", this::showNewMenu);
        bindMenuButton("fe-sort", this::showSortMenu);
        bindMenuButton("fe-layout", this::showLayoutMenu);

        bindButtonHint("fe-min", t("informationera.fe.min"));
        bindButtonHint("fe-max", t("informationera.fe.max"));
        bindButtonHint("fe-close", t("informationera.fe.close"));
        bindButtonHint("fe-back", t("informationera.fe.back"));
        bindButtonHint("fe-forward", t("informationera.fe.forward"));
        bindButtonHint("fe-up", t("informationera.fe.up"));
        bindButtonHint("fe-new", t("informationera.fe.new"));
        bindButtonHint("fe-cmd-cut", t("informationera.fe.cut"));
        bindButtonHint("fe-cmd-copy", t("informationera.fe.copy"));
        bindButtonHint("fe-cmd-paste", t("informationera.fe.paste"));
        bindButtonHint("fe-cmd-rename", t("informationera.fe.rename"));
        bindButtonHint("fe-cmd-delete", t("informationera.fe.delete"));
        bindButtonHint("fe-sort", t("informationera.fe.sort"));
        bindButtonHint("fe-layout", t("informationera.fe.layout"));
        bindButtonHint("fe-preview", t("informationera.fe.preview"));
        bindButtonHint("fe-view-details", t("informationera.fe.details"));
        bindButtonHint("fe-view-thumb", t("informationera.fe.thumb"));

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

        for (Element resizer : document.querySelectorAll(".fe-pane-resizer")) {
            String handle = resizer.getAttribute("data-resize");
            resizer.addEventListener("mousedown", e -> {
                if (e instanceof MouseEvent me && me.button == 0) {
                    beginResize(handle, me.clientX, me.clientY);
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
                if (target == null || (target.closest(".fe-item") == null
                        && target.closest("th") == null
                        && target.closest(".fe-group-row") == null)) {
                    beginRubber(me.clientX, me.clientY, me.controlKey);
                }
            });
            content.addEventListener("contextmenu", e -> {
                if (FileExplorerWindow.consumeContextMenuSuppression()) {
                    return;
                }
                if (!(e instanceof MouseEvent me)) {
                    return;
                }
                Element target = e.target instanceof Element ? (Element) e.target : null;
                if (target == null || (target.closest(".fe-item") == null && target.closest(".fe-group-row") == null)) {
                    showContentContextMenu(me.clientX, me.clientY);
                }
            });
            content.addEventListener("wheel", e -> {
                if (e instanceof MouseEvent me) {
                    if (me.controlKey) {
                        // 阻止默认滚动 + 阻止向原生传递，只做布局切换。
                        e.preventDefault();
                        me.consumeNative();
                        handleContentWheel(me);
                    }
                }
            });
            content.addEventListener("scroll", e -> onContentScroll());
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
            // 文件拖拽（左/右键）由 FileExplorerWindow 的全局引擎处理，支持跨窗口。
            if (tabDragIndex >= 0 && !tabDragActive) {
                double dx = me.clientX - tabDragStartX;
                double dy = me.clientY - tabDragStartY;
                if (dx * dx + dy * dy > 25) {
                    tabDragActive = true;
                    tabDragMoved = true;
                    showTabGhost(tabs.get(tabDragIndex).title, me.clientX, me.clientY);
                }
            }
            if (tabDragActive && tabDragIndex >= 0) {
                moveDragGhost(me.clientX, me.clientY);
            }
        });
        document.addEventListener("mouseup", e -> {
            if (!(e instanceof MouseEvent me)) {
                return;
            }
            // 右键拖拽收尾同步处理，避免 contextmenu 与 tick 轮询竞争。
            Position screen = document.documentToScreenPosition(new Position(me.clientX, me.clientY));
            FileExplorerWindow.dragMouseUp(screen.x, screen.y);

            if (tabDragActive && tabDragIndex >= 0) {
                int target = tabIndexAt(me.clientX);
                if (target >= 0 && target != tabDragIndex) {
                    moveTab(tabDragIndex, target);
                }
            }
            tabDragIndex = -1;
            tabDragActive = false;
            hideDragGhost();
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
        if (document == null) {
            return;
        }
        // 整个窗口 document 都拦截鼠标事件，阻止传递给原版 Screen。
        for (String type : new String[]{"mousedown", "mouseup", "click", "dblclick", "wheel", "contextmenu", "mousemove"}) {
            document.addEventListener(type, e -> {
                if (e instanceof MouseEvent me) {
                    // 输入框需要原生鼠标事件来定位光标，不拦截。
                    if (e.target instanceof Element target && target.closest("input") != null) {
                        return;
                    }
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
            boolean alt = ke.altKey;
            boolean shift = ke.shiftKey;
            // 焦点在文本输入框时，只让输入框自己处理按键，不触发导航/删除/选择。
            if (isTextInputFocused()) {
                return;
            }
            if (ctrl && shift && "n".equalsIgnoreCase(key)) {
                createFolder();
            } else if (ctrl && "t".equalsIgnoreCase(key)) {
                newTab();
            } else if (ctrl && "w".equalsIgnoreCase(key)) {
                closeTab(activeTab);
            } else if (ctrl && "n".equalsIgnoreCase(key)) {
                FileExplorerWindow.open();
            } else if (ctrl && "a".equalsIgnoreCase(key)) {
                selectAll();
            } else if (ctrl && "c".equalsIgnoreCase(key)) {
                copy();
            } else if (ctrl && "x".equalsIgnoreCase(key)) {
                cut();
            } else if (ctrl && "v".equalsIgnoreCase(key)) {
                paste();
            } else if (ctrl && "z".equalsIgnoreCase(key)) {
                undo();
            } else if (ctrl && "y".equalsIgnoreCase(key)) {
                redo();
            } else if (ctrl && ("f".equalsIgnoreCase(key) || "e".equalsIgnoreCase(key))) {
                focusSearch();
            } else if ("F5".equalsIgnoreCase(key) || (ctrl && "r".equalsIgnoreCase(key))) {
                renderContent();
            } else if (alt && ("ArrowLeft".equalsIgnoreCase(key) || "Left".equalsIgnoreCase(key))) {
                navigateBack();
            } else if (alt && ("ArrowRight".equalsIgnoreCase(key) || "Right".equalsIgnoreCase(key))) {
                navigateForward();
            } else if (alt && ("ArrowUp".equalsIgnoreCase(key) || "Up".equalsIgnoreCase(key))) {
                navigateUp();
            } else if (alt && ("Enter".equalsIgnoreCase(key))) {
                showProperties();
            } else if (shift && "F10".equalsIgnoreCase(key)) {
                showContextMenuAtCurrent();
            } else if ("ArrowDown".equalsIgnoreCase(key) || "Down".equalsIgnoreCase(key)) {
                moveSelection(1, shift);
            } else if ("ArrowUp".equalsIgnoreCase(key) || "Up".equalsIgnoreCase(key)) {
                moveSelection(-1, shift);
            } else if ("ArrowLeft".equalsIgnoreCase(key) || "Left".equalsIgnoreCase(key)) {
                moveSelection(-1, shift);
            } else if ("ArrowRight".equalsIgnoreCase(key) || "Right".equalsIgnoreCase(key)) {
                moveSelection(1, shift);
            } else if ("Home".equalsIgnoreCase(key)) {
                moveSelectionTo(0, shift);
            } else if ("End".equalsIgnoreCase(key)) {
                moveSelectionTo(renderedEntryList.size() - 1, shift);
            } else if ("Delete".equals(key) && !ctrl && !alt) {
                delete();
            } else if ("Enter".equals(key) && !shift && !ctrl && !alt) {
                openPrimary();
            } else if ("Backspace".equals(key) && !shift && !ctrl && !alt) {
                navigateBack();
            } else if ("F2".equals(key) && !shift && !ctrl && !alt) {
                renamePrimary();
            }
        });
    }

    private boolean isTextInputFocused() {
        Element active = document.getActiveElement();
        if (active == null) {
            return false;
        }
        return active == document.getElementById("fe-search")
                || active == document.getElementById("fe-rename-input")
                || active == document.getElementById("fe-address-input");
    }

    private void focusSearch() {
        Element search = document.getElementById("fe-search");
        if (search != null) {
            search.focus();
        }
    }

    // ---------- 地址栏历史记录 ----------

    private void bindHistory() {
        Element button = document.getElementById("fe-history-btn");
        if (button == null) {
            return;
        }
        button.addEventListener("click", e -> {
            Element.DOMRect rect = button.getBoundingClientRect();
            if (rect != null) {
                showHistoryMenu(rect.left, rect.bottom + 2);
            }
        });
    }

    private void showHistoryMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.header(t("informationera.fe.recent")));
        if (navHistory.isEmpty()) {
            entries.add(CtxEntry.action(t("informationera.fe.no_history"), () -> {
            }));
        } else {
            for (String key : navHistory) {
                VirtualFileSystem.Folder folder = fs.folder(key);
                if (folder == null) {
                    continue;
                }
                entries.add(CtxEntry.action(pathText(key), () -> navigateTo(key)));
            }
        }
        openContextMenu(x, y, entries);
    }

    // ---------- 地址栏编辑 ----------

    private void bindAddressInput() {
        Element addressbar = document.getElementById("fe-addressbar");
        Element input = document.getElementById("fe-address-input");
        Element breadcrumb = document.getElementById("fe-breadcrumb");
        if (addressbar == null || input == null) {
            return;
        }
        // 点击面包屑空白处进入编辑。
        if (breadcrumb != null) {
            breadcrumb.addEventListener("mousedown", e -> {
                if (!(e instanceof MouseEvent me) || me.button != 0) {
                    return;
                }
                Element target = e.target instanceof Element ? (Element) e.target : null;
                if (target != null && target.closest(".fe-crumb") != null) {
                    return;
                }
                beginAddressEdit();
            });
        }
        input.addEventListener("keydown", e -> {
            e.stopPropagation();
            if (e instanceof KeyEvent ke && "Enter".equals(ke.key)) {
                commitAddressEdit();
            } else if (e instanceof KeyEvent ke && "Escape".equals(ke.key)) {
                cancelAddressEdit();
            }
        });
        input.addEventListener("blur", e -> commitAddressEdit());
    }

    private void beginAddressEdit() {
        Element addressbar = document.getElementById("fe-addressbar");
        Element input = document.getElementById("fe-address-input");
        if (addressbar == null || input == null) {
            return;
        }
        input.setValue(pathText(currentKey));
        addressbar.getClassList().add("editing");
        input.focus();
    }

    private void commitAddressEdit() {
        Element addressbar = document.getElementById("fe-addressbar");
        Element input = document.getElementById("fe-address-input");
        if (addressbar == null || input == null) {
            return;
        }
        if (!addressbar.getClassList().contains("editing")) {
            return;
        }
        addressbar.getClassList().remove("editing");
        String raw = input.getValue();
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        navigateByPath(raw.trim());
    }

    private void cancelAddressEdit() {
        Element addressbar = document.getElementById("fe-addressbar");
        if (addressbar != null) {
            addressbar.getClassList().remove("editing");
        }
    }

    /** 输入 key 或完整路径（用 > / \ 分隔）解析并导航。 */
    private void navigateByPath(String raw) {
        String key = fs.resolve(raw);
        if (key == null) {
            key = resolvePath(raw);
        }
        if (key == null) {
            setStatus(t("informationera.fe.status_path_not_found", raw));
            return;
        }
        if (key.equals(currentKey)) {
            return;
        }
        navigateTo(key);
    }

    private String resolvePath(String raw) {
        // 用 / \ > 任意组合分隔。
        String[] parts = raw.replace('\\', '/').replace('>', '/').split("/");
        List<String> names = new ArrayList<>();
        for (String part : parts) {
            String t = part.trim();
            if (!t.isEmpty() && !".".equals(t)) {
                names.add(t);
            }
        }
        if (names.isEmpty()) {
            return null;
        }

        String key;
        String first = names.get(0);
        if (first.equalsIgnoreCase("此电脑") || first.equalsIgnoreCase("我的电脑") || first.equalsIgnoreCase("thispc")) {
            key = "thispc";
        } else if (first.equalsIgnoreCase("主文件夹") || first.equalsIgnoreCase("home")) {
            key = "home";
        } else if (first.equalsIgnoreCase("回收站") || first.equalsIgnoreCase("recyclebin")) {
            key = "recyclebin";
        } else if (first.equalsIgnoreCase("桌面") || first.equalsIgnoreCase("desktop")) {
            key = "desktop";
        } else if (first.equalsIgnoreCase("下载") || first.equalsIgnoreCase("downloads")) {
            key = "downloads";
        } else if (first.equalsIgnoreCase("文档") || first.equalsIgnoreCase("documents")) {
            key = "documents";
        } else if (first.equalsIgnoreCase("图片") || first.equalsIgnoreCase("pictures")) {
            key = "pictures";
        } else if (first.equalsIgnoreCase("音乐") || first.equalsIgnoreCase("music")) {
            key = "music";
        } else if (first.equalsIgnoreCase("视频") || first.equalsIgnoreCase("videos")) {
            key = "videos";
        } else if (first.equalsIgnoreCase("onedrive")) {
            key = "onedrive";
        } else {
            // 相对当前目录的简单名。
            String resolved = fs.resolve(first);
            if (resolved != null) {
                key = resolved;
            } else {
                key = findChild(currentKey, first);
            }
        }

        for (int i = 1; key != null && i < names.size(); i++) {
            String name = names.get(i);
            if ("..".equals(name)) {
                VirtualFileSystem.Folder parent = fs.folder(key);
                key = parent == null ? null : parent.parentKey;
            } else {
                key = findChild(key, name);
            }
        }
        return key;
    }

    private String findChild(String parentKey, String name) {
        VirtualFileSystem.Folder parent = fs.folder(parentKey);
        if (parent == null) {
            return null;
        }
        for (VirtualFileSystem.Entry entry : parent.entries) {
            if (entry.folder && entry.childKey != null && entry.name.equalsIgnoreCase(name)) {
                return entry.childKey;
            }
        }
        return null;
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
            tabEl.addEventListener("mousedown", e -> {
                e.stopPropagation();
                if (e instanceof MouseEvent me && me.button == 0) {
                    String idx = tabEl.getAttribute("data-tab");
                    if (idx != null) {
                        tabDragIndex = Integer.parseInt(idx);
                        tabDragActive = false;
                        tabDragMoved = false;
                        tabDragStartX = me.clientX;
                        tabDragStartY = me.clientY;
                    }
                }
            });
            tabEl.addEventListener("click", e -> {
                if (tabDragMoved) {
                    tabDragMoved = false;
                    return;
                }
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
        tab.foldersFirst = foldersFirst;
        tab.searchQuery = searchQuery;
        tab.lastClickedIndex = lastClickedIndex;
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        tab.title = folder != null ? folder.name : t("informationera.fe.title");
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
        foldersFirst = tab.foldersFirst;
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

    private int tabIndexAt(double mouseX) {
        for (Element tabEl : document.querySelectorAll(".fe-tab[data-tab]")) {
            String idx = tabEl.getAttribute("data-tab");
            if (idx == null) {
                continue;
            }
            Element.DOMRect rect = tabEl.getBoundingClientRect();
            if (rect == null) {
                continue;
            }
            if (mouseX < rect.left + rect.width / 2) {
                try {
                    return Integer.parseInt(idx);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return tabs.isEmpty() ? -1 : tabs.size() - 1;
    }

    private void moveTab(int from, int to) {
        if (from == to || from < 0 || from >= tabs.size() || to < 0 || to >= tabs.size()) {
            return;
        }
        Tab tab = tabs.remove(from);
        tabs.add(to, tab);
        if (activeTab == from) {
            activeTab = to;
        } else if (from < activeTab && to >= activeTab) {
            activeTab--;
        } else if (from > activeTab && to <= activeTab) {
            activeTab++;
        }
        tabDragIndex = to;
        renderTabs();
    }

    private void newTab() {
        saveActiveTab();
        Tab tab = new Tab();
        tab.currentKey = currentKey;
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        tab.title = folder != null ? folder.name : t("informationera.fe.title");
        tabs.add(tab);
        activeTab = tabs.size() - 1;
        loadTab(tab);
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
        updateSearchPlaceholder();
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
        if ("nav".equals(handle)) {
            startWinW = navWidth;
        } else if ("preview".equals(handle)) {
            startWinW = previewWidth;
        }
        activateCapture();
    }

    private void beginRubber(double mouseX, double mouseY, boolean additive) {
        interaction = "rubber";
        rubberStartX = mouseX;
        rubberStartY = mouseY;
        rubberAdditive = additive;
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

    private void applyPaneWidths() {
        Element nav = document.getElementById("fe-nav");
        if (nav != null) {
            nav.setInlineStyleProperty("width", px(navWidth));
        }
        Element preview = document.getElementById("fe-preview-pane");
        if (preview != null) {
            preview.setInlineStyleProperty("width", px(previewWidth));
        }
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
        if ("nav".equals(resizeHandle)) {
            navWidth = Math.max(120, startWinW + dx);
            applyPaneWidths();
            return;
        }
        if ("preview".equals(resizeHandle)) {
            // 预览窗格在右侧，向右拖动分隔条会让预览变窄。
            previewWidth = Math.max(120, startWinW - dx);
            applyPaneWidths();
            return;
        }
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
        if (!rubberAdditive) {
            selected.clear();
        }
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
            // 最小化：整个窗口隐藏，只保留任务栏任务标。
            window.setInlineStyleProperty("display", "none");
        } else {
            minimized = false;
            // 移除内联 display，恢复 CSS 定义的布局，避免残留 display 导致尺寸错乱。
            window.removeInlineStyleProperty("display");
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

    // ---------- 跨窗口拖拽原语（由 FileExplorerWindow 全局引擎调用） ----------

    String ghostHtmlFor(List<VirtualFileSystem.Entry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        if (entries.size() == 1) {
            VirtualFileSystem.Entry entry = entries.get(0);
            return "<div class=\"fe-ghost-row\">" + iconFor(entry, 16) + "<span>" + escape(entry.name) + "</span></div>";
        }
        StringBuilder sb = new StringBuilder("<div class=\"fe-ghost-row\">");
        sb.append(folderIcon(16)).append("<span>").append(t("informationera.fe.items", entries.size())).append("</span>");
        sb.append("</div>");
        return sb.toString();
    }

    /** 命中本窗口内需要覆盖系统光标的元素：ew/ns/nwse/pointer；无匹配返回 null。 */
    String resolveCursorKind(Position screenPos) {
        if (document == null || !document.isActive()) {
            return null;
        }
        Position p = document.screenToDocumentPosition(screenPos);
        Element hit = document.hitTest(p);
        if (hit == null) {
            return null;
        }
        if (hit.closest(".fe-resize-n") != null || hit.closest(".fe-resize-s") != null) {
            return "ns";
        }
        if (hit.closest(".fe-resize-e") != null || hit.closest(".fe-resize-w") != null
                || hit.closest(".fe-col-resizer") != null || hit.closest(".fe-pane-resizer") != null) {
            return "ew";
        }
        if (hit.closest(".fe-resize-ne") != null || hit.closest(".fe-resize-nw") != null
                || hit.closest(".fe-resize-se") != null || hit.closest(".fe-resize-sw") != null) {
            return "nwse";
        }
        if (hit.closest(".fe-cmd-btn") != null || hit.closest(".fe-tool-btn") != null
                || hit.closest(".fe-tab-close") != null || hit.closest(".fe-window-btn") != null
                || hit.closest(".fe-tab-new") != null || hit.closest(".fe-nav-item") != null
                || hit.closest(".fe-crumb") != null || hit.closest(".fe-status-view") != null) {
            return "pointer";
        }
        return null;
    }

    /** 把屏幕坐标换算成本窗口文档坐标后，命中一个可放置目标（文件夹行，或内容区空白=当前文件夹）。 */
    FileExplorerWindow.DropTarget hitDropTarget(Position screenPos) {
        if (document == null || !document.isActive()) {
            return null;
        }
        Position p = document.screenToDocumentPosition(screenPos);
        Element hit = document.hitTest(p);
        if (hit == null) {
            return null;
        }
        Element folderEl = hit.closest("[data-path]");
        if (folderEl != null) {
            String key = folderEl.getAttribute("data-path");
            if (key != null) {
                return new FileExplorerWindow.DropTarget(this, key);
            }
        }
        if (hit.closest(".fe-content") != null) {
            return new FileExplorerWindow.DropTarget(this, currentKey);
        }
        return null;
    }

    /** 执行一次移动/复制；成功返回 true 并刷新自身。 */
    boolean moveOrCopy(String sourceKey, VirtualFileSystem.Entry entry, String targetKey, boolean copy) {
        return moveOrCopy(sourceKey, List.of(entry), targetKey, copy);
    }

    /** 批量移动/复制（多选拖拽）。成功返回 true 并刷新自身。 */
    boolean moveOrCopy(String sourceKey, List<VirtualFileSystem.Entry> entries, String targetKey, boolean copy) {
        boolean any = false;
        List<Runnable> undos = new ArrayList<>();
        int count = 0;
        for (VirtualFileSystem.Entry entry : entries) {
            if (entry.folder && entry.childKey != null && targetKey.startsWith(entry.childKey)) {
                continue;
            }
            if (targetKey.equals(sourceKey) && !copy) {
                continue;
            }
            if (copy) {
                undos.add(fs.duplicateInto(targetKey, entry));
            } else {
                undos.add(fs.move(sourceKey, targetKey, entry));
            }
            any = true;
            count++;
        }
        if (!any) {
            setStatus(copy ? t("informationera.fe.status_cannot_copy") : t("informationera.fe.status_cannot_move"));
            return false;
        }
        pushUndo(() -> undos.forEach(Runnable::run));
        afterMutation();
        setStatus(copy ? t("informationera.fe.status_copied_to", count) : t("informationera.fe.status_moved", count));
        return true;
    }

    /** 拖拽创建快捷方式到目标文件夹。 */
    boolean createShortcuts(String sourceKey, List<VirtualFileSystem.Entry> entries, String targetKey) {
        List<Runnable> undos = new ArrayList<>();
        int count = 0;
        for (VirtualFileSystem.Entry entry : entries) {
            undos.add(fs.createShortcut(targetKey, sourceKey, entry));
            count++;
        }
        if (count == 0) {
            return false;
        }
        pushUndo(() -> undos.forEach(Runnable::run));
        afterMutation();
        setStatus(t("informationera.fe.status_shortcuts_created", count));
        return true;
    }

    /** 其它窗口发生了文件变更，刷新本窗口的显示。 */
    void refreshAfterExternal() {
        afterMutation();
    }

    /** 右键拖拽跨窗口落下：在当前窗口弹出“移动到此处/复制到此处”菜单。 */
    void showExternalDropMenu(List<VirtualFileSystem.Entry> entries, String sourceKey, String targetKey,
                              Position screenPos, FileExplorerController sourceController) {
        rightDragEntries = new ArrayList<>(entries);
        rightDragSourceKey = sourceKey;
        rightDropTargetKey = targetKey;
        Position p = document.screenToDocumentPosition(screenPos);
        showDropMenuFor(p.x, p.y, sourceController);
    }

    private void showDropMenuFor(double x, double y, FileExplorerController sourceController) {
        if (rightDragEntries.isEmpty() || rightDragSourceKey == null) {
            return;
        }
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.action(t("informationera.fe.move_here"), () -> performRightDrop(false, sourceController)));
        entries.add(CtxEntry.action(t("informationera.fe.copy_here"), () -> performRightDrop(true, sourceController)));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.submenu(t("informationera.fe.move_to_folder"), folderChoices(false, sourceController)));
        entries.add(CtxEntry.submenu(t("informationera.fe.copy_to_folder"), folderChoices(true, sourceController)));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.cancel"), () -> {
        }));
        openContextMenu(x, y, entries);
    }

    /** 递归列出所有可选文件夹（排除源条目自身及其子目录），作为二级菜单项。 */
    private List<CtxEntry> folderChoices(boolean copy, FileExplorerController sourceController) {
        List<CtxEntry> result = new ArrayList<>();
        collectFolderChoices(result, "thispc", copy, sourceController, 0);
        collectFolderChoices(result, "home", copy, sourceController, 0);
        if (result.isEmpty()) {
            result.add(CtxEntry.action(t("informationera.fe.no_folder"), () -> {
            }));
        }
        return result;
    }

    private void collectFolderChoices(List<CtxEntry> out, String key, boolean copy,
                                      FileExplorerController sourceController, int depth) {
        VirtualFileSystem.Folder folder = fs.folder(key);
        if (folder == null) {
            return;
        }
        if (depth > 0) {
            String label = (depth > 1 ? "— ".repeat(depth - 1) : "") + folder.name;
            out.add(CtxEntry.action(label, () -> {
                sourceController.moveOrCopy(rightDragSourceKey, rightDragEntries, key, copy);
                if (sourceController != this) {
                    refreshAfterExternal();
                }
            }));
        }
        for (VirtualFileSystem.Entry entry : folder.entries) {
            if (entry.folder && entry.childKey != null) {
                // 跳过拖拽源文件夹及其子树，避免移动到自身内部。
                boolean selfOrChild = false;
                for (VirtualFileSystem.Entry de : rightDragEntries) {
                    if (de.folder && de.childKey != null && entry.childKey.startsWith(de.childKey)) {
                        selfOrChild = true;
                        break;
                    }
                }
                if (selfOrChild) {
                    continue;
                }
                if (depth < 4) {
                    collectFolderChoices(out, entry.childKey, copy, sourceController, depth + 1);
                }
            }
        }
    }

    private void performRightDrop(boolean copy, FileExplorerController sourceController) {
        if (rightDragEntries.isEmpty() || rightDragSourceKey == null || rightDropTargetKey == null) {
            setStatus(t("informationera.fe.status_drop_invalid"));
            return;
        }
        sourceController.moveOrCopy(rightDragSourceKey, rightDragEntries, rightDropTargetKey, copy);
        if (sourceController != this) {
            refreshAfterExternal();
        }
    }

    // ---------- 右键菜单（自绘，Ore 主题） ----------

    private record CtxEntry(int kind, String label, boolean danger, boolean checked, Runnable action, List<CtxEntry> children) {
        static CtxEntry header(String label) {
            return new CtxEntry(0, label, false, false, null, null);
        }

        static CtxEntry separator() {
            return new CtxEntry(1, "", false, false, null, null);
        }

        static CtxEntry action(String label, Runnable action) {
            return new CtxEntry(2, label, false, false, action, null);
        }

        static CtxEntry actionChecked(String label, boolean checked, Runnable action) {
            return new CtxEntry(2, label, false, checked, action, null);
        }

        static CtxEntry actionDanger(String label, Runnable action) {
            return new CtxEntry(2, label, true, false, action, null);
        }

        static CtxEntry submenu(String label, List<CtxEntry> children) {
            return new CtxEntry(3, label, false, false, null, children);
        }
    }

    private void showWindowContextMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.action(t("informationera.fe.new_tab"), this::newTab));
        entries.add(CtxEntry.action(t("informationera.fe.maximize"), this::toggleMaximize));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionDanger(t("informationera.fe.close_window"), this::closeWindow));
        openContextMenu(x, y, entries);
    }

    private void showContentContextMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.action(t("informationera.fe.refresh"), this::renderContent));
        entries.add(CtxEntry.action(t("informationera.fe.new_folder"), this::createFolder));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.paste"), this::paste));
        entries.add(CtxEntry.action(t("informationera.fe.undo"), this::undo));
        entries.add(CtxEntry.action(t("informationera.fe.redo"), this::redo));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.select_all"), this::selectAll));
        entries.add(CtxEntry.action(t("informationera.fe.invert_selection"), this::invertSelection));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.properties"), () -> setStatus(pathText(currentKey))));
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
        entries.add(CtxEntry.action(t("informationera.fe.open"), () -> openEntry(entry)));
        entries.add(CtxEntry.submenu(t("informationera.fe.open_with"), List.of(
                CtxEntry.action(t("informationera.fe.notepad"), () -> setStatus(t("informationera.fe.status_opened_notepad", entry.name))),
                CtxEntry.action(t("informationera.fe.default_app"), () -> openEntry(entry)))));
        entries.add(CtxEntry.action(t("informationera.fe.rename"), () -> beginRename(item, entry)));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.cut"), this::cut));
        entries.add(CtxEntry.action(t("informationera.fe.copy"), this::copy));
        entries.add(CtxEntry.action(t("informationera.fe.copy_path"), () -> copyPath(entry)));
        entries.add(CtxEntry.action(t("informationera.fe.create_shortcut"), () -> createShortcut(entry)));
        if ("快捷方式".equals(entry.type) && entry.shortcutTargetKey != null) {
            entries.add(CtxEntry.action(t("informationera.fe.open_location"), () -> openEntry(entry)));
        }
        if (entry.folder && entry.childKey != null) {
            String key = entry.childKey;
            entries.add(CtxEntry.action(pinned.contains(key)
                    ? t("informationera.fe.unpin") : t("informationera.fe.pin"), () -> togglePin(key)));
        }
        entries.add(CtxEntry.submenu(t("informationera.fe.send_to"), List.of(
                CtxEntry.action(t("informationera.fe.desktop"), () -> sendTo("desktop", entry)),
                CtxEntry.action(t("informationera.fe.documents"), () -> sendTo("documents", entry)),
                CtxEntry.action(t("informationera.fe.downloads"), () -> sendTo("downloads", entry)))));
        entries.add(CtxEntry.actionDanger(t("informationera.fe.delete"), this::delete));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.properties"), () -> setStatus(entry.name + " — " + entry.type + "，" + (entry.folder ? t("informationera.fe.folder") : formatSize(entry.size)))));
        openContextMenu(x, y, entries);
    }

    private void showNewMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.header(t("informationera.fe.new")));
        entries.add(CtxEntry.action(t("informationera.fe.new_folder"), this::createFolder));
        openContextMenu(x, y, entries);
    }

    private void showSortMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.actionChecked(t("informationera.fe.name"), "name".equals(sortField), () -> setSort("name")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.modified"), "modified".equals(sortField), () -> setSort("modified")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.created"), "created".equals(sortField), () -> setSort("created")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.type"), "type".equals(sortField), () -> setSort("type")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.size"), "size".equals(sortField), () -> setSort("size")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.author"), "author".equals(sortField), () -> setSort("author")));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked(t("informationera.fe.asc"), sortAsc, () -> setSortAscending(true)));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.desc"), !sortAsc, () -> setSortAscending(false)));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked(t("informationera.fe.folders_first"), foldersFirst, this::toggleFoldersFirst));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.submenu(t("informationera.fe.group_by"), List.of(
                CtxEntry.actionChecked(t("informationera.fe.none"), groupBy == null, () -> setGroupBy(null)),
                CtxEntry.actionChecked(t("informationera.fe.name"), "name".equals(groupBy), () -> setGroupBy("name")),
                CtxEntry.actionChecked(t("informationera.fe.modified"), "modified".equals(groupBy), () -> setGroupBy("modified")),
                CtxEntry.actionChecked(t("informationera.fe.created"), "created".equals(groupBy), () -> setGroupBy("created")),
                CtxEntry.actionChecked(t("informationera.fe.type"), "type".equals(groupBy), () -> setGroupBy("type")),
                CtxEntry.actionChecked(t("informationera.fe.size"), "size".equals(groupBy), () -> setGroupBy("size")),
                CtxEntry.actionChecked(t("informationera.fe.author"), "author".equals(groupBy), () -> setGroupBy("author")))));
        openContextMenu(x, y, entries);
    }

    private void toggleFoldersFirst() {
        foldersFirst = !foldersFirst;
        renderContent();
    }

    private void showLayoutMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.actionChecked(t("informationera.fe.xlarge"), "xlarge".equals(viewMode), () -> setViewMode("xlarge")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.large"), "large".equals(viewMode), () -> setViewMode("large")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.medium"), "medium".equals(viewMode), () -> setViewMode("medium")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.small"), "small".equals(viewMode), () -> setViewMode("small")));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked(t("informationera.fe.list"), "list".equals(viewMode), () -> setViewMode("list")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.details"), "details".equals(viewMode), () -> setViewMode("details")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.content"), "content".equals(viewMode), () -> setViewMode("content")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.tiles"), "tiles".equals(viewMode), () -> setViewMode("tiles")));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked(t("informationera.fe.compact"), compact, this::toggleCompact));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked(t("informationera.fe.nav_pane"), showNav, this::toggleNavPane));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.preview_pane"), previewOpen, this::togglePreview));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.details_pane"), detailsPaneOpen, this::toggleDetailsPane));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionChecked(t("informationera.fe.checkboxes"), checkboxes, this::toggleCheckboxes));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.extensions"), showExtensions, this::toggleExtensions));
        openContextMenu(x, y, entries);
    }

    private void openContextMenu(double x, double y, List<CtxEntry> entries) {
        Element menu = document.getElementById("fe-context-menu");
        if (menu == null) {
            return;
        }
        List<Runnable> actions = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        appendContextEntries(sb, entries, actions);
        menu.setInnerHTML(sb.toString());

        Size viewport = document.getViewportSize();
        int estimatedHeight = estimateContextHeight(entries);
        double left = Math.max(2, Math.min(x, viewport.width() - MENU_WIDTH - 4));
        double top = Math.max(2, Math.min(y, viewport.height() - estimatedHeight - 4));
        menu.setInlineStyleProperty("left", px(left));
        menu.setInlineStyleProperty("top", px(top));
        menu.setInlineStyleProperty("display", "block");
        contextMenuOpen = true;
        contextFocusIndex = -1;
        bindContextKeyboard(menu, actions);

        for (Element item : menu.querySelectorAll(".fe-ctx-item")) {
            item.addEventListener("click", e -> {
                if (item.getClassList().contains("fe-has-sub")) {
                    e.stopPropagation();
                    return;
                }
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

    private void bindContextKeyboard(Element menu, List<Runnable> actions) {
        document.addEventListener("keydown", e -> {
            if (!contextMenuOpen || !(e instanceof KeyEvent ke)) {
                return;
            }
            if (e.target instanceof Element target && target.closest("input") != null) {
                return;
            }
            List<Element> items = menu.querySelectorAll(".fe-ctx-item[data-i]");
            if (items.isEmpty()) {
                return;
            }
            if ("ArrowDown".equalsIgnoreCase(ke.key) || "Down".equalsIgnoreCase(ke.key)) {
                contextFocusIndex = (contextFocusIndex + 1) % items.size();
            } else if ("ArrowUp".equalsIgnoreCase(ke.key) || "Up".equalsIgnoreCase(ke.key)) {
                contextFocusIndex = contextFocusIndex <= 0 ? items.size() - 1 : contextFocusIndex - 1;
            } else if ("Enter".equalsIgnoreCase(ke.key)) {
                if (contextFocusIndex >= 0 && contextFocusIndex < items.size()) {
                    Element item = items.get(contextFocusIndex);
                    String idx = item.getAttribute("data-i");
                    closeContextMenu();
                    try {
                        int index = Integer.parseInt(idx);
                        if (index >= 0 && index < actions.size() && actions.get(index) != null) {
                            actions.get(index).run();
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                return;
            } else if ("Escape".equalsIgnoreCase(ke.key)) {
                closeContextMenu();
                return;
            } else {
                return;
            }
            updateContextFocus(menu, items);
        });
    }

    private void updateContextFocus(Element menu, List<Element> items) {
        for (int i = 0; i < items.size(); i++) {
            if (i == contextFocusIndex) {
                items.get(i).getClassList().add("ctx-focused");
            } else {
                items.get(i).getClassList().remove("ctx-focused");
            }
        }
    }

    private void appendContextEntries(StringBuilder sb, List<CtxEntry> entries, List<Runnable> actions) {
        for (CtxEntry entry : entries) {
            switch (entry.kind) {
                case 0 -> sb.append("<div class=\"fe-ctx-header\">").append(escape(entry.label)).append("</div>");
                case 1 -> sb.append("<div class=\"fe-ctx-sep\"></div>");
                case 2 -> {
                    actions.add(entry.action);
                    int index = actions.size() - 1;
                    sb.append("<div class=\"fe-ctx-item")
                      .append(entry.danger ? " danger" : "")
                      .append("\" data-i=\"").append(index).append("\">");
                    sb.append("<span class=\"fe-ctx-mark\">").append(entry.checked ? "✓" : "").append("</span>");
                    sb.append("<span class=\"fe-ctx-label\">").append(escape(entry.label)).append("</span>");
                    sb.append("</div>");
                }
                case 3 -> {
                    sb.append("<div class=\"fe-ctx-item fe-has-sub\">");
                    sb.append("<span class=\"fe-ctx-mark\"></span>");
                    sb.append("<span class=\"fe-ctx-label\">").append(escape(entry.label)).append(" ▸</span>");
                    sb.append("<div class=\"fe-ctx-submenu\">");
                    appendContextEntries(sb, entry.children, actions);
                    sb.append("</div>");
                    sb.append("</div>");
                }
                default -> {
                }
            }
        }
    }

    private int estimateContextHeight(List<CtxEntry> entries) {
        int h = 0;
        for (CtxEntry entry : entries) {
            if (entry.kind == 1) {
                h += 10;
            } else {
                h += 30;
            }
        }
        return h;
    }

    void closeContextMenu() {
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
        setStatus(undo != null ? t("informationera.fe.status_renamed") : t("informationera.fe.status_rename_invalid"));
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
            setStatus(t("informationera.fe.status_no_selection"));
            return;
        }
        ExplorerSession.clearClipboard();
        for (VirtualFileSystem.Entry entry : selected) {
            ExplorerSession.clipboard.add(new ExplorerSession.ClipItem(currentKey, entry));
        }
        ExplorerSession.clipboardCut = true;
        setStatus(t("informationera.fe.status_cut", ExplorerSession.clipboard.size()));
        updateCommandButtons();
        updateSelectionVisuals();
    }

    private void copy() {
        if (selected.isEmpty()) {
            setStatus(t("informationera.fe.status_no_selection"));
            return;
        }
        ExplorerSession.clearClipboard();
        for (VirtualFileSystem.Entry entry : selected) {
            ExplorerSession.clipboard.add(new ExplorerSession.ClipItem(null, entry));
        }
        ExplorerSession.clipboardCut = false;
        setStatus(t("informationera.fe.status_copied", ExplorerSession.clipboard.size()));
        updateCommandButtons();
    }

    private void paste() {
        if (ExplorerSession.clipboard.isEmpty()) {
            setStatus(t("informationera.fe.status_clipboard_empty"));
            return;
        }
        List<Runnable> undos = new ArrayList<>();
        int count = 0;
        if (ExplorerSession.clipboardCut) {
            for (ExplorerSession.ClipItem item : new ArrayList<>(ExplorerSession.clipboard)) {
                if (item.sourceFolderKey() != null && !item.sourceFolderKey().equals(currentKey)) {
                    undos.add(fs.move(item.sourceFolderKey(), currentKey, item.entry()));
                    count++;
                }
            }
            ExplorerSession.clearClipboard();
        } else {
            for (ExplorerSession.ClipItem item : ExplorerSession.clipboard) {
                undos.add(fs.duplicateInto(currentKey, item.entry()));
                count++;
            }
        }
        if (count == 0) {
            setStatus(t("informationera.fe.status_cannot_paste"));
            return;
        }
        pushUndo(() -> undos.forEach(Runnable::run));
        afterMutation();
        setStatus(t("informationera.fe.status_pasted", count));
    }

    private void delete() {
        if (selected.isEmpty()) {
            setStatus(t("informationera.fe.status_no_selection"));
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
        setStatus(t("informationera.fe.status_deleted", count));
    }

    private void createFolder() {
        pushUndo(fs.createFolder(currentKey, t("informationera.fe.new_folder")));
        afterMutation();
        setStatus(t("informationera.fe.status_created_folder"));
    }

    private void undo() {
        if (ExplorerSession.undoStack.isEmpty()) {
            setStatus(t("informationera.fe.status_undo_none"));
            return;
        }
        Runnable action = ExplorerSession.undoStack.pop();
        action.run();
        ExplorerSession.redoStack.push(action);
        afterMutation();
        FileExplorerWindow.refreshAll();
        setStatus(t("informationera.fe.status_undone"));
    }

    private void redo() {
        if (ExplorerSession.redoStack.isEmpty()) {
            setStatus(t("informationera.fe.status_redo_none"));
            return;
        }
        Runnable action = ExplorerSession.redoStack.pop();
        action.run();
        ExplorerSession.undoStack.push(action);
        afterMutation();
        FileExplorerWindow.refreshAll();
        setStatus(t("informationera.fe.status_redone"));
    }

    private void pushUndo(Runnable undo) {
        ExplorerSession.undoStack.push(undo);
        ExplorerSession.redoStack.clear();
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

    /** 选中指定分组下的全部条目（含折叠未渲染的条目）。 */
    private void selectGroup(String group) {
        selected.clear();
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        if (folder == null) {
            updateSelectionVisuals();
            updateStatusBar();
            return;
        }
        for (VirtualFileSystem.Entry entry : folder.entries) {
            if (group.equals(groupLabel(entry))) {
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

    private void moveSelection(int delta, boolean extend) {
        if (renderedEntryList.isEmpty()) {
            return;
        }
        if (extend) {
            if (selectionAnchor < 0) {
                selectionAnchor = lastClickedIndex >= 0 ? lastClickedIndex : 0;
            }
            int next = Math.max(0, Math.min(renderedEntryList.size() - 1,
                    (lastClickedIndex >= 0 ? lastClickedIndex : selectionAnchor) + delta));
            selectRange(selectionAnchor, next);
            lastClickedIndex = next;
        } else {
            int base = lastClickedIndex >= 0 ? lastClickedIndex : 0;
            int next = Math.max(0, Math.min(renderedEntryList.size() - 1, base + delta));
            selectionAnchor = next;
            selected.clear();
            selected.add(renderedEntryList.get(next));
            lastClickedIndex = next;
            updateSelectionVisuals();
            updateStatusBar();
        }
    }

    private void moveSelectionTo(int index, boolean extend) {
        if (renderedEntryList.isEmpty()) {
            return;
        }
        int target = Math.max(0, Math.min(renderedEntryList.size() - 1, index));
        if (extend) {
            if (selectionAnchor < 0) {
                selectionAnchor = lastClickedIndex >= 0 ? lastClickedIndex : 0;
            }
            selectRange(selectionAnchor, target);
            lastClickedIndex = target;
        } else {
            selectionAnchor = target;
            selected.clear();
            selected.add(renderedEntryList.get(target));
            lastClickedIndex = target;
            updateSelectionVisuals();
            updateStatusBar();
        }
    }

    private void selectRange(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        selected.clear();
        for (int i = lo; i <= hi; i++) {
            selected.add(renderedEntryList.get(i));
        }
        updateSelectionVisuals();
        updateStatusBar();
    }

    private Element findItemByIndex(int index) {
        for (Element item : document.querySelectorAll(".fe-item")) {
            if (indexOf(item) == index) {
                return item;
            }
        }
        return null;
    }

    /** Alt+Enter：显示当前选中项属性。 */
    private void showProperties() {
        if (!selected.isEmpty()) {
            VirtualFileSystem.Entry entry = selected.iterator().next();
            setStatus(entry.name + " — " + entry.type + "，" + (entry.folder ? t("informationera.fe.folder") : formatSize(entry.size)));
        } else if (!renderedEntryList.isEmpty()) {
            VirtualFileSystem.Entry entry = renderedEntryList.get(0);
            setStatus(entry.name + " — " + entry.type + "，" + (entry.folder ? t("informationera.fe.folder") : formatSize(entry.size)));
        } else {
            setStatus(pathText(currentKey));
        }
    }

    /** Shift+F10：在当前位置打开上下文菜单（简化：对首个选中项，否则内容区空白菜单）。 */
    private void showContextMenuAtCurrent() {
        if (!selected.isEmpty()) {
            VirtualFileSystem.Entry entry = selected.iterator().next();
            int index = indexOfEntry(entry);
            Element item = index >= 0 ? findItemByIndex(index) : null;
            if (item != null) {
                Element.DOMRect rect = item.getBoundingClientRect();
                if (rect != null) {
                    showItemContextMenu(item, entry, rect.left + 8, rect.top + 8);
                    return;
                }
            }
        }
        Element content = document.getElementById("fe-content");
        Element.DOMRect rect = content != null ? content.getBoundingClientRect() : null;
        if (rect != null) {
            showContentContextMenu(rect.left + 40, rect.top + 40);
        }
    }

    private void openEntry(VirtualFileSystem.Entry entry) {
        // 快捷方式：跳转到其目标（文件夹则进入，文件则进入所在文件夹）。
        if (entry.shortcutTargetKey != null && fs.folder(entry.shortcutTargetKey) != null) {
            navigateTo(entry.shortcutTargetKey);
            return;
        }
        if (entry.childKey != null && fs.folder(entry.childKey) != null) {
            // 文件夹或指向文件夹的快捷方式。
            navigateTo(entry.childKey);
        } else if (entry.folder) {
            setStatus(t("informationera.fe.status_cannot_open_folder", entry.name));
        } else {
            setStatus(t("informationera.fe.status_cannot_open_file", entry.name));
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

    private void handleContentWheel(MouseEvent me) {
        if (!me.controlKey) {
            return;
        }
        // Windows 资源管理器 Ctrl+滚轮顺序（从大到小）：
        // 超大图标 -> 大图标 -> 中等图标 -> 小图标 -> 列表 -> 详细信息 -> 内容 -> 平铺。
        // 向上滚（deltaY<0）变"小一档"，向下滚变"大一档"，两端截断，不循环。
        List<String> modes = List.of("xlarge", "large", "medium", "small", "list", "details", "content", "tiles");
        int idx = modes.indexOf(viewMode);
        if (idx < 0) {
            idx = 3;
        }
        int next = me.deltaY < 0
                ? Math.max(0, idx - 1)
                : Math.min(modes.size() - 1, idx + 1);
        setViewMode(modes.get(next));
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

    private void setGroupBy(String value) {
        groupBy = value;
        collapsedGroups.clear();
        renderContent();
    }

    private void toggleExtensions() {
        showExtensions = !showExtensions;
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
            sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.detail_type", escape(entry.type))).append("</div>");
            if (entry.folder) {
                sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.folder")).append("</div>");
                sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.detail_contains", childCount(entry.childKey))).append("</div>");
            } else {
                sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.detail_size", formatSize(entry.size))).append("</div>");
                sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.detail_bytes", entry.size)).append("</div>");
            }
            sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.detail_modified", escape(entry.modified))).append("</div>");
            sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.detail_location", escape(pathText(currentKey)))).append("</div>");
            sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.detail_readonly")).append("</div>");
            pane.setInnerHTML(sb.toString());
        } else if (selected.size() > 1) {
            pane.setInnerHTML("<div class=\"fe-details-empty\">" + t("informationera.fe.detail_selected", selected.size()) + "</div>");
        } else {
            pane.setInnerHTML("<div class=\"fe-details-empty\">" + t("informationera.fe.detail_none") + "</div>");
        }
    }

    private int childCount(String key) {
        VirtualFileSystem.Folder folder = fs.folder(key);
        return folder == null ? 0 : folder.entries.size();
    }

    private void applyNavVisibility() {
        Element nav = document.getElementById("fe-nav");
        if (nav != null) {
            nav.setInlineStyleProperty("display", showNav ? "block" : "none");
        }
        Element resizer = document.getElementById("fe-nav-resizer");
        if (resizer != null) {
            resizer.setInlineStyleProperty("display", showNav ? "block" : "none");
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
        Element resizer = document.getElementById("fe-preview-resizer");
        if (resizer != null) {
            resizer.setInlineStyleProperty("display", previewOpen ? "block" : "none");
        }
    }

    private void updatePreview() {
        Element pane = document.getElementById("fe-preview-pane");
        if (pane == null) {
            return;
        }
        StringBuilder toolbar = new StringBuilder();
        toolbar.append("<div class=\"fe-preview-toolbar\">");
        toolbar.append(previewSizeButton(t("informationera.fe.small"), 48));
        toolbar.append(previewSizeButton(t("informationera.fe.medium"), 64));
        toolbar.append(previewSizeButton(t("informationera.fe.large"), 128));
        toolbar.append("</div>");

        if (selected.size() == 1) {
            VirtualFileSystem.Entry entry = selected.iterator().next();
            StringBuilder sb = new StringBuilder();
            sb.append(toolbar);
            sb.append("<div class=\"fe-preview-icon\">").append(iconFor(entry, previewSize)).append("</div>");
            sb.append("<div class=\"fe-preview-name\">").append(escape(entry.name)).append("</div>");
            sb.append("<div class=\"fe-preview-line\">").append(t("informationera.fe.detail_type", escape(entry.type))).append("</div>");
            if (entry.folder) {
                sb.append("<div class=\"fe-preview-line\">").append(t("informationera.fe.folder")).append("</div>");
            } else {
                sb.append("<div class=\"fe-preview-line\">").append(t("informationera.fe.detail_size", formatSize(entry.size))).append("</div>");
            }
            sb.append("<div class=\"fe-preview-line\">").append(t("informationera.fe.detail_modified_short", escape(entry.modified))).append("</div>");
            pane.setInnerHTML(sb.toString());
        } else if (selected.size() > 1) {
            pane.setInnerHTML(toolbar.toString() + "<div class=\"fe-preview-empty\">" + t("informationera.fe.detail_selected", selected.size()) + "</div>");
        } else {
            pane.setInnerHTML(toolbar.toString() + "<div class=\"fe-preview-empty\">" + t("informationera.fe.detail_none") + "</div>");
        }
        bindPreviewSizeButtons();
    }

    private String previewSizeButton(String label, int size) {
        boolean active = previewSize == size;
        return "<button class=\"fe-preview-size" + (active ? " active" : "") + "\" data-size=\"" + size + "\">" + label + "</button>";
    }

    private void bindPreviewSizeButtons() {
        for (Element btn : document.querySelectorAll(".fe-preview-size")) {
            String sizeStr = btn.getAttribute("data-size");
            btn.addEventListener("click", e -> {
                if (sizeStr != null) {
                    try {
                        previewSize = Integer.parseInt(sizeStr);
                    } catch (NumberFormatException ignored) {
                    }
                    updatePreview();
                }
            });
        }
    }

    // ---------- 导航 ----------

    private void navigateTo(String key) {
        if (fs.folder(key) == null) {
            setStatus(t("informationera.fe.status_path_not_found", key));
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
        // 记录本窗口导航历史（去重，最近在前）。
        navHistory.remove(currentKey);
        navHistory.add(0, currentKey);
        if (navHistory.size() > 20) {
            navHistory.remove(navHistory.size() - 1);
        }
        // 记录全局最近路径（任务栏 Jump List 用）。
        FileExplorerWindow.recordRecentPath(currentKey);
        updateSearchPlaceholder();
        renderContent();
        renderNavigationState();
        renderBreadcrumb();
        updateNavButtons();
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        tabs.get(activeTab).title = folder != null ? folder.name : t("informationera.fe.title");
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
        // 快速访问：固定 + 最近使用。
        List<String> recent = FileExplorerWindow.getRecentPaths();
        boolean hasRecent = !recent.isEmpty();
        if (!pinned.isEmpty() || hasRecent) {
            sb.append("<div class=\"fe-nav-title\">").append(t("informationera.fe.quick_access")).append("</div>");
            if (!pinned.isEmpty()) {
                for (String key : pinned) {
                    renderPinnedNode(sb, key);
                }
            }
            if (hasRecent) {
                sb.append("<div class=\"fe-nav-title fe-nav-subtitle\">").append(t("informationera.fe.recent_use")).append("</div>");
                int shown = 0;
                for (String key : recent) {
                    if (pinned.contains(key)) {
                        continue;
                    }
                    renderPinnedNode(sb, key);
                    if (++shown >= 8) {
                        break;
                    }
                }
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
        // 视图模式类：控制图标网格的列宽与图标尺寸，切换布局而不是单纯缩放。
        content.getClassList().remove("fe-view-small", "fe-view-medium", "fe-view-large", "fe-view-xlarge");
        if ("small".equals(viewMode) || "medium".equals(viewMode) || "large".equals(viewMode) || "xlarge".equals(viewMode)) {
            content.getClassList().add("fe-view-" + viewMode);
        }
        List<VirtualFileSystem.Entry> entries = topLevelEntries();
        renderedEntryList.clear();
        renderedEntryList.addAll(entries);
        lastClickedIndex = -1;

        if (entries.isEmpty()) {
            if (!searchQuery.trim().isEmpty()) {
                content.setInnerHTML("<div class=\"fe-empty\">" + t("informationera.fe.no_match")
                        + "<button class=\"fe-empty-search-btn\" id=\"fe-subfolder-search\">" + t("informationera.fe.search_subfolders") + "</button></div>");
                Element btn = content.querySelector("#fe-subfolder-search");
                if (btn != null) {
                    btn.addEventListener("click", e -> searchSubfolders());
                }
            } else {
                content.setInnerHTML("<div class=\"fe-empty\">" + t("informationera.fe.empty_folder") + "</div>");
            }
        } else if (isLinearView() && entries.size() > 200) {
            renderContentVirtual(entries);
        } else {
            content.setInnerHTML(switch (viewMode) {
                case "details" -> buildTable(entries);
                case "content" -> buildContent(entries);
                case "list" -> buildList(entries);
                case "tiles" -> buildTiles(entries);
                default -> buildGrid(entries);
            });
        }
        bindItemListeners();
        bindTableHeaders();
        updateSelectionVisuals();
        updateStatusBar();
        applyCompact();
        updateStatusViewButtons();
    }

    private boolean isLinearView() {
        return "details".equals(viewMode) || "list".equals(viewMode) || "content".equals(viewMode);
    }

    /** 线性视图窗口化渲染：只渲染可视窗口内的行，上下用 spacer 占位。 */
    private void renderContentVirtual(List<VirtualFileSystem.Entry> entries) {
        Element content = document.getElementById("fe-content");
        if (content == null) {
            return;
        }
        double scrollTop = content.getScrollTop();
        Element.DOMRect rect = content.getBoundingClientRect();
        double viewH = rect != null && rect.height > 0 ? rect.height : 600;
        int total = entries.size();
        int start = Math.max(0, (int) Math.floor(scrollTop / ROW_HEIGHT) - VIRTUAL_BUFFER);
        int end = Math.min(total, (int) Math.ceil((scrollTop + viewH) / ROW_HEIGHT) + VIRTUAL_BUFFER);

        StringBuilder sb = new StringBuilder();
        boolean details = "details".equals(viewMode);
        if (details) {
            sb.append("<div class=\"fe-table fe-vtable\" style=\"").append(tableWidthStyle()).append("\">");
            sb.append("<div class=\"fe-vthead\" style=\"").append(gridTemplateStyle()).append("\">");
            for (int col = 0; col < COL_FIELDS.length; col++) {
                if (columnVisible[col]) {
                    sb.append(headerCell(columnLabels()[col], COL_FIELDS[col], col));
                }
            }
            sb.append("</div>");
        }
        sb.append("<div class=\"fe-vbody\" style=\"height:").append(Math.round(total * ROW_HEIGHT))
          .append("px;position:relative;\">");
        if (start > 0) {
            sb.append("<div style=\"height:").append(Math.round(start * ROW_HEIGHT)).append("px;\"></div>");
        }
        for (int i = start; i < end; i++) {
            VirtualFileSystem.Entry entry = entries.get(i);
            if (details) {
                sb.append("<div class=\"fe-item fe-vrow\" data-id=\"e").append(i).append("\"")
                  .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
                  .append(folderPathAttr(entry))
                  .append(" style=\"").append(gridTemplateStyle()).append("\">");
                for (int col = 0; col < COL_FIELDS.length; col++) {
                    if (columnVisible[col]) {
                        sb.append(cellDiv(entry, col, i));
                    }
                }
                sb.append("</div>");
            } else {
                sb.append("<div class=\"fe-item fe-vrow fe-list-item\" data-id=\"e").append(i).append("\"")
                  .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
                  .append(folderPathAttr(entry)).append(">");
                if (checkboxes) {
                    sb.append("<span class=\"fe-check\" data-id=\"e").append(i).append("\"></span>");
                }
                sb.append("<span class=\"fe-icon\">").append(iconFor(entry, 16)).append("</span>");
                sb.append("<span class=\"fe-name-text\" data-name=\"1\">").append(escape(displayName(entry))).append("</span>");
                sb.append("</div>");
            }
        }
        sb.append("</div>");
        if (details) {
            sb.append("</div>");
        }
        content.setInnerHTML(sb.toString());
    }

    /** 虚拟滚动模式下的单元格（div 形式，对应 cellFor）。 */
    private String cellDiv(VirtualFileSystem.Entry entry, int col, int index) {
        switch (col) {
            case 0 -> {
                StringBuilder sb = new StringBuilder("<div class=\"fe-cell\">");
                if (checkboxes) {
                    sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
                }
                sb.append(iconFor(entry, 16))
                  .append("<span class=\"fe-name-text\" data-name=\"1\">").append(escape(displayName(entry))).append("</span>")
                  .append("</div>");
                return sb.toString();
            }
            case 1 -> {
                return "<div class=\"fe-vcell\">" + escape(entry.modified) + "</div>";
            }
            case 2 -> {
                return "<div class=\"fe-vcell\">" + escape(entry.type) + "</div>";
            }
            case 3 -> {
                return "<div class=\"fe-vcell\">" + (entry.folder ? "" : formatSize(entry.size)) + "</div>";
            }
            case 4 -> {
                return "<div class=\"fe-vcell\">" + escape(entry.created) + "</div>";
            }
            case 5 -> {
                return "<div class=\"fe-vcell\">" + escape(entry.author) + "</div>";
            }
            default -> {
                return "<div class=\"fe-vcell\"></div>";
            }
        }
    }

    private void onContentScroll() {
        Element content = document.getElementById("fe-content");
        if (content == null) {
            return;
        }
        double newTop = content.getScrollTop();
        if (Math.abs(newTop - scrollTop) > 1.0) {
            scrollTop = newTop;
            if (isLinearView()) {
                List<VirtualFileSystem.Entry> entries = topLevelEntries();
                if (entries.size() > 200) {
                    renderContentVirtual(entries);
                    bindItemListeners();
                    bindTableHeaders();
                    updateSelectionVisuals();
                }
            }
        }
    }

    /** 通配符匹配：支持 *（任意串）和 ?（单字符），大小写不敏感。 */
    private static boolean matchesQuery(String lowerName, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (query.indexOf('*') < 0 && query.indexOf('?') < 0) {
            return lowerName.contains(query);
        }
        return wildcardMatch(lowerName, query);
    }

    private static boolean wildcardMatch(String text, String pattern) {
        int ti = 0;
        int pi = 0;
        int star = -1;
        int mark = -1;
        while (ti < text.length()) {
            if (pi < pattern.length() && (pattern.charAt(pi) == '?' || pattern.charAt(pi) == text.charAt(ti))) {
                ti++;
                pi++;
            } else if (pi < pattern.length() && pattern.charAt(pi) == '*') {
                star = pi;
                mark = ti;
                pi++;
            } else if (star != -1) {
                pi = star + 1;
                mark++;
                ti = mark;
            } else {
                return false;
            }
        }
        while (pi < pattern.length() && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pattern.length();
    }

    /** 递归收集当前文件夹子树内匹配 query 的文件（含所在文件夹 key）。 */
    private void collectRecursiveMatches(String folderKey, String query, int depth,
                                         List<VirtualFileSystem.Entry> files, List<String> fileFolders) {
        if (depth > 12) {
            return;
        }
        VirtualFileSystem.Folder folder = fs.folder(folderKey);
        if (folder == null) {
            return;
        }
        for (VirtualFileSystem.Entry entry : folder.entries) {
            if (entry.folder && entry.childKey != null) {
                collectRecursiveMatches(entry.childKey, query, depth + 1, files, fileFolders);
            } else if (matchesQuery(entry.name.toLowerCase(), query)) {
                files.add(entry);
                fileFolders.add(folderKey);
            }
        }
    }

    /** 在子文件夹中搜索：若有匹配则导航到第一个匹配项所在文件夹并选中它。 */
    private void searchSubfolders() {
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase();
        if (query.isEmpty()) {
            return;
        }
        List<VirtualFileSystem.Entry> files = new ArrayList<>();
        List<String> fileFolders = new ArrayList<>();
        collectRecursiveMatches(currentKey, query, 0, files, fileFolders);
        if (files.isEmpty()) {
            setStatus(t("informationera.fe.status_no_subfolder_match"));
            return;
        }
        String targetKey = fileFolders.get(0);
        VirtualFileSystem.Entry target = files.get(0);
        navigateTo(targetKey);
        selected.clear();
        selected.add(target);
        updateSelectionVisuals();
        updateStatusBar();
        setStatus(t("informationera.fe.status_subfolder_found", files.size()));
    }

    private List<VirtualFileSystem.Entry> topLevelEntries() {
        List<VirtualFileSystem.Entry> entries = new ArrayList<>();
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        if (folder == null) {
            return entries;
        }
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase();
        for (VirtualFileSystem.Entry entry : folder.entries) {
            if (query.isEmpty() || matchesQuery(entry.name.toLowerCase(), query)) {
                entries.add(entry);
            }
        }
        // 有分组时先按分组标签排序，组内再按常规排序；否则只按常规排序。
        if (groupBy != null && !groupBy.isEmpty()) {
            entries.sort((a, b) -> {
                int cmp = groupLabel(a).compareToIgnoreCase(groupLabel(b));
                if (cmp != 0) {
                    return cmp;
                }
                return compareEntries(a, b);
            });
        } else {
            sortEntries(entries);
        }
        return entries;
    }

    /** 组内排序（文件夹优先可配置 + 自然排序名称）。 */
    private int compareEntries(VirtualFileSystem.Entry a, VirtualFileSystem.Entry b) {
        if (foldersFirst && a.folder != b.folder) {
            return a.folder ? -1 : 1;
        }
        return VirtualFileSystem.naturalCompare(a.name, b.name);
    }

    private String displayName(VirtualFileSystem.Entry entry) {
        if (entry.folder || showExtensions) {
            return entry.name;
        }
        int dot = entry.name.lastIndexOf('.');
        if (dot > 0) {
            return entry.name.substring(0, dot);
        }
        return entry.name;
    }

    private String groupLabel(VirtualFileSystem.Entry entry) {
        return switch (groupBy == null ? "" : groupBy) {
            case "name" -> entry.name.isEmpty() ? "" : String.valueOf(firstLetter(entry.name.charAt(0)));
            case "modified" -> modifiedGroup(entry.modified);
            case "created" -> modifiedGroup(entry.created);
            case "type" -> entry.type;
            case "author" -> entry.author == null ? "未知" : entry.author;
            case "size" -> entry.folder ? "文件夹" : sizeGroup(entry.size);
            default -> "";
        };
    }

    /** 名称分组的首字母：中文按 GB2312 区位推拼音首字母，其它字符取大写首字母。 */
    private static char firstLetter(char ch) {
        if (ch >= 0x4e00 && ch <= 0x9fa5) {
            try {
                byte[] gbk = String.valueOf(ch).getBytes("GBK");
                if (gbk.length == 2) {
                    int code = ((gbk[0] & 0xff) << 8) | (gbk[1] & 0xff);
                    if (code >= 0xB0A1 && code < 0xB0C5) return 'A';
                    if (code < 0xB2C1) return 'B';
                    if (code < 0xB4EE) return 'C';
                    if (code < 0xB6EA) return 'D';
                    if (code < 0xB7A2) return 'E';
                    if (code < 0xB8C1) return 'F';
                    if (code < 0xB9FE) return 'G';
                    if (code < 0xBBF7) return 'H';
                    if (code < 0xBFA6) return 'J';
                    if (code < 0xC0AC) return 'K';
                    if (code < 0xC2E8) return 'L';
                    if (code < 0xC4C3) return 'M';
                    if (code < 0xC5B6) return 'N';
                    if (code < 0xC5BE) return 'O';
                    if (code < 0xC6DA) return 'P';
                    if (code < 0xC8BB) return 'Q';
                    if (code < 0xC8F6) return 'R';
                    if (code < 0xCBFA) return 'S';
                    if (code < 0xCDDA) return 'T';
                    if (code < 0xCEF4) return 'W';
                    if (code < 0xD1B9) return 'X';
                    if (code < 0xD4D1) return 'Y';
                    if (code < 0xD7FA) return 'Z';
                }
            } catch (Exception ignored) {
            }
            return ch;
        }
        return Character.toUpperCase(ch);
    }

    /** 修改日期按「yyyy年M月」分桶。 */
    private static String modifiedGroup(String modified) {
        if (modified == null) {
            return "未知";
        }
        int firstSlash = modified.indexOf('/');
        int secondSlash = modified.indexOf('/', firstSlash + 1);
        if (firstSlash < 0 || secondSlash < 0) {
            return modified;
        }
        try {
            int y = Integer.parseInt(modified.substring(0, firstSlash).trim());
            int m = Integer.parseInt(modified.substring(firstSlash + 1, secondSlash).trim());
            return y + "年" + m + "月";
        } catch (NumberFormatException e) {
            return modified;
        }
    }

    private static String sizeGroup(long size) {
        if (size < 1024L) {
            return "小 (< 1 KB)";
        }
        if (size < 1024L * 1024L) {
            return "中 (1 KB - 1 MB)";
        }
        if (size < 1024L * 1024L * 1024L) {
            return "大 (1 MB - 1 GB)";
        }
        return "超大 (> 1 GB)";
    }

    private void sortEntries(List<VirtualFileSystem.Entry> entries) {
        entries.sort((a, b) -> {
            int cmp;
            if (foldersFirst && a.folder != b.folder) {
                cmp = a.folder ? -1 : 1;
            } else if (a.folder && b.folder) {
                cmp = VirtualFileSystem.naturalCompare(a.name, b.name);
            } else {
                cmp = switch (sortField) {
                    case "modified" -> a.modified.compareTo(b.modified);
                    case "created" -> a.created.compareTo(b.created);
                    case "type" -> a.type.compareToIgnoreCase(b.type);
                    case "author" -> a.author.compareToIgnoreCase(b.author);
                    case "size" -> Long.compare(a.size, b.size);
                    default -> VirtualFileSystem.naturalCompare(a.name, b.name);
                };
            }
            if (cmp == 0) {
                cmp = VirtualFileSystem.naturalCompare(a.name, b.name);
            }
            return sortAsc ? cmp : -cmp;
        });
    }

    private String buildTable(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"table fe-table\" style=\"").append(tableWidthStyle()).append("\">");
        sb.append("<thead><tr style=\"").append(gridTemplateStyle()).append("\">");
        for (int col = 0; col < COL_FIELDS.length; col++) {
            if (columnVisible[col]) {
                sb.append(headerCell(columnLabels()[col], COL_FIELDS[col], col));
            }
        }
        sb.append("</tr></thead><tbody>");
        if (groupBy != null && !groupBy.isEmpty()) {
            String currentGroup = null;
            boolean currentCollapsed = false;
            for (VirtualFileSystem.Entry entry : entries) {
                String label = groupLabel(entry);
                if (!label.equals(currentGroup)) {
                    currentGroup = label;
                    currentCollapsed = collapsedGroups.contains(label);
                    sb.append(groupHeaderHtml(label, currentCollapsed));
                }
                if (!currentCollapsed) {
                    renderTableRow(sb, entry);
                }
            }
        } else {
            for (VirtualFileSystem.Entry entry : entries) {
                renderTableRow(sb, entry);
            }
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /** 分组头（表格行形式，供 details 视图使用）。 */
    private String groupHeaderHtml(String label, boolean collapsed) {
        return "<tr class=\"fe-group-row\" data-group=\"" + escape(label)
                + "\" style=\"grid-template-columns:1fr;\"><td class=\"fe-group-header\">"
                + "<span class=\"fe-group-toggle\">" + (collapsed ? "▸" : "▾") + "</span>"
                + "<span class=\"fe-group-title\">" + escape(label) + "</span>"
                + "</td></tr>";
    }

    /** 分组头（div 形式，供列表/内容/平铺/图标视图使用）。 */
    private String groupHeaderDiv(String label, boolean collapsed) {
        return "<div class=\"fe-group-div\" data-group=\"" + escape(label) + "\">"
                + "<span class=\"fe-group-toggle\">" + (collapsed ? "▸" : "▾") + "</span>"
                + "<span class=\"fe-group-title\">" + escape(label) + "</span>"
                + "</div>";
    }

    private String headerCell(String label, String field, int col) {
        boolean active = sortField.equals(field);
        return "<th class=\"sortable" + (active ? " sort-active" : "") + "\" data-sort=\"" + field + "\" data-col=\"" + col + "\">"
                + label
                + "<span class=\"fe-col-resizer\" data-col=\"" + col + "\"></span></th>";
    }

    private void bindTableHeaders() {
        for (Element th : document.querySelectorAll(".fe-table th[data-sort], .fe-list-header-cell[data-sort]")) {
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
        for (Element groupRow : document.querySelectorAll(".fe-group-row[data-group], .fe-group-div[data-group]")) {
            String group = groupRow.getAttribute("data-group");
            Element toggle = groupRow.querySelector(".fe-group-toggle");
            Element title = groupRow.querySelector(".fe-group-title");
            if (toggle != null) {
                toggle.addEventListener("click", e -> {
                    e.stopPropagation();
                    if (group == null) {
                        return;
                    }
                    if (collapsedGroups.contains(group)) {
                        collapsedGroups.remove(group);
                    } else {
                        collapsedGroups.add(group);
                    }
                    renderContent();
                });
            }
            if (title != null) {
                title.addEventListener("click", e -> {
                    e.stopPropagation();
                    if (group != null) {
                        selectGroup(group);
                    }
                });
            }
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
        int index = renderedEntryList.indexOf(entry);
        if (index < 0) {
            return;
        }

        sb.append("<tr class=\"fe-item\" data-id=\"e").append(index).append("\"")
          .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
          .append(folderPathAttr(entry))
          .append(" style=\"").append(gridTemplateStyle()).append("\">");
        for (int col = 0; col < COL_FIELDS.length; col++) {
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
                  .append("<span class=\"fe-name-text\" data-name=\"1\">").append(escape(displayName(entry))).append("</span>")
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
            case 4 -> {
                return "<td>" + escape(entry.created) + "</td>";
            }
            case 5 -> {
                return "<td>" + escape(entry.author) + "</td>";
            }
            default -> {
                return "<td></td>";
            }
        }
    }

    private String gridTemplateStyle() {
        StringBuilder sb = new StringBuilder("grid-template-columns:");
        boolean first = true;
        for (int col = 0; col < COL_FIELDS.length; col++) {
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
        for (int col = 0; col < COL_FIELDS.length; col++) {
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
            setStatus(t("informationera.fe.status_at_least_one_col"));
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
        columnWidths.set(4, 140.0);
        columnWidths.set(5, 100.0);
        renderContent();
    }

    private void showHeaderContextMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        for (int col = 0; col < COL_FIELDS.length; col++) {
            int c = col;
            entries.add(CtxEntry.actionChecked(columnLabels()[col], columnVisible[col], () -> toggleColumn(c)));
        }
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.show_all_columns"), this::showAllColumns));
        entries.add(CtxEntry.action(t("informationera.fe.reset_widths"), this::resetColumnWidths));
        openContextMenu(x, y, entries);
    }

    private void showAllColumns() {
        for (int col = 0; col < columnVisible.length; col++) {
            columnVisible[col] = true;
        }
        renderContent();
    }

    /**
     * 渲染条目列表。启用了分组时，每个分组是一个独立 section：分组头 + 组内条目容器，
     * 组内条目从各自的第一列重新排布；未分组时直接渲染到 itemsClass 容器。
     */
    private void appendEntriesWithGroups(StringBuilder sb, String itemsClass, List<VirtualFileSystem.Entry> entries,
                                         java.util.function.Consumer<VirtualFileSystem.Entry> renderer) {
        if (groupBy != null && !groupBy.isEmpty()) {
            java.util.Map<String, List<VirtualFileSystem.Entry>> groups = new java.util.LinkedHashMap<>();
            for (VirtualFileSystem.Entry entry : entries) {
                groups.computeIfAbsent(groupLabel(entry), k -> new ArrayList<>()).add(entry);
            }
            for (var groupEntry : groups.entrySet()) {
                String label = groupEntry.getKey();
                boolean collapsed = collapsedGroups.contains(label);
                sb.append("<div class=\"fe-group-section\">");
                sb.append(groupHeaderDiv(label, collapsed));
                if (!collapsed) {
                    sb.append("<div class=\"").append(itemsClass).append("\">");
                    for (VirtualFileSystem.Entry entry : groupEntry.getValue()) {
                        renderer.accept(entry);
                    }
                    sb.append("</div>");
                }
                sb.append("</div>");
            }
        } else {
            sb.append("<div class=\"").append(itemsClass).append("\">");
            for (VirtualFileSystem.Entry entry : entries) {
                renderer.accept(entry);
            }
            sb.append("</div>");
        }
    }

    private String buildGrid(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        appendEntriesWithGroups(sb, "fe-grid", entries, entry -> {
            int index = renderedEntryList.indexOf(entry);
            sb.append("<div class=\"fe-item fe-grid-item\" data-id=\"e").append(index).append("\"")
              .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
              .append(folderPathAttr(entry)).append(">");
            if (checkboxes) {
                sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
            }
            sb.append("<div class=\"fe-icon\">").append(iconFor(entry, iconSize)).append("</div>");
            sb.append("<div class=\"fe-name\" data-name=\"1\">").append(escape(displayName(entry))).append("</div>");
            if ("本地磁盘".equals(entry.type)) {
                sb.append(driveBar(entry));
            }
            sb.append("</div>");
        });
        return sb.toString();
    }

    private String buildTiles(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        appendEntriesWithGroups(sb, "fe-tiles", entries, entry -> {
            int index = renderedEntryList.indexOf(entry);
            sb.append("<div class=\"fe-item fe-tile-item\" data-id=\"e").append(index).append("\"")
              .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
              .append(folderPathAttr(entry)).append(">");
            if (checkboxes) {
                sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
            }
            sb.append("<div class=\"fe-icon\">").append(iconFor(entry, 48)).append("</div>");
            sb.append("<div class=\"fe-tile-meta\">");
            sb.append("<div class=\"fe-tile-name\" data-name=\"1\">").append(escape(displayName(entry))).append("</div>");
            sb.append("<div class=\"fe-tile-line\">").append(escape(entry.type)).append("</div>");
            sb.append("<div class=\"fe-tile-line\">").append(entry.folder ? "文件夹" : formatSize(entry.size)).append("</div>");
            if ("本地磁盘".equals(entry.type)) {
                sb.append(driveBar(entry));
            } else {
                sb.append("<div class=\"fe-tile-line\">").append(escape(entry.modified)).append("</div>");
            }
            sb.append("</div></div>");
        });
        return sb.toString();
    }

    /** 列表视图：小图标 + 名称，多列紧凑排列，无表头。 */
    private String buildList(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        appendEntriesWithGroups(sb, "fe-list", entries, entry -> {
            int index = renderedEntryList.indexOf(entry);
            sb.append("<div class=\"fe-item fe-list-item\" data-id=\"e").append(index).append("\"")
              .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
              .append(folderPathAttr(entry)).append(">");
            if (checkboxes) {
                sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
            }
            sb.append("<span class=\"fe-icon\">").append(iconFor(entry, 16)).append("</span>");
            sb.append("<span class=\"fe-name-text\" data-name=\"1\">").append(escape(displayName(entry))).append("</span>");
            sb.append("</div>");
        });
        return sb.toString();
    }

    /** 内容视图：单列，每行小图标 + 名称 + 类型 + 大小 + 修改日期，无表头。 */
    private String buildContent(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        appendEntriesWithGroups(sb, "fe-content-list", entries, entry -> {
            int index = renderedEntryList.indexOf(entry);
            sb.append("<div class=\"fe-item fe-content-item\" data-id=\"e").append(index).append("\"")
              .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
              .append(folderPathAttr(entry)).append(">");
            if (checkboxes) {
                sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
            }
            sb.append("<span class=\"fe-icon\">").append(iconFor(entry, 16)).append("</span>");
            sb.append("<div class=\"fe-content-meta\">");
            sb.append("<div class=\"fe-content-name\" data-name=\"1\">").append(escape(displayName(entry))).append("</div>");
            sb.append("<div class=\"fe-content-details\">")
              .append(escape(entry.type)).append(" · ")
              .append(entry.folder ? "文件夹" : formatSize(entry.size)).append(" · ")
              .append(escape(entry.modified)).append("</div>");
            sb.append("</div></div>");
        });
        return sb.toString();
    }

    private String folderPathAttr(VirtualFileSystem.Entry entry) {
        return (entry.folder && entry.childKey != null) ? " data-path=\"" + escape(entry.childKey) + "\"" : "";
    }

    private void bindItemListeners() {
        for (Element item : document.querySelectorAll(".fe-item")) {
            item.addEventListener("click", e -> handleItemClick(item, e));
            item.addEventListener("contextmenu", e -> {
                if (FileExplorerWindow.consumeContextMenuSuppression()) {
                    return;
                }
                e.stopPropagation();
                if (e instanceof MouseEvent me) {
                    showItemContextMenu(item, entryFor(item), me.clientX, me.clientY);
                }
            });
            item.addEventListener("mousedown", e -> {
                if (e instanceof MouseEvent me) {
                    // Shift / Ctrl 点击是选择操作，不进入拖拽，也不改变选中。
                    if (me.shiftKey || me.controlKey) {
                        return;
                    }
                    VirtualFileSystem.Entry entry = entryFor(item);
                    if (entry == null) {
                        return;
                    }
                    // AUI 的 MouseEvent.button 使用 GLFW 按钮号：0=左键，1=右键，2=中键。
                    if (me.button == 0 || me.button == 1) {
                        boolean right = me.button == 1;
                        // 若按下项已在选中集内，则拖拽整个选中集（多选拖拽）；否则仅拖拽当前项。
                        List<VirtualFileSystem.Entry> dragList;
                        if (selected.contains(entry)) {
                            dragList = new ArrayList<>(selected);
                        } else {
                            selected.clear();
                            selected.add(entry);
                            dragList = new ArrayList<>(selected);
                            updateSelectionVisuals();
                            updateStatusBar();
                        }
                        FileExplorerWindow.beginFileDrag(this, dragList, currentKey, right);
                    }
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
        int clickCount = event instanceof MouseEvent me ? me.clickCount : 1;

        // 双击（系统合并，clickCount>=2）：打开（Windows 行为）。
        if (!ctrl && !shift && clickCount >= 2) {
            openEntry(entry);
            return;
        }

        // 慢双击重命名：仅当点击已选中项的文件名标签时进入 label edit（Explorer 行为）。
        // 两次单击间隔必须在 500ms 以上（否则会被系统合并为双击，走上面的打开逻辑）。
        long now = System.currentTimeMillis();
        if (clickCount == 1 && !ctrl && !shift && selected.size() == 1
                && selected.contains(entry) && isNameClick(event)
                && lastClickEntry == entry && now - lastClickTime > 500L) {
            beginRename(item, entry);
            return;
        }
        if (clickCount == 1 && !ctrl && !shift) {
            lastClickEntry = entry;
            lastClickTime = now;
        } else {
            lastClickEntry = null;
            lastClickTime = 0L;
        }

        if (shift && selectionAnchor >= 0) {
            selected.clear();
            int lo = Math.min(selectionAnchor, index);
            int hi = Math.max(selectionAnchor, index);
            for (int i = lo; i <= hi; i++) {
                selected.add(renderedEntryList.get(i));
            }
            // 保持锚点不变，只有最后点击位置移动。
        } else if (ctrl) {
            if (!selected.remove(entry)) {
                selected.add(entry);
            }
            selectionAnchor = index;
        } else {
            selected.clear();
            selected.add(entry);
            selectionAnchor = index;
        }
        lastClickedIndex = index;

        updateSelectionVisuals();
        updateStatusBar();
    }

    /** 判断点击是否落在条目文件名区域（带 data-name 标记的元素内）。 */
    private boolean isNameClick(Event event) {
        if (!(event instanceof MouseEvent me)) {
            return false;
        }
        Element target = me.target instanceof Element ? (Element) me.target : null;
        return target != null && target.closest("[data-name]") != null;
    }

    private VirtualFileSystem.Entry entryFor(Element item) {
        int index = indexOf(item);
        return index >= 0 && index < renderedEntryList.size() ? renderedEntryList.get(index) : null;
    }

    private boolean isCut(VirtualFileSystem.Entry entry) {
        if (!ExplorerSession.clipboardCut) {
            return false;
        }
        for (ExplorerSession.ClipItem item : ExplorerSession.clipboard) {
            if (item.entry() == entry) {
                return true;
            }
        }
        return false;
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
            // 剪切的条目虚化显示。
            boolean isCut = entry != null && isCut(entry);
            if (isCut) {
                item.getClassList().add("cut");
            } else {
                item.getClassList().remove("cut");
            }
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
                + "\n" + t("informationera.fe.detail_type", entry.type)
                + "\n" + (entry.folder ? t("informationera.fe.folder") : t("informationera.fe.detail_size", formatSize(entry.size)))
                + "\n" + t("informationera.fe.detail_modified_short", entry.modified);
    }

    private void hideTooltip() {
        if (tooltip != null) {
            tooltip.setInlineStyleProperty("display", "none");
        }
    }

    private void showTabGhost(String title, double x, double y) {
        if (dragGhost == null) {
            return;
        }
        dragGhost.setInnerHTML("<div class=\"fe-ghost-row\">" + folderIcon(16) + "<span>" + escape(title) + "</span></div>");
        moveDragGhost(x, y);
        dragGhost.setInlineStyleProperty("display", "flex");
    }

    private void moveDragGhost(double x, double y) {
        if (dragGhost == null) {
            return;
        }
        dragGhost.setInlineStyleProperty("left", px(x + 12));
        dragGhost.setInlineStyleProperty("top", px(y + 12));
    }

    private void hideDragGhost() {
        if (dragGhost != null) {
            dragGhost.setInlineStyleProperty("display", "none");
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
        setText("fe-item-count", t("informationera.fe.status_items", renderedEntryList.size()));
        setText("fe-selection-count", selected.isEmpty() ? "" : t("informationera.fe.status_selected", selected.size()) + selectedSizeText());
    }

    private void updateStatusViewButtons() {
        Element details = document.getElementById("fe-view-details");
        Element thumb = document.getElementById("fe-view-thumb");
        if (details != null) {
            if ("details".equals(viewMode)) {
                details.getClassList().add("active");
            } else {
                details.getClassList().remove("active");
            }
        }
        if (thumb != null) {
            if ("large".equals(viewMode) || "xlarge".equals(viewMode)) {
                thumb.getClassList().add("active");
            } else {
                thumb.getClassList().remove("active");
            }
        }
    }

    private void updateSearchPlaceholder() {
        Element search = document.getElementById("fe-search");
        if (search == null) {
            return;
        }
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        String name = folder != null ? folder.name : t("informationera.fe.title");
        search.setPlaceholder(t("informationera.fe.search_placeholder", name));
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
        setButtonEnabled("fe-cmd-paste", !ExplorerSession.clipboard.isEmpty());
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

    private void createShortcut(VirtualFileSystem.Entry entry) {
        pushUndo(fs.createShortcut(currentKey, currentKey, entry));
        afterMutation();
        setStatus(t("informationera.fe.status_shortcut_created", entry.name));
    }

    private void copyPath(VirtualFileSystem.Entry entry) {
        String path = pathText(currentKey) + " > " + entry.name;
        Minecraft.getInstance().keyboardHandler.setClipboard(path);
        setStatus(t("informationera.fe.status_path_copied", path));
    }

    private void sendTo(String targetKey, VirtualFileSystem.Entry entry) {
        VirtualFileSystem.Folder target = fs.folder(targetKey);
        if (target == null) {
            setStatus(t("informationera.fe.status_path_not_found", targetKey));
            return;
        }
        pushUndo(fs.duplicateInto(targetKey, entry));
        afterMutation();
        setStatus(t("informationera.fe.status_sent_to", target.name));
    }

    // ---------- 工具方法 ----------

    static String iconFor(VirtualFileSystem.Entry entry, int size) {
        if ("本地磁盘".equals(entry.type)) {
            return driveIcon(size);
        }
        return entry.folder ? folderIcon(size) : fileIcon(size);
    }

    /** 驱动器容量条（虚拟演示数据，按名称哈希生成固定占比）。 */
    private static String driveBar(VirtualFileSystem.Entry entry) {
        int used = Math.abs(entry.name.hashCode()) % 95 + 1;
        return "<div class=\"fe-drive-bar\"><div class=\"fe-drive-bar-fill\" style=\"width:" + used + "%\"></div></div>"
                + "<div class=\"fe-drive-bar-text\">" + t("informationera.fe.status_drive_used", used) + "</div>";
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

    static String px(double value) {
        return Math.round(value) + "px";
    }

    static String escape(String value) {
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
