package cc.sighs.informationera.client;

import cc.sighs.informationera.api.client.ExplorerColumn;
import cc.sighs.informationera.api.client.ExplorerColumnContext;
import cc.sighs.informationera.util.ItemStacks;
import com.sighs.apricityui.element.Item;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.event.KeyEvent;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.layout.Size;
import cc.sighs.informationera.disk.DiskFolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import static cc.sighs.informationera.client.FileExplorerText.t;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 文件资源管理器窗口的全部交互逻辑：
 * 多标签页、窗口拖拽/缩放、目录树、排序与视图、面包屑、右键菜单、框选、
 * 剪切/复制/粘贴/删除/重命名/新建/撤销、拖拽移动复制、复选框、悬停元数据、键盘快捷键。
 */
public final class FileExplorerController {

    private static final double DEFAULT_WIDTH = 980;
    private static final double DEFAULT_HEIGHT = 600;
    private static final double TITLEBAR_HEIGHT = 40;
    private static final double MENU_WIDTH = 230;
    /** 线性视图（details/list/content）每行高度，用于虚拟滚动窗口计算。 */
    private static final double ROW_HEIGHT = 34.0;
    /** 分组头行在虚拟滚动下的固定高度。 */
    private static final double GROUP_HEADER_HEIGHT = 36.0;
    /** 虚拟滚动缓冲行数。 */
    private static final int VIRTUAL_BUFFER = 12;

    /** 重命名输入框的左右内边距（与 CSS 的 padding 保持一致）。 */
    private static final double RENAME_PADDING_X = 6.0;
    /**
     * 重命名输入框的最小高度。
     *
     * <p>要能装下「文字本体 + 上下 padding + 边框」：字号 13px 的正文约 17px，
     * 上下 padding 各 3px、边框各 2px，合计约 27px。低于这个值文字会被裁。
     */
    private static final double RENAME_MIN_HEIGHT = 28.0;

    /** 「展开所有子文件夹」时递归收集的最大层数，与磁盘允许的最大嵌套深度一致。 */
    private static final int MAX_FILTER_DEPTH = 64;

    private static final String NAME_COLUMN_ID = ExplorerViewState.NAME_COLUMN_ID;

    private static FileExplorerController ACTIVE;

    /** 每个标签页独立的状态。 */
    static final class Tab {
        String title = t("informationera.fe.title");
        String currentKey = DiskFolder.ROOT_ID;
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

    /**
     * 当前视图模型。
     *
     * <p>这个引用**会被替换**：服务端每次推回权威状态都会生成一个新的
     * {@code VirtualFileSystem}，本窗口必须切到新实例上，否则会一直渲染最初那张空盘。
     * 所以这里的字段不能是 final，改由 {@link #onViewReplaced} 更新。
     */
    private VirtualFileSystem fs;
    private Document document;

    private Element window;
    private Element capture;
    private Element selectionRect;
    private Element tooltip;
    /** 提示框当前是否可见。用来让 {@link #tickTooltip()} 只在需要时做校验。 */
    private boolean tooltipVisible;
    private Element dragGhost;

    final List<Tab> tabs = new ArrayList<>();
    int activeTab = 0;

    private double windowX;
    private double windowY;
    private double windowWidth = DEFAULT_WIDTH;
    private double windowHeight = DEFAULT_HEIGHT;
    private final ExplorerWindowMode mode = new ExplorerWindowMode();

    private String currentKey = DiskFolder.HOST_ID;
    /**
     * 本窗口上一次套用视图时使用的磁盘身份（储存卡 uuid）。
     *
     * <p>设备状态到达/换卡后，{@link #onViewReplaced} 会用它检测磁盘是否变了：变了就重新
     * 读取当前文件夹在新磁盘上的视图，而不是沿用上一张卡的布局。
     */
    private String lastDiskIdentity;
    private final Set<VirtualFileSystem.Entry> selected = new HashSet<>();
    private final Set<VirtualFileSystem.Entry> checkedSet = new HashSet<>();
    private int lastCheckedIndex = -1;
    private final List<VirtualFileSystem.Entry> renderedEntryList = new ArrayList<>();
    // 最上层那个「玩家名」目录默认展开，一进来就能看到里面的磁盘。
    private final Set<String> expandedSidebar = new HashSet<>(Set.of(DiskFolder.HOST_ID));
    // 「固定到快速访问」的状态存在客户端配置里（ExplorerPreferences），不放在窗口实例上——
    // 否则关掉窗口、重启客户端就全丢了。
    /** 当前列设置（稳定命名空间 ID -> 宽度/显隐）。 */
    private final java.util.Map<String, ExplorerViewState.ColumnSetting> columnSettings = new java.util.LinkedHashMap<>();
    /** 当前列序（稳定命名空间 ID）；隐藏列与暂未注册的 ID 也保留。 */
    private final List<String> columnOrder = new ArrayList<>();
    /** 列选择对话框的临时草稿；取消时不影响已保存状态。 */
    private final List<ColumnDraft> columnDrafts = new ArrayList<>();
    /** 打开对话框时的快照：确定时只写用户实际改过的列，避免覆盖其它窗口的修改。 */
    private ExplorerViewState columnManagerBase;
    private boolean columnManagerOpen;

    private String viewMode = "details";
    private int iconSize = 40;
    private String sortField = "name";
    private boolean sortAsc = true;
    private boolean foldersFirst = true;
    private boolean showNav = true;
    private boolean checkboxes = false;
    private boolean previewOpen = false;
    /** 筛选窗格与预览窗格共用右侧位置，两者互斥。 */
    private boolean filterOpen = false;
    private boolean filterDialogOpen = false;
    private ExplorerFilterSelection.Kind filterDialogKind = ExplorerFilterSelection.Kind.TAG;
    /** 当前筛选弹窗是否切换到“模板”模式；五段结构复用同一套 DOM。 */
    private boolean filterDialogTemplates = false;
    /** 当前完整应用的模板名称；手工改动任一规则后清空。 */
    private String appliedFilterTemplate;
    /** 保存模板的命名弹窗状态。 */
    private boolean templateSaveDialogOpen = false;
    private boolean templateSaveConfirmOverwrite = false;
    private String templateSaveConfirmName;
    /** 当前筛选选择；关闭窗格后仍保留，重新打开时继续使用，但不参与关闭状态下的显示。 */
    private final ExplorerFilterSelection filterSelection = new ExplorerFilterSelection();
    /** 当前范围内每类条件的候选全集；范围/视图变化时整体失效。 */
    private final java.util.Map<ExplorerFilterSelection.Kind, List<String>> filterCandidateCache =
            new java.util.EnumMap<>(ExplorerFilterSelection.Kind.class);
    /** 已按最新语言重绘筛选界面的版本。 */
    private int filterLanguageVersion = -1;
    /** 候选缓存对应的语言版本；单独记录，避免取候选时提前吞掉界面重绘。 */
    private int filterCandidateLanguageVersion = -1;
    private int previewSize = 64;
    /** 预览图标尺寸的上下限与每次缩放的步长（像素）。 */
    private static final int PREVIEW_SIZE_MIN = 16;
    private static final int PREVIEW_SIZE_MAX = 256;
    private static final int PREVIEW_SIZE_STEP = 16;
    /** 预览里 NBT 查看器是否展开。折叠状态存在这里而不是 DOM 上：换选中项会重建这一段。 */
    private boolean nbtExpanded;
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
    /** 本次按下-松开之间是否真的移动过：单纯点击不应改写窗口持久化。 */
    private boolean interactionMoved;
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
    private String resizeColumnId;
    private double colResizeStartX;
    private double colResizeStartWidth;
    private double navWidth = 240;
    private double previewWidth = 240;

    private List<VirtualFileSystem.Entry> rightDragEntries = new ArrayList<>();
    private String rightDragSourceKey;
    private String rightDropTargetKey;

    // 冲突对话框的待处理状态。
    private List<VirtualFileSystem.Entry> conflictEntries = new ArrayList<>();
    private List<String> conflictNames = new ArrayList<>();
    private String conflictSourceKey;
    private String conflictTargetKey;
    private boolean conflictCopy;

    private int tabDragIndex = -1;
    private boolean tabDragActive = false;
    private boolean tabDragMoved = false;
    private double tabDragStartX;
    private double tabDragStartY;

    private VirtualFileSystem.Entry renamingEntry;

    private int addressSuggestIndex = -1;
    private int addressSuggestCount = 0;
    private List<String> addressSuggestCandidates = null;

    /** 列选择对话框中的一行草稿。 */
    private static final class ColumnDraft {
        final String id;
        boolean visible;
        double width;

        private ColumnDraft(String id, boolean visible, double width) {
            this.id = id;
            this.visible = visible;
            this.width = width;
        }
    }

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
        return mode.isMinimized();
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
        return mode.isMinimized();
    }

    /** 供任务栏跳转列表等外部入口使用，直接导航到 key。 */
    void navigateToExternal(String key) {
        if (fs.folder(key) == null) {
            return;
        }
        currentKey = key;
        afterNavigate();
    }

    public void closeWindow() {
        hideTooltip();
        closeContextMenu();
        closeColumnManager();
        closeFilterDialog();
        closeTemplateSaveDialog();
        // 只有“最近一次被玩家亲手调整过几何”的窗口才有资格改写持久化记录：
        // 关掉一个没动过的旧窗口不能把玩家刚调好的位置覆盖回去。
        if (FileExplorerWindow.isGeometryOwner(this)) {
            rememberWindowFrame();
        }
        FileExplorerWindow.unregister(this);
        if (document != null) {
            document.remove();
            document = null;
        }
        // 窗口已经收掉之后再落盘：即使配置写入失败，也不会留下半关的窗口。
        ExplorerPreferences.flush();
    }

    /** 玩家主动调整了窗口几何（拖动/缩放/最大化/从最大化拖出）：登记所有权并立即落盘。 */
    private void commitGeometry() {
        FileExplorerWindow.markGeometryOwner(this);
        rememberWindowFrame();
    }

    /** 当前可见矩形。 */
    private ExplorerWindowGeometry.Frame visibleFrame() {
        return new ExplorerWindowGeometry.Frame(windowX, windowY, windowWidth, windowHeight);
    }

    /** 把当前窗口的正常矩形与最大化状态写进窗口持久化（不写标签页/目录等会话状态）。 */
    private void rememberWindowFrame() {
        if (document == null) {
            return;
        }
        boolean maximized = mode.isMaximized();
        ExplorerWindowGeometry.Frame normal = mode.normalFrame(visibleFrame());
        ExplorerWindowState.remember(ExplorerWindowGeometry.fit(normal, document.getViewportSize()), maximized);
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
        // 筛选窗格是窗口会话状态；重新挂载时从干净状态开始，避免旧条件落到新视图上。
        filterOpen = false;
        filterDialogOpen = false;
        filterDialogTemplates = false;
        appliedFilterTemplate = null;
        templateSaveDialogOpen = false;
        templateSaveConfirmOverwrite = false;
        templateSaveConfirmName = null;
        filterSelection.clear();
        filterSelection.includeSubfolders = false;
        filterSelection.dedupe = false;
        filterCandidateCache.clear();
        // 先恢复全局显示习惯与当前文件夹的视图，后面的渲染才有正确的初始值。
        applyDisplayState(ExplorerPreferences.display());
        applyViewState(ExplorerPreferences.viewState(currentKey), false);
        lastDiskIdentity = ExplorerPreferences.diskIdentity();

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
        // 恢复上次关闭时的位置/尺寸；同一会话再开窗口时层叠错开，避免完全重叠。
        ExplorerWindowGeometry.Frame saved = ExplorerWindowState.frame();
        ExplorerWindowGeometry.Frame normal = saved == null
                ? ExplorerWindowGeometry.initialFrame(viewport, FileExplorerWindow.windowCount())
                : ExplorerWindowGeometry.cascade(ExplorerWindowGeometry.fit(saved, viewport),
                        viewport, FileExplorerWindow.windowCount());
        if (ExplorerWindowState.maximized()) {
            // 上次是最大化关闭：先记住普通还原矩形，再按当前视口铺满。
            mode.startMaximized(normal);
            applyFrame(ExplorerWindowGeometry.maximizedFrame(viewport));
        } else {
            applyFrame(normal);
        }
        applyWindowGeometry();
        updateMaximizeControl();
    }

    /** 把矩形写进窗口字段（只改状态，DOM 由 {@link #applyWindowGeometry()} 统一写入）。 */
    private void applyFrame(ExplorerWindowGeometry.Frame frame) {
        windowX = frame.x();
        windowY = frame.y();
        windowWidth = frame.width();
        windowHeight = frame.height();
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

    /**
     * 视口变化（游戏窗口尺寸/宽高比、分辨率、GUI 缩放、页面缩放）后重新贴合。
     *
     * <p>最大化窗口始终铺满新视口；正常窗口只缩不放，并整体夹回视口内，
     * 避免右下角和状态栏跑到看不见的地方。
     */
    void adaptToViewport() {
        if (document == null || window == null) {
            return;
        }
        Size viewport = document.getViewportSize();
        ExplorerWindowGeometry.Frame target = mode.adapt(visibleFrame(), viewport);
        if (Math.abs(target.x() - windowX) < 0.01 && Math.abs(target.y() - windowY) < 0.01
                && Math.abs(target.width() - windowWidth) < 0.01
                && Math.abs(target.height() - windowHeight) < 0.01) {
            return;
        }
        applyFrame(target);
        applyWindowGeometry();
    }

    /** 把 HTML 里静态命令栏文案替换为当前语言。 */
    private void localizeStaticLabels() {
        setCmdLabel("fe-new", t("informationera.fe.new"));
        setCmdLabel("fe-sort", t("informationera.fe.sort"));
        setCmdLabel("fe-layout", t("informationera.fe.layout"));
        setCmdLabel("fe-filter", t("informationera.fe.filter"));
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
        bindToolButton("fe-filter", this::toggleFilter);
        bindToolButton("fe-preview", this::togglePreview);
        bindToolButton("fe-view-details", () -> setViewMode("details"));
        bindToolButton("fe-view-thumb", () -> setViewMode("large"));

        bindMenuButton("fe-new", this::showNewMenu);
        bindMenuButton("fe-sort", this::showSortMenu);
        bindMenuButton("fe-layout", this::showLayoutMenu);

        bindButtonHint("fe-min", t("informationera.fe.min"));
        bindButtonHint("fe-max", this::maximizeHint);
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
        bindButtonHint("fe-filter", t("informationera.fe.filter"));
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

        // 属性对话框：关闭按钮 / 遮罩 / Esc。
        Element propsClose = document.getElementById("fe-properties-close");
        if (propsClose != null) {
            propsClose.addEventListener("click", e -> closeProperties());
        }
        Element propsOverlay = document.getElementById("fe-properties-overlay");
        if (propsOverlay != null) {
            propsOverlay.addEventListener("click", e -> closeProperties());
        }
        document.addEventListener("keydown", e -> {
            if (e instanceof KeyEvent ke && "Escape".equalsIgnoreCase(ke.key)) {
                Element dialog = document.getElementById("fe-properties-dialog");
                if (dialog != null && "none" != dialog.getInlineStylePropertyValue("display")) {
                    closeProperties();
                }
            }
        });

        // 列选择对话框：关闭 / 遮罩 / 确定 / 取消 / Esc。
        Element columnsClose = document.getElementById("fe-columns-close");
        if (columnsClose != null) {
            columnsClose.addEventListener("click", e -> closeColumnManager());
        }
        Element columnsCancel = document.getElementById("fe-columns-cancel");
        if (columnsCancel != null) {
            columnsCancel.addEventListener("click", e -> closeColumnManager());
        }
        Element columnsOk = document.getElementById("fe-columns-ok");
        if (columnsOk != null) {
            columnsOk.addEventListener("click", e -> applyColumnManager());
        }
        Element columnsOverlay = document.getElementById("fe-columns-overlay");
        if (columnsOverlay != null) {
            columnsOverlay.addEventListener("click", e -> closeColumnManager());
        }
        document.addEventListener("keydown", e -> {
            if (e instanceof KeyEvent ke && "Escape".equalsIgnoreCase(ke.key) && columnManagerOpen) {
                closeColumnManager();
            }
        });

        // 筛选条件弹窗：关闭 / 遮罩 / Esc。
        Element filterClose = document.getElementById("fe-filter-dialog-close");
        if (filterClose != null) {
            filterClose.addEventListener("click", e -> closeFilterDialog());
        }
        Element filterOverlay = document.getElementById("fe-filter-dialog-overlay");
        if (filterOverlay != null) {
            filterOverlay.addEventListener("click", e -> closeFilterDialog());
        }
        document.addEventListener("keydown", e -> {
            if (e instanceof KeyEvent ke && "Escape".equalsIgnoreCase(ke.key) && filterDialogOpen) {
                closeFilterDialog();
            }
        });

        // 保存模板的命名弹窗：关闭 / 遮罩 / 取消 / 确定 / 输入框键盘。
        Element templateSaveClose = document.getElementById("fe-template-save-close");
        if (templateSaveClose != null) {
            templateSaveClose.addEventListener("click", e -> closeTemplateSaveDialog());
        }
        Element templateSaveCancel = document.getElementById("fe-template-save-cancel");
        if (templateSaveCancel != null) {
            templateSaveCancel.addEventListener("click", e -> closeTemplateSaveDialog());
        }
        Element templateSaveOverlay = document.getElementById("fe-template-save-overlay");
        if (templateSaveOverlay != null) {
            templateSaveOverlay.addEventListener("click", e -> closeTemplateSaveDialog());
        }
        Element templateSaveOk = document.getElementById("fe-template-save-ok");
        if (templateSaveOk != null) {
            templateSaveOk.addEventListener("click", e -> {
                // 双击的第二下不当作“再次确认覆盖”，避免手快双击直接吞掉同名模板。
                if (e instanceof MouseEvent me && me.clickCount >= 2) {
                    return;
                }
                saveFilterTemplateFromDialog();
            });
        }
        Element templateSaveName = document.getElementById("fe-template-save-name");
        if (templateSaveName != null) {
            templateSaveName.addEventListener("input", e -> onTemplateSaveNameChanged());
            templateSaveName.addEventListener("keyup", e -> {
                if (e instanceof KeyEvent ke && "Escape".equals(ke.key)) {
                    return;
                }
                onTemplateSaveNameChanged();
            });
            templateSaveName.addEventListener("keydown", e -> {
                e.stopPropagation();
                if (!(e instanceof KeyEvent ke)) {
                    return;
                }
                if ("Enter".equals(ke.key)) {
                    saveFilterTemplateFromDialog();
                } else if ("Escape".equals(ke.key)) {
                    closeTemplateSaveDialog();
                }
            });
        }
        document.addEventListener("keydown", e -> {
            if (e instanceof KeyEvent ke && "Escape".equalsIgnoreCase(ke.key) && templateSaveDialogOpen) {
                closeTemplateSaveDialog();
            }
        });

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
            boolean globalTabDrag = FileExplorerWindow.isTabDragActive();
            if (tabDragIndex >= 0 && !tabDragActive && !globalTabDrag) {
                double dx = me.clientX - tabDragStartX;
                double dy = me.clientY - tabDragStartY;
                if (dx * dx + dy * dy > 25) {
                    tabDragActive = true;
                    tabDragMoved = true;
                    showTabGhost(tabs.get(tabDragIndex).title, me.clientX, me.clientY);
                }
            }
            if (tabDragActive && tabDragIndex >= 0 && !globalTabDrag) {
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

            if (tabDragActive && tabDragIndex >= 0 && !FileExplorerWindow.isTabDragActive()) {
                int target = tabIndexAt(me.clientX);
                if (target >= 0 && target != tabDragIndex) {
                    moveTab(tabDragIndex, target);
                }
            }
            if (FileExplorerWindow.isTabDragActive()) {
                // 全局引擎收尾由 FileExplorerWindow.tabDragTick 完成，这里只清理本窗口残留 ghost。
                tabDragIndex = -1;
                tabDragActive = false;
                hideDragGhost();
            } else {
                tabDragIndex = -1;
                tabDragActive = false;
                hideDragGhost();
            }
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
        // 手工改搜索框后，当前规则不再保证与已应用模板完全一致。
        clearAppliedFilterTemplate();
        searchQuery = next;
        updateFilterClearButton();
        renderContent();
    }

    /** 去掉页面级 intercept meta 后，手动拦截窗口上的原生输入。 */
    /**
     * 阻止原生鼠标事件穿透到游戏。
     *
     * <h2>为什么不用 {@code aui-mouse-events: intercept}</h2>
     * 那个 meta 是**整页开关**：打开后凡是命中页面的输入都被消费。而资源管理器是铺满
     * 整屏的 Overlay（{@code 100vw × 100vh}），于是整个屏幕的点击都不再传给底下的容器，
     * 槽位就完全点不到了。
     *
     * <p>所以这里改成**逐事件判断**：只有当事件确实落在窗口本体上时才消费，
     * 落在窗口之外（铺满屏幕但透明的页面根）就放行给原版界面。
     *
     * <p>判定用 {@code e.target}——AUI 的事件派发已经按层级命中测试选好了目标元素，
     * 直接问「这个目标是不是窗口的一部分」比再做一次坐标换算更准。
     */
    private void bindNativeInterception() {
        if (document == null) {
            return;
        }
        for (String type : new String[]{"mousedown", "mouseup", "click", "dblclick", "wheel", "contextmenu", "mousemove"}) {
            document.addEventListener(type, e -> {
                if (!(e instanceof MouseEvent me)) {
                    return;
                }
                if (!(me.target instanceof Element target)) {
                    return;
                }
                // 输入框需要原生鼠标事件来定位光标，不拦截。
                if (target.closest("input") != null) {
                    return;
                }
                // 目标落在窗口本体（含其各种浮层）上才消费；否则放行给原版 Screen。
                if (!isWindowElement(target)) {
                    return;
                }
                me.consumeNative();
            });
        }
    }

    /**
     * 给定的屏幕坐标是否落在本窗口的可见区域上。
     *
     * <p>供跨窗口拖拽在松手时判断「鼠标是否还在资源管理器上」：只要还在，就绝不把
     * 这次拖拽当成「拖到容器槽位」，避免穿透到底下的容器界面。
     */
    boolean hitsOwnWindow(Position screenPos) {
        if (document == null || !document.isActive() || screenPos == null) {
            return false;
        }
        Position p = document.screenToDocumentPosition(screenPos);
        if (p == null) {
            return false;
        }
        Element hit = document.hitTest(p);
        return hit != null && isWindowElement(hit);
    }

    /** 命中元素是否属于资源管理器窗口本体（含它的各种浮层）。 */
    private static boolean isWindowElement(Element hit) {
        // 窗口本体、以及挂在其下的所有 UI（标题栏/工具栏/内容区/菜单/对话框）都算命中。
        return hit.closest(".fe") != null
                || hit.closest(".fe-ctx-menu") != null
                || hit.closest(".fe-conflict-dialog") != null
                || hit.closest(".fe-properties-dialog") != null
                || hit.closest(".fe-columns-dialog") != null
                || hit.closest(".fe-columns-overlay") != null
                || hit.closest(".fe-filter-dialog") != null
                || hit.closest(".fe-filter-dialog-overlay") != null
                || hit.closest(".fe-template-save-dialog") != null
                || hit.closest(".fe-template-save-overlay") != null
                || hit.closest(".fe-selection-rect") != null
                || hit.closest(".fe-tooltip") != null
                || hit.closest(".fe-rename-input") != null
                || hit.closest(".fe-drag-ghost") != null;
    }

    /** 键盘快捷键（当键盘事件能到达本 Overlay 时生效）。 */
    private void bindKeyboard() {
        document.addEventListener("keydown", e -> {
            if (!(e instanceof KeyEvent ke)) {
                return;
            }
            // 列选择 / 筛选条件 / 模板命名是模态对话框：键盘操作不能再落到背后的文件列表。
            if (columnManagerOpen || filterDialogOpen || templateSaveDialogOpen) {
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
            } else if (ctrl && "Tab".equals(key)) {
                // Windows：Ctrl+Tab 向后切换标签页，Ctrl+Shift+Tab 向前。
                cycleTab(shift ? -1 : 1);
            } else if (ctrl && !shift && !alt && key.length() == 1
                    && key.charAt(0) >= '1' && key.charAt(0) <= '9') {
                // Windows：Ctrl+数字 直接切到第 n 个标签页（1 起算）。
                switchToTabByNumber(key.charAt(0) - '0');
            } else if (ctrl && "n".equalsIgnoreCase(key)) {
                // 与资源管理器一致：在当前所在文件夹开一个新窗口，而不是回到根目录。
                FileExplorerWindow.openAt(currentKey);
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
            } else if ("F4".equalsIgnoreCase(key) && !ctrl && !alt && !shift) {
                // Windows：F4 选中地址栏。
                beginAddressEdit();
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
            } else if ("Delete".equals(key) && !ctrl && !alt && !shift) {
                delete();
            } else if ("Delete".equals(key) && shift && !ctrl && !alt) {
                deletePermanently();
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
                || active == document.getElementById("fe-address-input")
                || active == document.getElementById("fe-filter-dialog-search")
                || active == document.getElementById("fe-template-save-name")
                || active.getClassList().contains("fe-filter-input")
                || active.closest(".fe-filter-input") != null;
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
        Element suggest = document.getElementById("fe-address-suggest");
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
        // 输入自动补全。
        input.addEventListener("input", e -> updateAddressSuggestions());
        input.addEventListener("keydown", e -> {
            e.stopPropagation();
            if (!(e instanceof KeyEvent ke)) {
                return;
            }
            if (suggestOpen()) {
                if ("ArrowDown".equalsIgnoreCase(ke.key) || "Down".equalsIgnoreCase(ke.key)) {
                    addressSuggestIndex = Math.min(addressSuggestIndex + 1, addressSuggestCount - 1);
                    updateAddressSuggestHighlight();
                    return;
                }
                if ("ArrowUp".equalsIgnoreCase(ke.key) || "Up".equalsIgnoreCase(ke.key)) {
                    addressSuggestIndex = Math.max(0, addressSuggestIndex - 1);
                    updateAddressSuggestHighlight();
                    return;
                }
                if ("Enter".equalsIgnoreCase(ke.key)) {
                    if (addressSuggestIndex >= 0 && addressSuggestIndex < addressSuggestCount) {
                        acceptAddressSuggestion(addressSuggestIndex);
                    } else {
                        closeAddressSuggest();
                        commitAddressEdit();
                    }
                    return;
                }
                if ("Escape".equalsIgnoreCase(ke.key)) {
                    closeAddressSuggest();
                    return;
                }
            }
            if ("Enter".equals(ke.key)) {
                commitAddressEdit();
            } else if ("Escape".equals(ke.key)) {
                cancelAddressEdit();
            }
        });
        input.addEventListener("blur", e -> commitAddressEdit());
    }

    private boolean suggestOpen() {
        Element suggest = document.getElementById("fe-address-suggest");
        return suggest != null && addressSuggestCount > 0
                && "none" != suggest.getInlineStylePropertyValue("display");
    }

    private void updateAddressSuggestions() {
        Element input = document.getElementById("fe-address-input");
        Element suggest = document.getElementById("fe-address-suggest");
        if (input == null || suggest == null) {
            return;
        }
        String raw = input.getValue();
        String query = raw == null ? "" : raw.trim().toLowerCase();
        if (query.isEmpty()) {
            closeAddressSuggest();
            return;
        }
        List<String> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 本窗口的导航历史 + 快速访问里的路径（固定项在前，其余按使用频率）。
        for (String key : navHistory) {
            addAddressCandidate(candidates, seen, key, query);
        }
        for (String key : ExplorerPreferences.quickAccess()) {
            addAddressCandidate(candidates, seen, key, query);
        }
        // VFS 全部文件夹 key/name 前缀匹配。
        for (String key : allFolderKeys()) {
            addAddressCandidate(candidates, seen, key, query);
        }
        if (candidates.isEmpty()) {
            closeAddressSuggest();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String key : candidates) {
            VirtualFileSystem.Folder folder = fs.folder(key);
            String label = folder != null ? folder.name : key;
            sb.append("<div class=\"fe-address-suggest-item\" data-key=\"").append(escape(key)).append("\">")
              .append(escape(pathText(key))).append("</div>");
        }
        suggest.setInnerHTML(sb.toString());
        addressSuggestIndex = 0;
        addressSuggestCount = candidates.size();
        addressSuggestCandidates = candidates;
        suggest.setInlineStyleProperty("display", "block");
        updateAddressSuggestHighlight();
        for (Element item : suggest.querySelectorAll(".fe-address-suggest-item")) {
            String key = item.getAttribute("data-key");
            item.addEventListener("mousedown", e -> e.stopPropagation());
            item.addEventListener("click", e -> {
                if (key != null) {
                    closeAddressSuggest();
                    navigateTo(key);
                    cancelAddressEdit();
                }
            });
        }
    }

    private void addAddressCandidate(List<String> out, Set<String> seen, String key, String query) {
        if (key == null || seen.contains(key)) {
            return;
        }
        VirtualFileSystem.Folder folder = fs.folder(key);
        if (folder == null) {
            return;
        }
        String path = pathText(key);
        if (folder.name.toLowerCase().startsWith(query) || key.toLowerCase().startsWith(query)
                || path.toLowerCase().contains(query)) {
            seen.add(key);
            out.add(key);
        }
    }

    private List<String> allFolderKeys() {
        List<String> result = new ArrayList<>();
        collectFolderKeys(result, cc.sighs.informationera.disk.DiskFolder.ROOT_ID);
        return result;
    }

    private void collectFolderKeys(List<String> out, String key) {
        VirtualFileSystem.Folder folder = fs.folder(key);
        if (folder == null) {
            return;
        }
        out.add(key);
        for (VirtualFileSystem.Entry entry : folder.entries) {
            if (entry.folder && entry.childKey != null) {
                collectFolderKeys(out, entry.childKey);
            }
        }
    }

    private void updateAddressSuggestHighlight() {
        Element suggest = document.getElementById("fe-address-suggest");
        if (suggest == null) {
            return;
        }
        List<Element> items = suggest.querySelectorAll(".fe-address-suggest-item");
        for (int i = 0; i < items.size(); i++) {
            if (i == addressSuggestIndex) {
                items.get(i).getClassList().add("active");
            } else {
                items.get(i).getClassList().remove("active");
            }
        }
    }

    private void acceptAddressSuggestion(int index) {
        if (addressSuggestCandidates == null || index < 0 || index >= addressSuggestCandidates.size()) {
            closeAddressSuggest();
            return;
        }
        String key = addressSuggestCandidates.get(index);
        closeAddressSuggest();
        navigateTo(key);
        cancelAddressEdit();
    }

    private void closeAddressSuggest() {
        Element suggest = document.getElementById("fe-address-suggest");
        if (suggest != null) {
            suggest.setInlineStyleProperty("display", "none");
            suggest.setInnerHTML("");
        }
        addressSuggestCount = 0;
        addressSuggestIndex = -1;
        addressSuggestCandidates = null;
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

        // 现在只有一块磁盘：第一个名字要么是玩家名（最上层）、盘名（磁盘那一层），
        // 要么是磁盘里的一个文件夹。
        String key;
        String first = names.get(0);
        // 最上层是玩家名目录，磁盘挂在它下面；「此电脑」这类通用别名也指向这一层。
        VirtualFileSystem.Folder host = fs.folder(DiskFolder.HOST_ID);
        String hostName = host == null ? "" : host.name;
        String rootName = fs.diskName();
        if ((!hostName.isEmpty() && first.equalsIgnoreCase(hostName))
                || first.equalsIgnoreCase("信息磁盘")
                || first.equalsIgnoreCase("此电脑")
                || first.equalsIgnoreCase("我的电脑")
                || first.equalsIgnoreCase("disk")
                || first.equalsIgnoreCase("root")
                || first.equals("/")) {
            key = DiskFolder.HOST_ID;
        } else if (rootName != null && !rootName.isEmpty() && first.equalsIgnoreCase(rootName)) {
            key = DiskFolder.ROOT_ID;
        } else if (first.equalsIgnoreCase("回收站") || first.equalsIgnoreCase("recyclebin")) {
            key = DiskFolder.RECYCLE_ID;
        } else {
            // 相对当前目录的简单名；不行再从根下找。
            String resolved = fs.resolve(first);
            if (resolved != null) {
                key = resolved;
            } else {
                key = findChild(currentKey, first);
                if (key == null) {
                    key = findChild(DiskFolder.ROOT_ID, first);
                }
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
                // 中键点标签页：关闭它（Windows / 浏览器习惯）。
                if (e instanceof MouseEvent me && me.button == 2) {
                    String idx = tabEl.getAttribute("data-tab");
                    if (idx != null) {
                        try {
                            closeTab(Integer.parseInt(idx));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    return;
                }
                if (e instanceof MouseEvent me && me.button == 0) {
                    String idx = tabEl.getAttribute("data-tab");
                    if (idx != null) {
                        int tabIdx = Integer.parseInt(idx);
                        tabDragIndex = tabIdx;
                        tabDragActive = false;
                        tabDragMoved = false;
                        tabDragStartX = me.clientX;
                        tabDragStartY = me.clientY;
                        // 多窗口时把标签拖拽交给全局引擎，支持跨窗口合并/拖出成新窗口。
                        if (FileExplorerWindow.windowCount() > 1) {
                            Position screen = document.documentToScreenPosition(new Position(me.clientX, me.clientY));
                            FileExplorerWindow.beginTabDrag(this, tabIdx, screen.x, screen.y);
                        }
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
        tab.backStack.addAll(ExplorerSession.globalBackStack);
        tab.forwardStack.clear();
        tab.forwardStack.addAll(ExplorerSession.globalForwardStack);
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
        ExplorerSession.globalBackStack.clear();
        ExplorerSession.globalBackStack.addAll(tab.backStack);
        ExplorerSession.globalForwardStack.clear();
        ExplorerSession.globalForwardStack.addAll(tab.forwardStack);
        selected.clear();
        selected.addAll(tab.selected);
        expandedSidebar.clear();
        expandedSidebar.addAll(tab.expandedSidebar);
        viewMode = tab.viewMode;
        iconSize = tab.iconSize;
        sortField = tab.sortField;
        sortAsc = tab.sortAsc;
        foldersFirst = tab.foldersFirst;
        clearAppliedFilterTemplate();
        searchQuery = tab.searchQuery;
        updateFilterClearButton();
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

    /**
     * 循环切换标签页（Windows：Ctrl+Tab 向后、Ctrl+Shift+Tab 向前）。
     *
     * @param step +1 向后，-1 向前；到头后回绕
     */
    private void cycleTab(int step) {
        if (tabs.size() <= 1) {
            return;
        }
        switchToTab(Math.floorMod(activeTab + step, tabs.size()));
    }

    /**
     * 切到第 n 个标签页（Windows：Ctrl+数字，1 起算）。
     *
     * <p>与资源管理器一致：编号超出标签数量时不做任何事，而不是切到最后一个。
     */
    private void switchToTabByNumber(int number) {
        int index = number - 1;
        if (index >= 0 && index < tabs.size()) {
            switchToTab(index);
        }
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
        addTabAt(currentKey);
    }

    /**
     * 在当前窗口里开一个新标签页并停在指定文件夹。
     *
     * <p>对应右键菜单的「在新标签页中打开」：与 {@link #newTab()} 的区别只是落点——
     * 那个停在当前所在文件夹，这个停在给定的那个。
     */
    void openInNewTab(String key) {
        if (key == null || fs.folder(key) == null) {
            return;
        }
        addTabAt(key);
    }

    /** 新增一个停在指定文件夹的标签页，并切过去。 */
    private void addTabAt(String key) {
        saveActiveTab();
        Tab tab = new Tab();
        tab.currentKey = key;
        VirtualFileSystem.Folder folder = fs.folder(key);
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
        closeColumnManager();
        // 标签页恢复的字段只是缓存，最终以「身份 + 当前文件夹」的设置为准。
        applyViewState(ExplorerPreferences.viewState(currentKey), false);
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

    /** 供 FileExplorerWindow 调用：把当前活跃标签状态保存回 tab。 */
    void saveActiveTabState() {
        if (tabs.isEmpty()) {
            return;
        }
        saveActiveTab();
    }

    /** 供 FileExplorerWindow 调用：把给定 tab 迁移到本窗口（末尾追加并激活）。 */
    void adoptTab(FileExplorerController.Tab tab) {
        if (tab == null) {
            return;
        }
        tabs.add(tab);
        activeTab = tabs.size() - 1;
        loadTab(tab);
        renderAllAfterTabChange();
        FileExplorerWindow.focus(this);
    }

    /** 供 FileExplorerWindow 调用：按 index 移除标签（不关闭窗口）。 */
    FileExplorerController.Tab detachTab(int index) {
        if (index < 0 || index >= tabs.size()) {
            return null;
        }
        if (tabs.size() <= 1) {
            return null;
        }
        saveActiveTab();
        FileExplorerController.Tab tab = tabs.remove(index);
        if (index == activeTab) {
            activeTab = Math.min(index, tabs.size() - 1);
        } else if (index < activeTab) {
            activeTab--;
        }
        loadTab(tabs.get(activeTab));
        renderAllAfterTabChange();
        return tab;
    }

    /** 供 FileExplorerWindow 调用：关闭本窗口并返回是否成功。 */
    void closeIfEmpty() {
        if (tabs.isEmpty()) {
            closeWindow();
        }
    }

    /** 命中本窗口标签栏（用于跨窗口标签拖拽落点）。 */
    boolean hitTabStrip(Position screenPos) {
        if (document == null || !document.isActive()) {
            return false;
        }
        Position p = document.screenToDocumentPosition(screenPos);
        Element hit = document.hitTest(p);
        return hit != null && (hit.closest("#fe-tabstrip") != null || hit.closest(".fe-tab") != null);
    }

    /** 同窗口内按屏幕坐标重排标签（供全局标签拖拽释放到源窗口时使用）。 */
    void reorderTabToScreen(Position screenPos, int fromIndex) {
        if (document == null || !document.isActive()) {
            return;
        }
        Position p = document.screenToDocumentPosition(screenPos);
        int target = tabIndexAt(p.x);
        if (target >= 0 && target != fromIndex) {
            moveTab(fromIndex, target);
        }
    }

    // ---------- 窗口拖拽 / 缩放 / 框选 ----------

    private void beginDrag(double mouseX, double mouseY) {
        // 最大化窗口在鼠标真正移动后才还原（见 onInteractionMove）：单纯点击标题栏
        // 不应该退出最大化。
        interaction = "drag";
        interactionMoved = false;
        startMouseX = mouseX;
        startMouseY = mouseY;
        startWinX = windowX;
        startWinY = windowY;
        activateCapture();
    }

    private void beginResize(String handle, double mouseX, double mouseY) {
        interaction = "resize";
        interactionMoved = false;
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

    private void beginColumnResize(String id, double mouseX) {
        if (id == null) {
            return;
        }
        interaction = "colresize";
        resizeColumnId = id;
        colResizeStartX = mouseX;
        colResizeStartWidth = columnWidth(id);
        activateCapture();
    }

    private void applyColumnResize(double mouseX) {
        if (resizeColumnId == null) {
            return;
        }
        String id = resizeColumnId;
        double dx = mouseX - colResizeStartX;
        double newWidth = Math.max(48.0, colResizeStartWidth + dx);
        // 先读存储里的最新视图再只改这一列，避免另一个窗口同时改了排序/分组时被本地快照覆盖。
        ExplorerViewState updated = ExplorerPreferences.updateView(currentKey, state -> {
            ExplorerColumnService.ensureStateColumns(state);
            state.setWidth(id, newWidth);
        });
        applyViewState(updated, false);
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
        Element filter = document.getElementById("fe-filter-pane");
        if (filter != null) {
            filter.setInlineStyleProperty("width", px(previewWidth));
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
                if (mode.isMaximized()) {
                    // 和 Windows 一样：最大化窗口在指针真正拖动后才还原，
                    // 单纯点击标题栏保持最大化。还原后把拖拽基准移到当前指针，
                    // 避免窗口先跳一下再被同一段位移拖走两次。
                    double movedX = me.clientX - startMouseX;
                    double movedY = me.clientY - startMouseY;
                    if (movedX * movedX + movedY * movedY <= 9) {
                        return;
                    }
                    Size viewport = document.getViewportSize();
                    ExplorerWindowGeometry.Frame normal = mode.unmaximize(visibleFrame(), viewport);
                    double viewportWidth = Math.max(1, viewport.width());
                    double anchorX = me.clientX - (me.clientX / viewportWidth) * normal.width();
                    double anchorY = Math.max(0, me.clientY - TITLEBAR_HEIGHT / 2);
                    applyFrame(ExplorerWindowGeometry.fit(
                            new ExplorerWindowGeometry.Frame(anchorX, anchorY, normal.width(), normal.height()),
                            viewport));
                    applyWindowGeometry();
                    updateMaximizeControl();
                    // 还原本身已经是玩家调整几何：记为所有者并落盘。
                    interactionMoved = true;
                    commitGeometry();
                    startMouseX = me.clientX;
                    startMouseY = me.clientY;
                    startWinX = windowX;
                    startWinY = windowY;
                    return;
                }
                // 拖动时整体夹回视口：窗口不会只露出一个抓不到的边。
                applyFrame(ExplorerWindowGeometry.fit(
                        new ExplorerWindowGeometry.Frame(startWinX + (me.clientX - startMouseX),
                                startWinY + (me.clientY - startMouseY), windowWidth, windowHeight),
                        document.getViewportSize()));
                applyWindowGeometry();
                interactionMoved = true;
            }
            case "resize" -> {
                if (!"nav".equals(resizeHandle) && !"preview".equals(resizeHandle)) {
                    interactionMoved = true;
                }
                applyResize(me.clientX, me.clientY);
            }
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
            case "w" -> w = startWinW - dx;
            case "n" -> h = startWinH - dy;
            case "ne" -> {
                h = startWinH - dy;
                w = startWinW + dx;
            }
            case "nw" -> {
                w = startWinW - dx;
                h = startWinH - dy;
            }
            case "se" -> {
                w = startWinW + dx;
                h = startWinH + dy;
            }
            case "sw" -> {
                w = startWinW - dx;
                h = startWinH + dy;
            }
            default -> {
            }
        }

        // 尺寸上限：东/南边固定左上角，最多长到视口右边/下边；西/北边固定的是对边，
        // 最多长回原来的右/下边再往左/上延伸到视口边界。位置由固定边反推，不会因为
        // 尺寸被夹住而整体滑走。
        Size viewport = document.getViewportSize();
        boolean west = hasWest(resizeHandle);
        boolean north = hasNorth(resizeHandle);
        double rightEdge = startWinX + startWinW;
        double bottomEdge = startWinY + startWinH;
        double maxWidth = west ? rightEdge : viewport.width() - startWinX;
        double maxHeight = north ? bottomEdge : viewport.height() - startWinY;
        double minWidth = Math.min(ExplorerWindowGeometry.minWidth(viewport), Math.max(1, maxWidth));
        double minHeight = Math.min(ExplorerWindowGeometry.minHeight(viewport), Math.max(1, maxHeight));
        w = Math.max(minWidth, Math.min(w, Math.max(minWidth, maxWidth)));
        h = Math.max(minHeight, Math.min(h, Math.max(minHeight, maxHeight)));
        x = west ? rightEdge - w : startWinX;
        y = north ? bottomEdge - h : startWinY;

        applyFrame(ExplorerWindowGeometry.fit(
                new ExplorerWindowGeometry.Frame(x, y, w, h), viewport));
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
        String finishedInteraction = interaction;
        String finishedHandle = resizeHandle;
        if ("rubber".equals(interaction)) {
            finishRubber();
        }
        // 列宽/窗格宽度在拖动过程中只改内存，松手时再合并成一次持久化修改。
        if ("colresize".equals(finishedInteraction)) {
            notifyOtherWindowsFolder(currentKey);
        } else if ("resize".equals(finishedInteraction) && "nav".equals(finishedHandle)) {
            changeDisplay(state -> state.navWidth = navWidth, false);
        } else if ("resize".equals(finishedInteraction) && "preview".equals(finishedHandle)) {
            changeDisplay(state -> state.previewWidth = previewWidth, false);
        }
        // 窗口本体拖拽/缩放确实移动过：记为几何所有者并落盘，下次开窗或重启后恢复。
        // 只是点了一下标题栏/缩放边（没有位移）不算调整，不能改写持久化记录。
        if (interactionMoved
                && ("drag".equals(finishedInteraction)
                || ("resize".equals(finishedInteraction) && !"nav".equals(finishedHandle)
                        && !"preview".equals(finishedHandle)))) {
            commitGeometry();
        }
        interactionMoved = false;
        interaction = "none";
        resizeHandle = "";
        resizeColumnId = null;
        if (selectionRect != null) {
            selectionRect.setInlineStyleProperty("display", "none");
        }
        deactivateCapture();
    }

    private void finishRubber() {
        if (!rubberAdditive) {
            selected.clear();
        }
        if (isLinearView()) {
            // 线性视图（details/list/content）：按行索引区间选择，避免矩形与多列表格/虚拟滚动行错配。
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (Element item : document.querySelectorAll(".fe-item")) {
                VirtualFileSystem.Entry entry = entryFor(item);
                if (entry == null) {
                    continue;
                }
                Element.DOMRect rect = item.getBoundingClientRect();
                if (rect != null && rect.bottom > rubberTop && rect.top < rubberTop + rubberHeight) {
                    int index = indexOf(item);
                    if (index >= 0) {
                        lo = Math.min(lo, index);
                        hi = Math.max(hi, index);
                    }
                }
            }
            if (lo != Integer.MAX_VALUE && hi != Integer.MIN_VALUE) {
                for (int i = lo; i <= hi && i < renderedEntryList.size(); i++) {
                    selected.add(renderedEntryList.get(i));
                }
            }
        } else {
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
        if (!mode.isMinimized()) {
            // 最小化只隐藏窗口：最大化状态和普通还原矩形都保持不动，
            // 任务栏恢复后最大化窗口仍然最大化。
            mode.minimize();
            hideTooltip();
            window.setInlineStyleProperty("display", "none");
        } else {
            // 移除内联 display，恢复 CSS 定义的布局，避免残留 display 导致尺寸错乱。
            window.removeInlineStyleProperty("display");
            // 最小化期间视口可能已经变了（窗口被拉宽/分辨率变化），恢复时按当前视口重新贴合。
            applyFrame(mode.restoreFromMinimize(visibleFrame(), document.getViewportSize()));
            applyWindowGeometry();
        }
    }

    private void toggleMaximize() {
        Size viewport = document.getViewportSize();
        if (!mode.isMaximized()) {
            applyFrame(mode.maximize(visibleFrame(), viewport));
        } else {
            // 还原矩形也必须落在当前视口里：最大化期间视口可能已经缩小。
            applyFrame(mode.unmaximize(visibleFrame(), viewport));
        }
        applyWindowGeometry();
        updateMaximizeControl();
        // 最大化/还原是玩家主动调整几何：成为几何所有者并落盘。
        commitGeometry();
    }

    // ---------- 跨窗口拖拽原语（由 FileExplorerWindow 全局引擎调用） ----------

    String ghostHtmlFor(List<VirtualFileSystem.Entry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        if (entries.size() == 1) {
            VirtualFileSystem.Entry entry = entries.get(0);
            return "<div class=\"fe-ghost-row\">" + iconFor(entry, 16) + "<span>" + escape(entry.name)
                    + "</span><span class=\"fe-ghost-badge\"></span></div>";
        }
        StringBuilder sb = new StringBuilder("<div class=\"fe-ghost-row\">");
        sb.append(folderIcon(16)).append("<span>").append(t("informationera.fe.items", entries.size())).append("</span>");
        sb.append("<span class=\"fe-ghost-badge\"></span></div>");
        return sb.toString();
    }

    /**
     * 给拖拽虚影里的物品图标喂驱动栈。
     *
     * <p>虚影挂在任务栏 Overlay 的 Document 上，不是本窗口的 Document，所以内容区那套
     * {@link #driveItemIcons()} 覆盖不到它——不补这一步，虚影里的物品就是个空图标。
     * 虚影最多只显示一个物品图标，取第一个即可。
     */
    void driveGhostIcons() {
        if (fs == null) {
            return;
        }
        Element ghost = FileExplorerTrigger.getGlobalGhost();
        if (ghost == null) {
            return;
        }
        Element icon = ghost.querySelector(".fe-item-icon");
        if (icon == null) {
            return;
        }
        Element child = icon.querySelector("item");
        if (!(child instanceof Item item)) {
            return;
        }
        String nodeId = icon.getAttribute("data-icon-for");
        VirtualFileSystem.Entry entry = nodeId == null ? null : entryById(nodeId);
        if (entry == null || entry.itemKey == null) {
            return;
        }
        ItemStack stack = fs.iconStackOf(entry, DiskClient.registryProvider());
        if (stack.isEmpty()) {
            return;
        }
        item.setDrivenState(stack, ItemStacks.overlayText(entry.count), false, false, Item.Source.INGREDIENT);
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
                || hit.closest(".fe-crumb") != null || hit.closest(".fe-status-view") != null
                || hit.closest(".fe-filter-chip") != null || hit.closest(".fe-filter-more") != null
                || hit.closest(".fe-filter-clear") != null || hit.closest(".fe-filter-switch-row") != null
                || hit.closest(".fe-filter-value") != null || hit.closest(".fe-filter-history-value") != null
                || hit.closest(".fe-filter-action") != null
                || hit.closest(".fe-conflict-btn") != null
                || hit.closest(".fe-template-save-close") != null) {
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
        // 过滤无效项（移动到自己内部、原地移动）。
        List<VirtualFileSystem.Entry> valid = new ArrayList<>();
        for (VirtualFileSystem.Entry entry : entries) {
            if (entry.folder && entry.childKey != null && fs.isWithin(targetKey, entry.childKey)) {
                continue;
            }
            // 注意：复制到同一个文件夹是合法操作（Windows 里会生成「xxx - 副本」），
            // 只有**移动**到原地才是无意义的。扁平视图里每个条目来自不同目录，
            // 必须按它自己的父文件夹判断，不能拿当前目录代替。
            String origin = entry.parentFolderKey != null ? entry.parentFolderKey : sourceKey;
            if (targetKey.equals(origin) && !copy) {
                continue;
            }
            valid.add(entry);
        }
        if (valid.isEmpty()) {
            setStatus(copy ? t("informationera.fe.status_cannot_copy") : t("informationera.fe.status_cannot_move"));
            return false;
        }
        // 复制不弹冲突框：服务端复制时本就会自动生成不重名的名字
        // （Windows 的「粘贴到源文件夹」也是直接变出副本，不打断操作）。
        // 只有移动才需要问「同名了怎么办」，因为移动无法自动改名——那个名字属于原条目。
        if (!copy) {
            List<VirtualFileSystem.Entry> conflicts = new ArrayList<>();
            for (VirtualFileSystem.Entry entry : valid) {
                if (fs.hasName(targetKey, entry.name)) {
                    conflicts.add(entry);
                }
            }
            if (!conflicts.isEmpty()) {
                conflictEntries = new ArrayList<>(valid);
                conflictNames = new ArrayList<>();
                for (VirtualFileSystem.Entry c : conflicts) {
                    conflictNames.add(c.name);
                }
                conflictSourceKey = sourceKey;
                conflictTargetKey = targetKey;
                conflictCopy = false;
                showConflictDialog(conflicts);
                return false;
            }
        }
        return performMoveOrCopy(sourceKey, valid, targetKey, copy, new ArrayList<>(), new ArrayList<>());
    }

    /** 实际执行批量移动/复制。replaceNames / skipNames 只用于**移动**时的同名冲突处理。 */
    private boolean performMoveOrCopy(String sourceKey, List<VirtualFileSystem.Entry> entries, String targetKey,
                                      boolean copy, List<String> replaceNames, List<String> skipNames) {
        List<String> ids = new ArrayList<>();
        for (VirtualFileSystem.Entry entry : entries) {
            // 复制不做冲突过滤：服务端会自动生成不重名的名字。
            if (!copy) {
                boolean conflict = fs.hasName(targetKey, entry.name);
                if (conflict && !replaceNames.contains(entry.name)) {
                    // 冲突且未选择替换：跳过（含显式跳过）。
                    continue;
                }
            }
            ids.add(entry.id);
        }
        if (ids.isEmpty()) {
            return false;
        }
        // 变更只在服务端执行；这里只发意图，状态回来后再刷新界面。
        if (copy) {
            DiskClient.copy(ids, targetKey);
        } else {
            DiskClient.move(ids, targetKey);
        }
        setStatus(copy ? t("informationera.fe.status_copied_to", ids.size())
                : t("informationera.fe.status_moved", ids.size()));
        return true;
    }

    /** 拖拽到目标文件夹：快捷方式概念已取消，「按住 Alt 拖动」改为复制引用。 */
    boolean createShortcuts(String sourceKey, List<VirtualFileSystem.Entry> entries, String targetKey) {
        List<String> ids = new ArrayList<>();
        for (VirtualFileSystem.Entry entry : entries) {
            ids.add(entry.id);
        }
        if (ids.isEmpty()) {
            return false;
        }
        DiskClient.copy(ids, targetKey);
        setStatus(t("informationera.fe.status_copied_to", ids.size()));
        return true;
    }

    /** 其它窗口发生了文件变更，刷新本窗口的显示。 */
    void refreshAfterExternal() {
        afterMutation();
    }

    /**
     * 服务端推回了新的权威视图：本窗口持有的条目引用已全部失效，
     * 需要清掉选中集合与渲染缓存后整体重绘。
     */
    /**
     * 服务端推回了新的权威视图：切到新实例并整体重绘。
     *
     * <p>本窗口持有的条目引用已全部失效，需要清掉选中集合与渲染缓存。
     * 关键是必须**换掉 {@link #fs}**——否则新数据再正确，渲染读的还是旧视图。
     */
    void onViewReplaced(VirtualFileSystem view) {
        if (view != null) {
            this.fs = view;
        }
        // 设备状态到达或换了储存卡时，文件夹 id 可能和上一张卡相同，但布局必须按新磁盘取。
        String disk = ExplorerPreferences.diskIdentity();
        boolean diskChanged = !Objects.equals(disk, lastDiskIdentity);
        lastDiskIdentity = disk;
        // 条目引用已整体失效，正在进行的重命名必须放弃：留着输入框再提交，
        // 用的就是指向旧视图的条目了。
        if (renamingEntry != null) {
            cancelRename();
        }
        // 当前所在文件夹可能在新视图里已经不存在（被删除/移动过），
        // 这时回退到根目录，避免渲染出一片空白。
        boolean folderChanged = false;
        if (this.fs.folder(currentKey) == null) {
            currentKey = DiskFolder.ROOT_ID;
            folderChanged = true;
        }
        if (diskChanged || folderChanged) {
            closeColumnManager();
            applyViewState(ExplorerPreferences.viewState(currentKey), false);
        }
        selected.clear();
        checkedSet.clear();
        renderedEntryList.clear();
        ExplorerSession.clearClipboard();
        afterMutation();
        if (filterOpen) {
            // 权威视图换了，筛选窗格里的候选/创造标签页标题也要按新视图重读。
            renderFilterPane();
            if (filterDialogOpen) {
                renderFilterDialog();
            }
        }
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
        collectFolderChoices(result, DiskFolder.ROOT_ID, copy, sourceController, 0);
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
                    if (de.folder && de.childKey != null && fs.isWithin(entry.childKey, de.childKey)) {
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

    /**
     * 右键菜单的一项。
     *
     * @param shortcut 右对齐显示的快捷键提示（空串表示不显示）；与键盘处理里的绑定保持一致
     */
    private record CtxEntry(int kind, String label, String shortcut, boolean danger, boolean checked,
                            Runnable action, List<CtxEntry> children) {
        static CtxEntry header(String label) {
            return new CtxEntry(0, label, "", false, false, null, null);
        }

        static CtxEntry separator() {
            return new CtxEntry(1, "", "", false, false, null, null);
        }

        static CtxEntry action(String label, Runnable action) {
            return new CtxEntry(2, label, "", false, false, action, null);
        }

        static CtxEntry action(String label, String shortcut, Runnable action) {
            return new CtxEntry(2, label, shortcut, false, false, action, null);
        }

        static CtxEntry actionChecked(String label, boolean checked, Runnable action) {
            return new CtxEntry(2, label, "", false, checked, action, null);
        }

        static CtxEntry actionDanger(String label, Runnable action) {
            return new CtxEntry(2, label, "", true, false, action, null);
        }

        static CtxEntry actionDanger(String label, String shortcut, Runnable action) {
            return new CtxEntry(2, label, shortcut, true, false, action, null);
        }

        static CtxEntry submenu(String label, List<CtxEntry> children) {
            return new CtxEntry(3, label, "", false, false, null, children);
        }
    }

    private void showWindowContextMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.action(t("informationera.fe.new_tab"), "Ctrl+T", this::newTab));
        entries.add(CtxEntry.action(t(mode.isMaximized() ? "informationera.fe.restore" : "informationera.fe.maximize"),
                this::toggleMaximize));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.actionDanger(t("informationera.fe.close_window"), this::closeWindow));
        openContextMenu(x, y, entries);
    }

    private void showContentContextMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        if ("recyclebin".equals(currentKey)) {
            entries.add(CtxEntry.action(t("informationera.fe.empty_recycle_bin"), this::emptyRecycleBin));
            entries.add(CtxEntry.action(t("informationera.fe.restore_all"), this::restoreAllRecycle));
            entries.add(CtxEntry.separator());
            entries.add(CtxEntry.action(t("informationera.fe.refresh"), this::renderContent));
            entries.add(CtxEntry.separator());
            entries.add(CtxEntry.action(t("informationera.fe.properties"), "Alt+Enter", this::showProperties));
            openContextMenu(x, y, entries);
            return;
        }
        entries.add(CtxEntry.action(t("informationera.fe.refresh"), "F5", this::renderContent));
        entries.add(CtxEntry.action(t("informationera.fe.new_folder"), "Ctrl+Shift+N", this::createFolder));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.paste"), "Ctrl+V", this::paste));
        entries.add(CtxEntry.action(t("informationera.fe.undo"), "Ctrl+Z", this::undo));
        entries.add(CtxEntry.action(t("informationera.fe.redo"), "Ctrl+Y", this::redo));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.select_all"), "Ctrl+A", this::selectAll));
        entries.add(CtxEntry.action(t("informationera.fe.invert_selection"), this::invertSelection));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.properties"), "Alt+Enter", this::showProperties));
        openContextMenu(x, y, entries);
    }

    private void showItemContextMenu(Element item, VirtualFileSystem.Entry entry, double x, double y) {
        if (entry == null) {
            return;
        }
        hideTooltip();
        if (!selected.contains(entry)) {
            selected.clear();
            selected.add(entry);
            updateSelectionVisuals();
            updateStatusBar();
        }
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.action(t("informationera.fe.open"), "Enter", () -> openEntry(entry)));
        // 文件夹才能「在新标签页/新窗口中打开」，与资源管理器一致。
        // 资源管理器里这两项**没有**键盘快捷键（只能走菜单或中键），所以不显示提示。
        if (entry.folder && entry.childKey != null) {
            String key = entry.childKey;
            entries.add(CtxEntry.action(t("informationera.fe.open_in_new_tab"), () -> openInNewTab(key)));
            entries.add(CtxEntry.action(t("informationera.fe.open_in_new_window"), () -> openInNewWindow(key)));
        } else {
            // 「打开方式」只对文件有意义，文件夹不该出现这一项。
            entries.add(CtxEntry.submenu(t("informationera.fe.open_with"), List.of(
                    CtxEntry.action(t("informationera.fe.notepad"), () -> setStatus(t("informationera.fe.status_opened_notepad", entry.name))),
                    CtxEntry.action(t("informationera.fe.default_app"), () -> openEntry(entry)))));
        }
        entries.add(CtxEntry.action(t("informationera.fe.rename"), "F2", () -> beginRename(item, entry)));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.cut"), "Ctrl+X", this::cut));
        entries.add(CtxEntry.action(t("informationera.fe.copy"), "Ctrl+C", this::copy));
        entries.add(CtxEntry.action(t("informationera.fe.copy_path"), () -> copyPath(entry)));
        entries.add(CtxEntry.action(t("informationera.fe.create_shortcut"), () -> createShortcut(entry)));
        if (t("informationera.fe.shortcut_type").equals(entry.type) && entry.shortcutTargetKey != null) {
            entries.add(CtxEntry.action(t("informationera.fe.open_location"), () -> openEntry(entry)));
        }
        if (entry.folder && entry.childKey != null) {
            String key = entry.childKey;
            entries.add(CtxEntry.action(ExplorerPreferences.isPinned(key)
                    ? t("informationera.fe.unpin") : t("informationera.fe.pin"), () -> togglePin(key)));
        }
        entries.add(CtxEntry.submenu(t("informationera.fe.send_to"), List.of(
                CtxEntry.action(t("informationera.fe.disk_root"), () -> sendTo(DiskFolder.ROOT_ID, entry)),
                CtxEntry.action(t("informationera.folder.blocks"), () -> sendTo(DiskFolder.BLOCKS_ID, entry)),
                CtxEntry.action(t("informationera.folder.food"), () -> sendTo(DiskFolder.FOOD_ID, entry)),
                CtxEntry.action(t("informationera.folder.tools"), () -> sendTo(DiskFolder.TOOLS_ID, entry)),
                CtxEntry.action(t("informationera.folder.weapons"), () -> sendTo(DiskFolder.WEAPONS_ID, entry)))));
        if (entry.isRecycleEntry() || DiskFolder.RECYCLE_ID.equals(entry.parentFolderKey)) {
            // 回收站里两个动作并存：还原回原位置，或彻底删除（真丢物品）。
            entries.add(CtxEntry.action(t("informationera.fe.restore"), () -> restoreRecycleEntry(entry)));
            entries.add(CtxEntry.actionDanger(t("informationera.fe.permanently_delete"), "Shift+Delete",
                    this::deletePermanently));
        } else {
            entries.add(CtxEntry.actionDanger(t("informationera.fe.delete"), "Delete", this::delete));
        }
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.properties"), "Alt+Enter", this::showProperties));
        openContextMenu(x, y, entries);
    }

    private void showNewMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.header(t("informationera.fe.new")));
        entries.add(CtxEntry.action(t("informationera.fe.new_folder"), this::createFolder));
        openContextMenu(x, y, entries);
    }

    private void cancelSelection() {
        selected.clear();
        updateSelectionVisuals();
        updateStatusBar();
    }

    private void showSortMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        for (ExplorerColumn column : ExplorerColumnService.columns()) {
            String label = ExplorerColumnService.label(column).getString();
            entries.add(CtxEntry.actionChecked(label, column.id().equals(sortField),
                    () -> setSort(column.id())));
        }
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
        changeView(state -> state.foldersFirst = !state.foldersFirst);
    }

    private void showLayoutMenu(double x, double y) {
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.actionChecked(t("informationera.fe.xlarge"), "xlarge".equals(viewMode), () -> setViewMode("xlarge")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.large"), "large".equals(viewMode), () -> setViewMode("large")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.medium"), "medium".equals(viewMode), () -> setViewMode("medium")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.small"), "small".equals(viewMode), () -> setViewMode("small")));
        entries.add(CtxEntry.actionChecked(t("informationera.fe.slots"), "slots".equals(viewMode), () -> setViewMode("slots")));
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
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.set_default_view"), this::setCurrentViewAsDefault));
        entries.add(CtxEntry.action(t("informationera.fe.restore_default_view"), this::restoreCurrentViewDefault));
        entries.add(CtxEntry.action(t("informationera.fe.reset_all_views"), this::resetAllFolderViews));
        openContextMenu(x, y, entries);
    }

    /** 把当前文件夹的布局/排序/分组/列设置记为默认，供其它没有独立设置的文件夹使用。 */
    private void setCurrentViewAsDefault() {
        ExplorerPreferences.setDefaultView(currentKey);
        setStatus(t("informationera.fe.status_default_view_saved"));
    }

    /** 删除当前文件夹的独立设置，回到默认视图。 */
    private void restoreCurrentViewDefault() {
        ExplorerPreferences.resetFolderView(currentKey);
        applyViewState(ExplorerPreferences.viewState(currentKey), true);
        notifyOtherWindowsFolder(currentKey);
        setStatus(t("informationera.fe.status_default_view_restored"));
    }

    /** 清除当前身份下所有文件夹的独立设置（默认模板本身保留）。 */
    private void resetAllFolderViews() {
        ExplorerPreferences.resetAllFolderViews();
        for (FileExplorerController controller : new ArrayList<>(FileExplorerWindow.getWindows())) {
            controller.reloadFolderViewFromPreferences();
        }
        setStatus(t("informationera.fe.status_views_reset"));
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
                    // 快捷键提示右对齐，与资源管理器一致。
                    if (entry.shortcut != null && !entry.shortcut.isEmpty()) {
                        sb.append("<span class=\"fe-ctx-shortcut\">").append(escape(entry.shortcut)).append("</span>");
                    }
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

    /**
     * 进入重命名状态：把输入框贴到被重命名的那一行上。
     *
     * <p>定位依据是**文件名标签自身的矩形**（`[data-name]`），不是整行——在详细/内容视图里
     * 整行包含图标与多列，按行定位会让输入框跑到行首、与文字错开。
     *
     * <p>宽度取标签的 `scrollWidth`（文本内容实际宽度），让输入框与原文案一样长，
     * 而不是把整列撑满。
     */
    private void beginRename(Element item, VirtualFileSystem.Entry entry) {
        Element input = document.getElementById("fe-rename-input");
        if (input == null) {
            return;
        }
        renamingEntry = entry;

        Element nameEl = item != null ? item.querySelector("[data-name]") : null;
        Element.DOMRect rect = nameEl != null ? nameEl.getBoundingClientRect()
                : (item != null ? item.getBoundingClientRect() : null);
        if (rect == null || rect.width <= 0 || rect.height <= 0) {
            cancelRename();
            return;
        }

        // 文本实际宽度：scrollWidth 是内容宽度，比列宽更贴近「原文案有多长」。
        double textWidth = nameEl != null && nameEl.scrollWidth > 0
                ? nameEl.scrollWidth : rect.width;
        // 左右各留一点内边距，文字不贴边框；上限防止超长名称把输入框撑出窗口。
        double width = Math.min(420.0, Math.max(60.0, textWidth + RENAME_PADDING_X * 2 + 4.0));
        double height = Math.max(rect.height, RENAME_MIN_HEIGHT);
        double top = rect.top + (rect.height - height) / 2.0;

        // 关键顺序：先把位置、尺寸、值都设好并把滚动归零，最后才 focus/select。
        // AUI 的滚动是带动画的（scrollLeft 与 targetScrollLeft 分离），
        // 如果在 select() 之后再去 setScrollLeft(0)，会播一段从末尾滚回开头的动画——
        // 看上去就是「文字从左往右滑了一下才归位」。
        input.setValue(entry.name);
        input.setInlineStyleProperty("left", px(rect.left - RENAME_PADDING_X));
        input.setInlineStyleProperty("top", px(top));
        input.setInlineStyleProperty("width", px(width));
        input.setInlineStyleProperty("height", px(height));
        input.setInlineStyleProperty("display", "block");
        input.setScrollLeft(0);
        input.setScrollTop(0);

        if (input instanceof com.sighs.apricityui.element.AbstractText textInput) {
            // 文件重命名时只选中主文件名（不含扩展名），文件夹或隐藏扩展名时全选。
            if (!entry.folder && showExtensions) {
                int dot = entry.name.lastIndexOf('.');
                if (dot > 0) {
                    textInput.setSelectionRange(0, dot);
                } else {
                    textInput.select();
                }
            } else {
                textInput.select();
            }
        }
        // 选区/光标可能把滚动目标推到末尾，这里再显式归零一次；
        // 由于此时已经处于「从头选中」的状态，不会再产生回滚动画。
        input.setScrollLeft(0);
        input.focus();
    }

    private void commitRename() {
        Element input = document.getElementById("fe-rename-input");
        if (renamingEntry == null || input == null) {
            cancelRename();
            return;
        }
        String typed = input.getValue() == null ? "" : input.getValue().trim();
        if (typed.isEmpty()) {
            cancelRename();
            setStatus(t("informationera.fe.status_rename_invalid"));
            return;
        }
        DiskClient.rename(renamingEntry.id, typed);
        cancelRename();
        setStatus(t("informationera.fe.status_renamed"));
    }

    private void cancelRename() {
        renamingEntry = null;
        Element input = document.getElementById("fe-rename-input");
        if (input != null) {
            input.setInlineStyleProperty("display", "none");
            // 清掉按行实测的尺寸，避免下次重命名时先以旧尺寸闪一帧。
            input.removeInlineStyleProperty("height");
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
            // 扁平/子树视图里条目来自不同目录：剪切必须记它真正的父文件夹，
            // 不能统一记成当前目录，否则粘贴时的原地判断会错。
            String source = entry.parentFolderKey != null ? entry.parentFolderKey : currentKey;
            ExplorerSession.clipboard.add(new ExplorerSession.ClipItem(source, entry));
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
        // 收集粘贴项：剪切则逐项 sourceFolderKey，复制则 sourceFolderKey 为 null。
        List<ExplorerSession.ClipItem> items = new ArrayList<>(ExplorerSession.clipboard);
        if (ExplorerSession.clipboardCut) {
            boolean hasAny = false;
            List<String> sourceKeys = new ArrayList<>();
            List<VirtualFileSystem.Entry> entries = new ArrayList<>();
            for (ExplorerSession.ClipItem item : items) {
                if (item.sourceFolderKey() != null && !item.sourceFolderKey().equals(currentKey)) {
                    hasAny = true;
                }
            }
            if (!hasAny) {
                setStatus(t("informationera.fe.status_cannot_paste"));
                return;
            }
            // 剪切粘贴可能有多个不同源目录，这里简化：统一用每个 item 的 sourceFolderKey 逐个移动。
            // 冲突检测按源分组处理：同源按一个批次。
            for (ExplorerSession.ClipItem item : items) {
                if (item.sourceFolderKey() == null || item.sourceFolderKey().equals(currentKey)) {
                    continue;
                }
                if (fs.hasName(currentKey, item.entry().name)) {
                    // 有冲突：弹窗（单个条目）。
                    conflictEntries = new ArrayList<>(List.of(item.entry()));
                    conflictNames = new ArrayList<>(List.of(item.entry().name));
                    conflictSourceKey = item.sourceFolderKey();
                    conflictTargetKey = currentKey;
                    conflictCopy = false;
                    showConflictDialog(List.of(item.entry()));
                    return;
                }
            }
            // 无冲突：执行剪切粘贴（移动）。
            List<String> ids = new ArrayList<>();
            for (ExplorerSession.ClipItem item : items) {
                if (item.sourceFolderKey() == null || item.sourceFolderKey().equals(currentKey)) {
                    continue;
                }
                ids.add(item.entry().id);
            }
            if (ids.isEmpty()) {
                setStatus(t("informationera.fe.status_cannot_paste"));
                return;
            }
            int count = ids.size();
            ExplorerSession.clearClipboard();
            DiskClient.move(ids, currentKey);
            setStatus(t("informationera.fe.status_pasted", count));
        } else {
            // 复制粘贴：统一源 key 为 null，冲突检测。
            List<VirtualFileSystem.Entry> entries = new ArrayList<>();
            for (ExplorerSession.ClipItem item : items) {
                entries.add(item.entry());
            }
            moveOrCopy(currentKey, entries, currentKey, true);
        }
    }

    /**
     * 删除选中的条目。
     *
     * <p>语义随当前位置变化：在普通文件夹里是「移入回收站」；在回收站里已经没有再往回收站
     * 移的意义，所以直接变成彻底删除——这也是资源管理器的习惯行为。
     */
    private void delete() {
        if (DiskFolder.RECYCLE_ID.equals(currentKey)) {
            deletePermanently();
            return;
        }
        if (selected.isEmpty()) {
            setStatus(t("informationera.fe.status_no_selection"));
            return;
        }
        List<String> ids = new ArrayList<>();
        for (VirtualFileSystem.Entry entry : selected) {
            ids.add(entry.id);
        }
        DiskClient.toRecycleBin(ids);
        setStatus(t("informationera.fe.status_deleted_to_recycle", ids.size()));
    }

    private void deletePermanently() {
        if (selected.isEmpty()) {
            setStatus(t("informationera.fe.status_no_selection"));
            return;
        }
        List<String> ids = new ArrayList<>();
        for (VirtualFileSystem.Entry entry : selected) {
            ids.add(entry.id);
        }
        // 彻底删除会丢弃只被这些文件引用的物品数量，因此走服务端确认。
        DiskClient.purge(ids);
        setStatus(t("informationera.fe.status_deleted", ids.size()));
    }

    private void emptyRecycleBin() {
        if (fs.recycleCount() <= 0) {
            setStatus(t("informationera.fe.status_recycle_empty"));
            return;
        }
        DiskClient.emptyRecycleBin();
        setStatus(t("informationera.fe.status_recycle_cleared"));
    }

    private void restoreAllRecycle() {
        if (fs.recycleCount() <= 0) {
            setStatus(t("informationera.fe.status_recycle_empty"));
            return;
        }
        DiskClient.restoreAll();
        setStatus(t("informationera.fe.status_restored"));
    }

    private void restoreRecycleEntry(VirtualFileSystem.Entry entry) {
        if (entry == null) {
            setStatus(t("informationera.fe.status_recycle_empty"));
            return;
        }
        DiskClient.restore(entry.id);
        setStatus(t("informationera.fe.status_restored"));
    }

    private void createFolder() {
        DiskClient.createFolder(currentKey, t("informationera.fe.new_folder"));
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
        checkedSet.clear();
        lastCheckedIndex = -1;
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

    /** 选中指定分组下的全部条目（以当前渲染结果为准，含筛选后的条目）。 */
    private void selectGroup(String group) {
        selected.clear();
        for (VirtualFileSystem.Entry entry : renderedEntryList) {
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
        Element dialog = document.getElementById("fe-properties-dialog");
        Element overlay = document.getElementById("fe-properties-overlay");
        Element title = document.getElementById("fe-properties-title");
        Element contentEl = document.getElementById("fe-properties-content");
        if (dialog == null || contentEl == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (selected.size() == 1) {
            VirtualFileSystem.Entry entry = selected.iterator().next();
            if (title != null) {
                title.setTextContent(entry.name);
            }
            appendPropertyRow(sb, t("informationera.fe.name"), entry.name);
            appendPropertyRow(sb, t("informationera.fe.type"),
                    entry.folder ? t("informationera.fe.folder") : entry.type);
            appendPropertyRow(sb, t("informationera.fe.location"),
                    pathText(entry.parentFolderKey != null ? entry.parentFolderKey : currentKey));
            if (entry.folder) {
                appendPropertyRow(sb, t("informationera.fe.size"), t("informationera.fe.folder"));
                appendPropertyRow(sb, t("informationera.fe.contains"), t("informationera.fe.detail_contains", childCount(entry.childKey)));
            } else {
                // 文件的「大小」在物品磁盘里就是它引用的物品数量。
                appendPropertyRow(sb, t("informationera.fe.item_count"), formatSize(entry.count));
                ExplorerColumnContext columnContext = contextFor(entry);
                if (entry.itemId != null) {
                    appendPropertyRow(sb, t("informationera.fe.item_id"), entry.itemId);
                    appendPropertyRow(sb, t("informationera.fe.column_namespace"), columnContext.namespace());
                }
                int maxStack = columnContext.maxStack();
                int maxDamage = columnContext.maxDamage();
                if (maxStack > 0) {
                    appendPropertyRow(sb, t("informationera.fe.column_max_stack"),
                            Integer.toString(maxStack));
                }
                if (maxDamage > 0) {
                    appendPropertyRow(sb, t("informationera.fe.column_durability"),
                            Integer.toString(maxDamage));
                }
            }
            if (entry.shortcutTargetKey != null && fs.folder(entry.shortcutTargetKey) != null) {
                appendPropertyRow(sb, t("informationera.fe.target"), pathText(entry.shortcutTargetKey));
            }
            appendPropertyRow(sb, t("informationera.fe.created"), entry.created);
            appendPropertyRow(sb, t("informationera.fe.modified"), entry.modified);
            appendPropertyRow(sb, t("informationera.fe.attributes"), t("informationera.fe.detail_readonly"));
        } else if (selected.size() > 1) {
            if (title != null) {
                title.setTextContent(t("informationera.fe.properties"));
            }
            appendPropertyRow(sb, t("informationera.fe.properties"), t("informationera.fe.detail_selected", selected.size()));
        } else {
            VirtualFileSystem.Folder folder = fs.folder(currentKey);
            String name = folder != null ? folder.name : currentKey;
            if (title != null) {
                title.setTextContent(name);
            }
            appendPropertyRow(sb, t("informationera.fe.name"), name);
            appendPropertyRow(sb, t("informationera.fe.type"), t("informationera.fe.folder"));
            appendPropertyRow(sb, t("informationera.fe.location"), pathText(currentKey));
            appendPropertyRow(sb, t("informationera.fe.contains"), t("informationera.fe.detail_contains", childCount(currentKey)));
            appendPropertyRow(sb, t("informationera.fe.attributes"), t("informationera.fe.detail_readonly"));
        }
        contentEl.setInnerHTML(sb.toString());

        Size viewport = document.getViewportSize();
        double left = Math.max(8, (viewport.width() - 380) / 2);
        double top = Math.max(8, (viewport.height() - 360) / 2);
        dialog.setInlineStyleProperty("left", px(left));
        dialog.setInlineStyleProperty("top", px(top));
        dialog.setInlineStyleProperty("display", "flex");
        if (overlay != null) {
            overlay.setInlineStyleProperty("display", "block");
        }
    }

    private void appendPropertyRow(StringBuilder sb, String label, String value) {
        sb.append("<div class=\"fe-properties-row\"><span class=\"fe-properties-label\">")
          .append(escape(label)).append("</span><span class=\"fe-properties-value\">")
          .append(escape(value)).append("</span></div>");
    }

    private void closeProperties() {
        Element dialog = document.getElementById("fe-properties-dialog");
        Element overlay = document.getElementById("fe-properties-overlay");
        if (dialog != null) {
            dialog.setInlineStyleProperty("display", "none");
        }
        if (overlay != null) {
            overlay.setInlineStyleProperty("display", "none");
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

    // ---------- 在新标签页 / 新窗口中打开 ----------

    /** 在新窗口里打开指定文件夹。 */
    void openInNewWindow(String key) {
        if (key == null || fs.folder(key) == null) {
            return;
        }
        FileExplorerWindow.openAt(key);
    }

    /**
     * 「在新标签页 / 新窗口中打开」的目标文件夹。
     *
     * <p>优先取选中的文件夹（多选时取第一个）；没有选中文件夹时退回当前所在文件夹，
     * 这样快捷键在两种情况下都有意义，与资源管理器的习惯一致。
     */
    // ---------- 视图 / 排序 / 布局 ----------

    /** 把存储里的视图设置套用到本窗口，不写回存储。 */
    private void applyViewState(ExplorerViewState state, boolean rerender) {
        boolean groupChanged = !Objects.equals(groupBy, state.groupBy);
        viewMode = state.viewMode;
        iconSize = iconSizeFor(viewMode);
        sortField = state.sortField;
        sortAsc = state.sortAsc;
        foldersFirst = state.foldersFirst;
        groupBy = state.groupBy;
        ExplorerColumnService.ensureStateColumns(state);
        applyColumnState(state);
        if (groupChanged) {
            collapsedGroups.clear();
        }
        if (rerender) {
            renderContent();
        }
    }

    /** 把状态里的列设置复制到本窗口的本地镜像。 */
    private void applyColumnState(ExplorerViewState state) {
        columnSettings.clear();
        for (java.util.Map.Entry<String, ExplorerViewState.ColumnSetting> entry : state.columnSettings.entrySet()) {
            ExplorerViewState.ColumnSetting setting = entry.getValue();
            columnSettings.put(entry.getKey(), new ExplorerViewState.ColumnSetting(setting.width, setting.visible));
        }
        columnOrder.clear();
        columnOrder.addAll(state.columnOrder);
    }

    /** 把存储里的全局显示设置套用到本窗口，不写回存储。 */
    private void applyDisplayState(ExplorerDisplayState state) {
        showNav = state.showNav;
        previewOpen = state.showPreview;
        if (previewOpen) {
            // 预览和筛选占同一个位置：任何来源打开预览都要收起筛选，避免两个窗格同时出现。
            // 筛选关闭后内容必须重绘，相关调用点会据此补一次 renderContent。
            filterOpen = false;
            closeFilterDialog();
            closeTemplateSaveDialog();
        }
        detailsPaneOpen = state.showDetails;
        navWidth = state.navWidth;
        previewWidth = state.previewWidth;
        compact = state.compact;
        checkboxes = state.checkboxes;
        showExtensions = state.showExtensions;
    }

    /** 应用全局设置对应的界面变化；内容相关的变化才重绘内容区。 */
    private void applyDisplayVisuals(boolean rerenderContent) {
        applyPaneWidths();
        applyNavVisibility();
        applyPreviewVisibility();
        applyDetailsPaneVisibility();
        applyCompact();
        updatePreview();
        updateDetailsPane();
        if (rerenderContent) {
            renderContent();
        }
    }

    /**
     * 读存储里的最新全局设置、只改指定字段，再套用到本窗口并通知其它窗口。
     */
    private void changeDisplay(Consumer<ExplorerDisplayState> mutation, boolean rerenderContent) {
        boolean filterWasOpen = filterOpen;
        ExplorerDisplayState updated = ExplorerPreferences.updateDisplay(mutation);
        applyDisplayState(updated);
        applyDisplayVisuals(rerenderContent || (filterWasOpen && !filterOpen));
        notifyOtherWindowsDisplay();
    }

    /** 读存储里的最新文件夹视图、只改指定字段，再套用并通知同文件夹的其它窗口。 */
    private void changeView(Consumer<ExplorerViewState> mutation) {
        ExplorerViewState updated = ExplorerPreferences.updateView(currentKey, mutation);
        applyViewState(updated, true);
        notifyOtherWindowsFolder(currentKey);
    }

    private void notifyOtherWindowsDisplay() {
        for (FileExplorerController controller : new ArrayList<>(FileExplorerWindow.getWindows())) {
            if (controller != this) {
                controller.reloadDisplayFromPreferences();
            }
        }
    }

    private void notifyOtherWindowsFolder(String folderKey) {
        for (FileExplorerController controller : new ArrayList<>(FileExplorerWindow.getWindows())) {
            if (controller != this && Objects.equals(folderKey, controller.currentKey)) {
                controller.reloadFolderViewFromPreferences();
            }
        }
    }

    /** 其它窗口改了全局显示设置后调用；不回写，避免形成循环。 */
    void reloadDisplayFromPreferences() {
        boolean filterWasOpen = filterOpen;
        ExplorerDisplayState state = ExplorerPreferences.display();
        boolean contentChanged = state.checkboxes != checkboxes
                || state.showExtensions != showExtensions
                || state.compact != compact;
        applyDisplayState(state);
        contentChanged = contentChanged || (filterWasOpen && !filterOpen);
        applyDisplayVisuals(contentChanged);
    }

    /** 其它窗口改了同一文件夹的视图后调用；不回写，避免形成循环。 */
    void reloadFolderViewFromPreferences() {
        applyViewState(ExplorerPreferences.viewState(currentKey), true);
    }

    private void setViewMode(String mode) {
        changeView(state -> state.viewMode = mode);
    }

    private int iconSizeFor(String mode) {
        return switch (mode) {
            case "slots" -> 32;
            case "small" -> 40;
            case "medium" -> 64;
            case "large" -> 96;
            case "xlarge" -> 128;
            default -> 16;
        };
    }

    /**
     * 图标视图/平铺视图使用的图标尺寸。
     *
     * <p>扁平视图（详细/列表/内容）的行高固定，图标只能是 16px；而图标类视图的图标
     * 应该跟着「查看」档位一起变大变小。平铺视图与图标视图共用同一档位，只是整体略小一档，
     * 这样两者切换时视觉过渡自然。
     */
    private int tileIconSize() {
        return switch (viewMode) {
            case "small" -> 32;
            case "medium" -> 48;
            case "large" -> 72;
            case "xlarge" -> 96;
            default -> 48;
        };
    }

    private void handleContentWheel(MouseEvent me) {
        if (!me.controlKey) {
            return;
        }
        // Windows 资源管理器 Ctrl+滚轮顺序（从大到小）：
        // 超大图标 -> 大图标 -> 中等图标 -> 小图标 -> 列表 -> 详细信息 -> 内容 -> 平铺。
        // 向上滚（deltaY<0）变"小一档"，向下滚变"大一档"，两端截断，不循环。
        List<String> modes = List.of("xlarge", "large", "medium", "small", "slots", "list", "details", "content", "tiles");
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
        changeView(state -> state.sortField = field);
    }

    private void toggleSort(String field) {
        changeView(state -> {
            if (state.sortField.equals(field)) {
                state.sortAsc = !state.sortAsc;
            } else {
                state.sortField = field;
                state.sortAsc = true;
            }
        });
    }

    private void setSortAscending(boolean ascending) {
        changeView(state -> state.sortAsc = ascending);
    }

    private void toggleNavPane() {
        changeDisplay(state -> state.showNav = !state.showNav, false);
    }

    private void toggleCheckboxes() {
        changeDisplay(state -> state.checkboxes = !state.checkboxes, true);
    }

    private void toggleCompact() {
        changeDisplay(state -> state.compact = !state.compact, true);
    }

    private void setGroupBy(String value) {
        changeView(state -> state.groupBy = value);
    }

    private void toggleExtensions() {
        changeDisplay(state -> state.showExtensions = !state.showExtensions, true);
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
        changeDisplay(state -> state.showDetails = !state.showDetails, false);
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
            sb.append("<div class=\"fe-details-line\">").append(t("informationera.fe.detail_location",
                    escape(pathText(entry.parentFolderKey != null ? entry.parentFolderKey : currentKey)))).append("</div>");
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
        changeDisplay(state -> state.showPreview = !state.showPreview, false);
    }

    private void applyPreviewVisibility() {
        Element pane = document.getElementById("fe-preview-pane");
        if (pane != null) {
            pane.setInlineStyleProperty("display", previewOpen ? "block" : "none");
        }
        Element filter = document.getElementById("fe-filter-pane");
        if (filter != null) {
            filter.setInlineStyleProperty("display", filterOpen ? "flex" : "none");
        }
        Element resizer = document.getElementById("fe-preview-resizer");
        if (resizer != null) {
            resizer.setInlineStyleProperty("display", previewOpen || filterOpen ? "block" : "none");
        }
        Element button = document.getElementById("fe-filter");
        if (button != null) {
            button.setAttribute("aria-pressed", filterOpen ? "true" : "false");
            if (filterOpen) {
                button.getClassList().add("active");
            } else {
                button.getClassList().remove("active");
            }
        }
    }

    private void toggleFilter() {
        filterOpen = !filterOpen;
        if (!filterOpen) {
            closeFilterDialog();
            closeTemplateSaveDialog();
        }
        if (filterOpen && previewOpen) {
            // 两个窗格抢同一个位置：打开筛选就收起预览。
            changeDisplay(state -> state.showPreview = false, false);
        }
        applyPreviewVisibility();
        renderFilterPane();
        renderContent();
    }

    /** 筛选窗格：顶部两个开关 + 三组条件 + 模板选择 + 底部操作。 */
    private void renderFilterPane() {
        Element pane = document.getElementById("fe-filter-pane");
        if (pane == null) {
            return;
        }
        double scroll = pane.getScrollTop();
        Element oldScroll = pane.querySelector(".fe-filter-scroll");
        if (oldScroll != null) {
            scroll = oldScroll.getScrollTop();
        }
        Element active = document.getActiveElement();
        ExplorerFilterSelection.Kind focusedKind = active == null
                ? null : ExplorerFilterSelection.Kind.byKey(active.getAttribute("data-filter-input"));
        boolean templateFocused = active != null
                && active == document.getElementById("fe-filter-template-input");
        java.util.Map<ExplorerFilterSelection.Kind, String> typed = new java.util.EnumMap<>(ExplorerFilterSelection.Kind.class);
        for (Element input : pane.querySelectorAll(".fe-filter-input")) {
            ExplorerFilterSelection.Kind kind = ExplorerFilterSelection.Kind.byKey(input.getAttribute("data-filter-input"));
            if (kind != null) {
                typed.put(kind, input.getValue());
            }
        }
        Element oldTemplateInput = document.getElementById("fe-filter-template-input");
        String templateTyped = oldTemplateInput == null ? "" : oldTemplateInput.getValue();

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-filter-scroll\">");
        sb.append("<div class=\"fe-filter-head\">");
        sb.append("<span class=\"fe-filter-title\">").append(escape(t("informationera.fe.filter"))).append("</span>");
        sb.append("<button class=\"fe-filter-clear\" id=\"fe-filter-clear\" type=\"button\">")
          .append(escape(t("informationera.fe.filter_clear"))).append("</button>");
        sb.append("</div>");

        appendFilterSwitch(sb, "fe-filter-dedupe", "informationera.fe.filter_dedupe", filterSelection.dedupe);
        appendFilterSwitch(sb, "fe-filter-subfolders", "informationera.fe.filter_expand_subfolders",
                filterSelection.includeSubfolders);

        for (ExplorerFilterSelection.Kind kind : ExplorerFilterSelection.Kind.values()) {
            appendFilterSection(sb, kind);
        }
        appendFilterTemplateSection(sb);
        sb.append("</div>");
        appendFilterFooter(sb);
        pane.setInnerHTML(sb.toString());

        for (Element input : pane.querySelectorAll(".fe-filter-input")) {
            ExplorerFilterSelection.Kind kind = ExplorerFilterSelection.Kind.byKey(input.getAttribute("data-filter-input"));
            if (kind != null) {
                input.setValue(typed.getOrDefault(kind, ""));
            }
        }
        Element templateInput = document.getElementById("fe-filter-template-input");
        if (templateInput != null) {
            templateInput.setValue(templateTyped);
        }
        Element scrollPane = pane.querySelector(".fe-filter-scroll");
        if (scrollPane != null) {
            scrollPane.setScrollTop(scroll);
        }
        bindFilterPane();
        applyFilterVisibilityState();
        updateFilterClearButton();
        if (!filterDialogOpen && focusedKind != null) {
            for (Element input : pane.querySelectorAll(".fe-filter-input")) {
                if (focusedKind.key().equals(input.getAttribute("data-filter-input"))) {
                    input.focus();
                    String text = typed.getOrDefault(focusedKind, "");
                    if (!text.isEmpty()) {
                        updateFilterSuggestions(focusedKind, text);
                    }
                    break;
                }
            }
        } else if (!filterDialogOpen && templateFocused && templateInput != null) {
            templateInput.focus();
            if (!templateTyped.isEmpty()) {
                updateFilterTemplateSuggestions(templateTyped);
            }
        }
    }

    private void appendFilterSwitch(StringBuilder sb, String id, String labelKey, boolean checked) {
        sb.append("<div class=\"fe-filter-switch-row\" id=\"").append(id).append("\" role=\"switch\" aria-checked=\"")
          .append(checked ? "true" : "false").append("\">");
        sb.append("<span class=\"fe-filter-switch").append(checked ? " on" : "")
          .append("\"><span class=\"fe-filter-switch-knob\"></span></span>");
        sb.append("<span class=\"fe-filter-switch-label\">").append(escape(t(labelKey))).append("</span>");
        sb.append("</div>");
    }

    private void appendFilterTemplateSection(StringBuilder sb) {
        sb.append("<div class=\"fe-filter-section fe-filter-template-section\">");
        sb.append("<div class=\"fe-filter-section-head\">");
        sb.append("<span class=\"fe-filter-section-title\">")
          .append(escape(t("informationera.fe.filter_templates"))).append("</span>");
        sb.append("<button class=\"fe-filter-more\" type=\"button\" id=\"fe-filter-template-more\" aria-label=\"")
          .append(escape(t("informationera.fe.filter_choose"))).append("\">")
          .append(escape(t("informationera.fe.filter_choose"))).append(" ▾</button>");
        sb.append("</div>");
        sb.append("<div class=\"fe-filter-chips\" id=\"fe-filter-template-chips\">");
        appendFilterTemplateChipContent(sb);
        sb.append("</div>");
        sb.append("<div class=\"fe-filter-input-wrap\">");
        sb.append("<input class=\"form-input-2 fe-filter-input fe-filter-template-input\" id=\"fe-filter-template-input\"")
          .append(" type=\"text\" placeholder=\"")
          .append(escape(t("informationera.fe.filter_template_input_placeholder"))).append("\">");
        sb.append("<div class=\"fe-filter-suggest\" id=\"fe-filter-template-suggest\" style=\"display:none;\"></div>");
        sb.append("</div></div>");
    }

    private void appendFilterFooter(StringBuilder sb) {
        sb.append("<div class=\"fe-filter-footer\">");
        sb.append("<button class=\"fe-filter-action primary\" id=\"fe-filter-save-template\" type=\"button\">")
          .append(escape(t("informationera.fe.filter_save_template"))).append("</button>");
        sb.append("</div>");
    }

    /** 清除按钮现在负责搜索、条件、两个开关和模板标记，因此状态判断要覆盖全部规则。 */
    private boolean canClearFilter() {
        return !searchQuery.isBlank()
                || !filterSelection.isEmpty()
                || filterSelection.includeSubfolders
                || filterSelection.dedupe
                || appliedFilterTemplate != null;
    }

    private void updateFilterClearButton() {
        Element button = document.getElementById("fe-filter-clear");
        if (button == null) {
            return;
        }
        boolean enabled = canClearFilter();
        button.setAttribute("aria-disabled", enabled ? "false" : "true");
        if (enabled) {
            button.getClassList().remove("disabled");
        } else {
            button.getClassList().add("disabled");
        }
    }

    private void appendFilterTemplateChipContent(StringBuilder sb) {
        if (appliedFilterTemplate == null) {
            sb.append("<span class=\"fe-filter-none\">")
              .append(escape(t("informationera.fe.filter_template_none"))).append("</span>");
        } else {
            sb.append(filterTemplateChipHtml(appliedFilterTemplate));
        }
    }

    private String filterTemplateChipHtml(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("<span class=\"fe-filter-chip fe-filter-template-chip\" data-template-chip=\"")
          .append(escape(name)).append("\" title=\"").append(escape(name)).append("\">");
        sb.append("<span class=\"fe-filter-chip-text\">").append(escape(name)).append("</span>");
        sb.append("<span class=\"fe-filter-chip-x\" role=\"button\" aria-label=\"")
          .append(escape(t("informationera.fe.filter_template_remove"))).append("\">×</span>");
        sb.append("</span>");
        return sb.toString();
    }

    private void updateFilterTemplateChip() {
        Element chips = document == null ? null : document.getElementById("fe-filter-template-chips");
        if (chips == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        appendFilterTemplateChipContent(sb);
        chips.setInnerHTML(sb.toString());
        bindFilterTemplateChips(chips);
    }

    private void bindFilterTemplateChips(Element scope) {
        for (Element chip : scope.querySelectorAll(".fe-filter-template-chip")) {
            chip.addEventListener("click", e -> {
                e.stopPropagation();
                if (appliedFilterTemplate == null) {
                    return;
                }
                clearAppliedFilterTemplate();
                if (filterDialogOpen && filterDialogTemplates) {
                    renderFilterDialog();
                }
            });
        }
    }

    /** 手工改动任一规则后清空“当前应用模板”，避免继续显示为完全匹配。 */
    private void clearAppliedFilterTemplate() {
        if (appliedFilterTemplate == null) {
            return;
        }
        appliedFilterTemplate = null;
        updateFilterTemplateChip();
        updateFilterClearButton();
    }

    private void appendFilterSection(StringBuilder sb, ExplorerFilterSelection.Kind kind) {
        String key = escape(kind.key());
        sb.append("<div class=\"fe-filter-section\" data-filter-section=\"").append(key).append("\">");
        sb.append("<div class=\"fe-filter-section-head\">");
        sb.append("<span class=\"fe-filter-section-title\">").append(escape(t(kind.labelKey()))).append("</span>");
        sb.append("<button class=\"fe-filter-more\" type=\"button\" data-filter-more=\"").append(key)
          .append("\" aria-label=\"").append(escape(t("informationera.fe.filter_choose")))
          .append("\">").append(escape(t("informationera.fe.filter_choose"))).append(" ▾</button>");
        sb.append("</div>");
        sb.append("<div class=\"fe-filter-chips\" data-filter-chips=\"").append(key).append("\">");
        java.util.Set<String> selected = filterSelection.values(kind);
        if (selected.isEmpty()) {
            sb.append("<span class=\"fe-filter-none\">").append(escape(t("informationera.fe.filter_none")))
              .append("</span>");
        } else {
            for (String value : selected) {
                sb.append(filterChipHtml(kind, value, true));
            }
        }
        sb.append("</div>");
        sb.append("<div class=\"fe-filter-input-wrap\">");
        sb.append("<input class=\"form-input-2 fe-filter-input\" type=\"text\" data-filter-input=\"").append(key)
          .append("\" placeholder=\"").append(escape(t("informationera.fe.filter_add_placeholder", t(kind.labelKey()))))
          .append("\">");
        sb.append("<div class=\"fe-filter-suggest\" data-filter-suggest=\"").append(key)
          .append("\" style=\"display:none;\"></div>");
        sb.append("</div></div>");
    }

    private String filterChipHtml(ExplorerFilterSelection.Kind kind, String value, boolean removable) {
        String label = ExplorerFilterCatalog.label(kind, value);
        StringBuilder sb = new StringBuilder();
        sb.append("<span class=\"fe-filter-chip\" data-chip-kind=\"").append(escape(kind.key()))
          .append("\" data-chip-value=\"").append(escape(value))
          .append("\" title=\"").append(escape(value)).append("\">");
        sb.append("<span class=\"fe-filter-chip-text\">").append(escape(label)).append("</span>");
        if (removable) {
            sb.append("<span class=\"fe-filter-chip-x\" role=\"button\" aria-label=\"")
              .append(escape(t("informationera.fe.filter_remove"))).append("\">×</span>");
        }
        sb.append("</span>");
        return sb.toString();
    }

    private void bindFilterPane() {
        Element pane = document.getElementById("fe-filter-pane");
        if (pane == null) {
            return;
        }
        Element clear = pane.querySelector("#fe-filter-clear");
        if (clear != null) {
            clear.addEventListener("click", e -> {
                e.stopPropagation();
                if ("true".equals(clear.getAttribute("aria-disabled"))) {
                    return;
                }
                resetFilter();
            });
        }
        Element switchRow = pane.querySelector("#fe-filter-subfolders");
        if (switchRow != null) {
            switchRow.addEventListener("click", e -> {
                e.stopPropagation();
                clearAppliedFilterTemplate();
                filterSelection.includeSubfolders = !filterSelection.includeSubfolders;
                renderFilterPane();
                renderContent();
            });
        }
        Element dedupeRow = pane.querySelector("#fe-filter-dedupe");
        if (dedupeRow != null) {
            dedupeRow.addEventListener("click", e -> {
                e.stopPropagation();
                clearAppliedFilterTemplate();
                filterSelection.dedupe = !filterSelection.dedupe;
                renderFilterPane();
                renderContent();
            });
        }
        Element templateMore = pane.querySelector("#fe-filter-template-more");
        if (templateMore != null) {
            templateMore.addEventListener("click", e -> {
                e.stopPropagation();
                showFilterTemplateDialog();
            });
        }
        Element templateInput = pane.querySelector("#fe-filter-template-input");
        if (templateInput != null) {
            templateInput.addEventListener("input", e -> updateFilterTemplateSuggestions(templateInput.getValue()));
            templateInput.addEventListener("keyup", e -> {
                if (e instanceof KeyEvent ke && "Escape".equals(ke.key)) {
                    return;
                }
                updateFilterTemplateSuggestions(templateInput.getValue());
            });
            templateInput.addEventListener("focus", e -> updateFilterTemplateSuggestions(templateInput.getValue()));
            templateInput.addEventListener("keydown", e -> {
                e.stopPropagation();
                if (!(e instanceof KeyEvent ke)) {
                    return;
                }
                if ("Enter".equals(ke.key)) {
                    submitFilterTemplateInput(templateInput);
                } else if ("Escape".equals(ke.key)) {
                    showFilterTemplateSuggestions(List.of());
                }
            });
        }
        Element saveTemplate = pane.querySelector("#fe-filter-save-template");
        if (saveTemplate != null) {
            saveTemplate.addEventListener("click", e -> {
                e.stopPropagation();
                showTemplateSaveDialog();
            });
        }
        bindFilterTemplateChips(pane);
        for (Element more : pane.querySelectorAll(".fe-filter-more")) {
            ExplorerFilterSelection.Kind kind = ExplorerFilterSelection.Kind.byKey(more.getAttribute("data-filter-more"));
            if (kind != null) {
                more.addEventListener("click", e -> {
                    e.stopPropagation();
                    showFilterDialog(kind);
                });
            }
        }
        for (Element input : pane.querySelectorAll(".fe-filter-input")) {
            ExplorerFilterSelection.Kind kind = ExplorerFilterSelection.Kind.byKey(input.getAttribute("data-filter-input"));
            if (kind == null) {
                continue;
            }
            input.addEventListener("input", e -> updateFilterSuggestions(kind, input.getValue()));
            input.addEventListener("keyup", e -> {
                if (e instanceof KeyEvent ke && "Escape".equals(ke.key)) {
                    return;
                }
                updateFilterSuggestions(kind, input.getValue());
            });
            input.addEventListener("focus", e -> updateFilterSuggestions(kind, input.getValue()));
            input.addEventListener("keydown", e -> {
                e.stopPropagation();
                if (!(e instanceof KeyEvent ke)) {
                    return;
                }
                if ("Enter".equals(ke.key)) {
                    submitFilterInput(kind, input);
                } else if ("Escape".equals(ke.key)) {
                    showFilterSuggestions(kind, List.of());
                }
            });
        }
        for (Element chip : pane.querySelectorAll(".fe-filter-chip")) {
            chip.addEventListener("click", e -> {
                e.stopPropagation();
                ExplorerFilterSelection.Kind kind = ExplorerFilterSelection.Kind.byKey(chip.getAttribute("data-chip-kind"));
                String value = chip.getAttribute("data-chip-value");
                if (kind != null && value != null) {
                    removeFilterValue(kind, value);
                }
            });
        }
    }

    private void addFilterValue(ExplorerFilterSelection.Kind kind, String rawValue) {
        if (rawValue != null) {
            addFilterValues(kind, List.of(rawValue));
        }
    }

    /** 一次加入多个值；去重后只在真的新增了条件时刷新界面。 */
    private void addFilterValues(ExplorerFilterSelection.Kind kind, List<String> rawValues) {
        if (kind == null || rawValues == null || rawValues.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String rawValue : rawValues) {
            String value = ExplorerFilterSelection.normalize(rawValue);
            if (value == null) {
                continue;
            }
            if (filterSelection.add(kind, value)) {
                ExplorerPreferences.recordFilterUse(kind.key(), value);
                changed = true;
            }
        }
        if (changed) {
            clearAppliedFilterTemplate();
            refreshFilterViews();
        }
    }

    private void removeFilterValue(ExplorerFilterSelection.Kind kind, String rawValue) {
        if (kind == null || !filterSelection.remove(kind, rawValue)) {
            return;
        }
        clearAppliedFilterTemplate();
        refreshFilterViews();
    }

    /** 重置整套筛选规则：条件、搜索、两个开关和当前应用模板都清空；模板本身与历史保留。 */
    private void resetFilter() {
        filterSelection.reset();
        appliedFilterTemplate = null;
        searchQuery = "";
        Element search = document.getElementById("fe-search");
        if (search != null) {
            search.setValue("");
        }
        Element pane = document.getElementById("fe-filter-pane");
        if (pane != null) {
            for (Element input : pane.querySelectorAll("input")) {
                input.setValue("");
            }
        }
        refreshFilterViews();
    }

    /** 应用模板：整套替换当前规则（搜索、四类条件、平铺、去重），不做递归引用。 */
    private void applyFilterTemplate(ExplorerFilterTemplate template) {
        if (template == null) {
            return;
        }
        filterSelection.reset();
        for (String value : template.tags) {
            filterSelection.add(ExplorerFilterSelection.Kind.TAG, value);
        }
        for (String value : template.mods) {
            filterSelection.add(ExplorerFilterSelection.Kind.MOD, value);
        }
        for (String value : template.tabs) {
            filterSelection.add(ExplorerFilterSelection.Kind.TAB, value);
        }
        for (String value : template.keywords) {
            filterSelection.add(ExplorerFilterSelection.Kind.KEYWORD, value);
        }
        filterSelection.includeSubfolders = template.includeSubfolders;
        filterSelection.dedupe = template.dedupe;
        searchQuery = template.search;
        Element search = document.getElementById("fe-search");
        if (search != null) {
            search.setValue(searchQuery);
        }
        Element pane = document.getElementById("fe-filter-pane");
        if (pane != null) {
            for (Element input : pane.querySelectorAll("input")) {
                input.setValue("");
            }
        }
        Element dialogSearch = document.getElementById("fe-filter-dialog-search");
        if (dialogSearch != null) {
            dialogSearch.setValue("");
        }
        appliedFilterTemplate = template.name;
        ExplorerPreferences.recordFilterUse(ExplorerFilterTemplate.HISTORY_KIND, template.name);
        refreshFilterViews();
    }

    private void applyFilterTemplateByName(String name) {
        ExplorerFilterTemplate template = ExplorerPreferences.filterTemplate(name);
        if (template == null) {
            setStatus(t("informationera.fe.filter_template_missing"));
            return;
        }
        applyFilterTemplate(template);
    }

    // ---------- 保存筛选模板 ----------

    private void showTemplateSaveDialog() {
        Element dialog = document.getElementById("fe-template-save-dialog");
        Element overlay = document.getElementById("fe-template-save-overlay");
        if (dialog == null || overlay == null) {
            return;
        }
        hideTooltip();
        closeContextMenu();
        templateSaveDialogOpen = true;
        templateSaveConfirmOverwrite = false;
        templateSaveConfirmName = null;
        Element title = document.getElementById("fe-template-save-title");
        if (title != null) {
            title.setTextContent(t("informationera.fe.filter_template_save_title"));
        }
        Element message = document.getElementById("fe-template-save-message");
        if (message != null) {
            message.setTextContent("");
        }
        Element input = document.getElementById("fe-template-save-name");
        if (input != null) {
            input.setValue("");
            input.setPlaceholder(t("informationera.fe.filter_template_name_placeholder"));
        }
        Element ok = document.getElementById("fe-template-save-ok");
        if (ok != null) {
            ok.setTextContent(t("informationera.fe.filter_template_save"));
        }
        Element cancel = document.getElementById("fe-template-save-cancel");
        if (cancel != null) {
            cancel.setTextContent(t("informationera.fe.cancel"));
        }
        Size viewport = document.getViewportSize();
        double width = 420;
        double height = 210;
        dialog.setInlineStyleProperty("left", px(Math.max(8, (viewport.width() - width) / 2)));
        dialog.setInlineStyleProperty("top", px(Math.max(8, (viewport.height() - height) / 2)));
        dialog.setInlineStyleProperty("height", px(height));
        dialog.setInlineStyleProperty("display", "flex");
        overlay.setInlineStyleProperty("display", "block");
        if (input != null) {
            input.focus();
        }
    }

    private void closeTemplateSaveDialog() {
        templateSaveDialogOpen = false;
        templateSaveConfirmOverwrite = false;
        templateSaveConfirmName = null;
        if (document == null) {
            return;
        }
        Element dialog = document.getElementById("fe-template-save-dialog");
        Element overlay = document.getElementById("fe-template-save-overlay");
        if (dialog != null) {
            dialog.setInlineStyleProperty("display", "none");
        }
        if (overlay != null) {
            overlay.setInlineStyleProperty("display", "none");
        }
        Element input = document.getElementById("fe-template-save-name");
        if (input != null) {
            input.setValue("");
        }
    }

    /** 改名后必须重新确认是否覆盖同名模板，避免第一次警告后改个名仍沿用覆盖状态。 */
    private void onTemplateSaveNameChanged() {
        Element input = document.getElementById("fe-template-save-name");
        String current = input == null || input.getValue() == null ? "" : input.getValue().trim();
        if (templateSaveConfirmOverwrite && current.equals(templateSaveConfirmName)) {
            return;
        }
        templateSaveConfirmOverwrite = false;
        templateSaveConfirmName = null;
        Element message = document.getElementById("fe-template-save-message");
        if (message != null) {
            message.setTextContent("");
        }
        Element ok = document.getElementById("fe-template-save-ok");
        if (ok != null) {
            ok.setTextContent(t("informationera.fe.filter_template_save"));
        }
    }

    private void saveFilterTemplateFromDialog() {
        if (!templateSaveDialogOpen) {
            return;
        }
        Element input = document.getElementById("fe-template-save-name");
        String name = input == null || input.getValue() == null ? "" : input.getValue().trim();
        Element message = document.getElementById("fe-template-save-message");
        if (name.isEmpty()) {
            templateSaveConfirmOverwrite = false;
            templateSaveConfirmName = null;
            if (message != null) {
                message.setTextContent(t("informationera.fe.filter_template_name_required"));
            }
            Element ok = document.getElementById("fe-template-save-ok");
            if (ok != null) {
                ok.setTextContent(t("informationera.fe.filter_template_save"));
            }
            return;
        }
        boolean exists = ExplorerPreferences.filterTemplateExists(name);
        if (exists && !templateSaveConfirmOverwrite) {
            // 第一次只警告，第二次才真正覆盖：防止同名保存直接吞掉旧模板。
            templateSaveConfirmOverwrite = true;
            templateSaveConfirmName = name;
            if (message != null) {
                message.setTextContent(t("informationera.fe.filter_template_exists_confirm"));
            }
            Element ok = document.getElementById("fe-template-save-ok");
            if (ok != null) {
                ok.setTextContent(t("informationera.fe.filter_template_overwrite"));
            }
            return;
        }
        ExplorerFilterTemplate template = ExplorerFilterTemplate.capture(name, searchQuery, filterSelection);
        ExplorerPreferences.saveFilterTemplate(template);
        appliedFilterTemplate = template.name;
        closeTemplateSaveDialog();
        renderFilterPane();
        if (filterDialogOpen && filterDialogTemplates) {
            renderFilterDialog();
        }
        setStatus(t("informationera.fe.filter_template_saved"));
    }

    private ExplorerFilterTemplate resolveFilterTemplate(String rawText) {
        String text = ExplorerFilterSelection.normalize(rawText);
        if (text == null) {
            return null;
        }
        ExplorerFilterTemplate exact = ExplorerPreferences.filterTemplate(text);
        if (exact != null) {
            return exact;
        }
        List<String> suggestions = filterTemplateSuggestions(text, 1);
        return suggestions.isEmpty() ? null : ExplorerPreferences.filterTemplate(suggestions.get(0));
    }

    private List<String> filterTemplateSuggestions(String rawQuery, int limit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        List<String> result = new ArrayList<>();
        if (query.isEmpty() || limit <= 0) {
            return result;
        }
        for (String name : ExplorerPreferences.filterTemplateNames()) {
            if (ExplorerSearch.matches(name, query)) {
                result.add(name);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private void updateFilterTemplateSuggestions(String rawQuery) {
        hideOtherFilterSuggestions(null);
        showFilterTemplateSuggestions(filterTemplateSuggestions(rawQuery, 8));
    }

    private void showFilterTemplateSuggestions(List<String> names) {
        Element box = document.getElementById("fe-filter-template-suggest");
        if (box == null) {
            return;
        }
        if (names.isEmpty()) {
            box.setInnerHTML("");
            box.setInlineStyleProperty("display", "none");
            return;
        }
        box.setInlineStyleProperty("display", "flex");
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            sb.append("<button class=\"fe-filter-suggestion\" type=\"button\" data-template-suggest=\"")
              .append(escape(name)).append("\" title=\"").append(escape(name)).append("\">")
              .append(escape(name)).append("</button>");
        }
        box.setInnerHTML(sb.toString());
        for (Element button : box.querySelectorAll(".fe-filter-suggestion")) {
            button.addEventListener("mousedown", e -> e.preventDefault());
            button.addEventListener("click", e -> {
                e.stopPropagation();
                String name = button.getAttribute("data-template-suggest");
                if (name != null) {
                    Element input = document.getElementById("fe-filter-template-input");
                    if (input != null) {
                        input.setValue("");
                    }
                    applyFilterTemplateByName(name);
                }
            });
        }
    }

    private void submitFilterTemplateInput(Element input) {
        String text = input == null ? "" : input.getValue();
        ExplorerFilterTemplate template = resolveFilterTemplate(text);
        if (template == null) {
            setStatus(t("informationera.fe.filter_template_missing"));
            return;
        }
        input.setValue("");
        applyFilterTemplate(template);
    }

    /** 高频/最近里只展示仍然存在的模板，单行最多 8 个。 */
    private List<String> existingTemplateHistory(boolean frequent, int limit) {
        List<String> raw = frequent
                ? ExplorerPreferences.frequentFilterValues(ExplorerFilterTemplate.HISTORY_KIND, 80)
                : ExplorerPreferences.recentFilterValues(ExplorerFilterTemplate.HISTORY_KIND, 80);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : raw) {
            ExplorerFilterTemplate template = ExplorerPreferences.filterTemplate(name);
            if (template == null) {
                continue;
            }
            String key = name.toLowerCase(java.util.Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            result.add(template.name);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private String filterTemplateHistoryRow(String title, List<String> names) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-filter-dialog-section\">");
        sb.append("<div class=\"fe-filter-dialog-head\">").append(escape(title)).append("</div>");
        sb.append("<div class=\"fe-filter-history-row\">");
        if (names.isEmpty()) {
            sb.append("<span class=\"fe-filter-none\">").append(escape(t("informationera.fe.filter_none")))
              .append("</span>");
        } else {
            for (String name : names) {
                sb.append("<button class=\"fe-filter-history-value\" type=\"button\" data-filter-template-history=\"")
                  .append(escape(name)).append("\" title=\"").append(escape(name)).append("\">")
                  .append(escape(name)).append("</button>");
            }
        }
        sb.append("</div></div>");
        return sb.toString();
    }

    /** 条件变化后同时刷新窗格、弹窗和内容区；界面上的排序/分组/虚拟滚动全部沿用。 */
    private void refreshFilterViews() {
        renderFilterPane();
        if (filterDialogOpen) {
            renderFilterDialog();
        }
        renderContent();
    }

    /** 标签/创造标签页数据同步后，筛选窗格和候选重新读取；未打开筛选的窗口不需要动作。 */
    void onFilterDataChanged() {
        if (!filterOpen || document == null) {
            return;
        }
        invalidateFilterCandidates();
        renderFilterPane();
        if (filterDialogOpen) {
            renderFilterDialog();
        }
        renderContent();
    }

    private void updateFilterSuggestions(ExplorerFilterSelection.Kind kind, String rawQuery) {
        hideOtherFilterSuggestions(kind);
        List<String> suggestions = filterSuggestions(kind, rawQuery, 8);
        showFilterSuggestions(kind, suggestions);
    }

    /** 只保留当前输入框的补全列表，避免多个输入的浮层同时挂着、点击时命中错误的候选。 */
    private void hideOtherFilterSuggestions(ExplorerFilterSelection.Kind keep) {
        Element pane = document.getElementById("fe-filter-pane");
        if (pane == null) {
            return;
        }
        for (Element box : pane.querySelectorAll(".fe-filter-suggest")) {
            String kindKey = box.getAttribute("data-filter-suggest");
            if (kindKey != null && !kindKey.equals(keep == null ? null : keep.key())) {
                box.setInnerHTML("");
                box.setInlineStyleProperty("display", "none");
            }
        }
        Element template = document.getElementById("fe-filter-template-suggest");
        if (template != null) {
            template.setInnerHTML("");
            template.setInlineStyleProperty("display", "none");
        }
    }

    private void showFilterSuggestions(ExplorerFilterSelection.Kind kind, List<String> suggestions) {
        Element pane = document.getElementById("fe-filter-pane");
        if (pane == null) {
            return;
        }
        Element box = null;
        for (Element candidate : pane.querySelectorAll(".fe-filter-suggest")) {
            if (kind.key().equals(candidate.getAttribute("data-filter-suggest"))) {
                box = candidate;
                break;
            }
        }
        if (box == null) {
            return;
        }
        if (suggestions.isEmpty()) {
            box.setInnerHTML("");
            box.setInlineStyleProperty("display", "none");
            return;
        }
        box.setInlineStyleProperty("display", "flex");
        StringBuilder sb = new StringBuilder();
        for (String value : suggestions) {
            sb.append("<button class=\"fe-filter-suggestion\" type=\"button\" data-suggest-kind=\"")
              .append(escape(kind.key())).append("\" data-suggest-value=\"").append(escape(value))
              .append("\" title=\"").append(escape(value)).append("\">")
              .append(escape(ExplorerFilterCatalog.label(kind, value))).append("</button>");
        }
        box.setInnerHTML(sb.toString());
        Element input = paneFilterInput(kind);
        for (Element button : box.querySelectorAll(".fe-filter-suggestion")) {
            button.addEventListener("mousedown", e -> e.preventDefault());
            button.addEventListener("click", e -> {
                e.stopPropagation();
                String value = button.getAttribute("data-suggest-value");
                if (value != null) {
                    if (input != null) {
                        input.setValue("");
                    }
                    addFilterValue(kind, value);
                }
            });
        }
    }

    private Element paneFilterInput(ExplorerFilterSelection.Kind kind) {
        Element pane = document.getElementById("fe-filter-pane");
        if (pane == null || kind == null) {
            return null;
        }
        for (Element input : pane.querySelectorAll(".fe-filter-input")) {
            if (kind.key().equals(input.getAttribute("data-filter-input"))) {
                return input;
            }
        }
        return null;
    }

    private void submitFilterInput(ExplorerFilterSelection.Kind kind, Element input) {
        String text = input == null ? "" : input.getValue();
        List<String> values = resolveFilterValues(kind, text);
        if (values.isEmpty()) {
            return;
        }
        input.setValue("");
        addFilterValues(kind, values);
    }

    /**
     * 把输入文本解析成一组条件值。整段文本能精确匹配显示文本或稳定 id 时只加一个；
     * 否则按逗号拆开逐项解析。标签/模组允许手工 id，创造标签页只接受能解析到稳定 id 的值。
     */
    private List<String> resolveFilterValues(ExplorerFilterSelection.Kind kind, String rawText) {
        if (kind == null) {
            return List.of();
        }
        boolean localizedSearch = kind == ExplorerFilterSelection.Kind.TAB
                || kind == ExplorerFilterSelection.Kind.KEYWORD;
        return ExplorerFilterMatching.resolve(rawText, kind != ExplorerFilterSelection.Kind.TAB,
                filterCandidates(kind), value -> ExplorerFilterCatalog.label(kind, value),
                localizedSearch);
    }

    private List<String> filterSuggestions(ExplorerFilterSelection.Kind kind, String rawQuery, int limit) {
        if (kind == null) {
            return List.of();
        }
        // 显示文本是补全的唯一匹配面：标签/模组显示英文 id，标签页/关键词显示当前本地化文本；
        // 拼音/首字母只用于标签页和关键词，标签/模组仍保持 id 的原文匹配语义。
        boolean localizedSearch = kind == ExplorerFilterSelection.Kind.TAB
                || kind == ExplorerFilterSelection.Kind.KEYWORD;
        return ExplorerFilterMatching.suggestions(rawQuery, limit, filterCandidates(kind),
                value -> ExplorerFilterCatalog.label(kind, value),
                localizedSearch);
    }

    private List<String> filterCandidates(ExplorerFilterSelection.Kind kind) {
        if (kind == null) {
            return List.of();
        }
        int languageVersion = ExplorerKeywordIndex.languageVersion();
        if (languageVersion != filterCandidateLanguageVersion) {
            filterCandidateLanguageVersion = languageVersion;
            filterCandidateCache.clear();
        }
        List<String> cached = filterCandidateCache.get(kind);
        if (cached != null) {
            return cached;
        }
        List<String> computed = ExplorerFilterCatalog.candidates(
                filterScopeEntries(filterOpen && filterSelection.includeSubfolders), kind);
        filterCandidateCache.put(kind, computed);
        return computed;
    }

    private void invalidateFilterCandidates() {
        filterCandidateCache.clear();
    }

    // ---------- 筛选条件弹窗 ----------

    private void showFilterDialog(ExplorerFilterSelection.Kind kind) {
        Element dialog = document.getElementById("fe-filter-dialog");
        Element overlay = document.getElementById("fe-filter-dialog-overlay");
        if (dialog == null || overlay == null || kind == null) {
            return;
        }
        hideTooltip();
        closeContextMenu();
        filterDialogKind = kind;
        filterDialogTemplates = false;
        filterDialogOpen = true;
        Element title = document.getElementById("fe-filter-dialog-title");
        if (title != null) {
            title.setTextContent(t("informationera.fe.filter_dialog_title", t(kind.labelKey())));
        }
        renderFilterDialog();
        Element dialogBody = document.getElementById("fe-filter-dialog-body");
        if (dialogBody != null) {
            dialogBody.setScrollTop(0);
        }
        Size viewport = document.getViewportSize();
        double width = 560;
        double height = Math.min(620, Math.max(280, viewport.height() - 120));
        double left = Math.max(8, (viewport.width() - width) / 2);
        double top = Math.max(8, (viewport.height() - height) / 2);
        dialog.setInlineStyleProperty("left", px(left));
        dialog.setInlineStyleProperty("top", px(top));
        dialog.setInlineStyleProperty("height", px(height));
        dialog.setInlineStyleProperty("display", "flex");
        overlay.setInlineStyleProperty("display", "block");
        Element search = document.getElementById("fe-filter-dialog-search");
        if (search != null) {
            search.focus();
        }
    }

    private void showFilterTemplateDialog() {
        Element dialog = document.getElementById("fe-filter-dialog");
        Element overlay = document.getElementById("fe-filter-dialog-overlay");
        if (dialog == null || overlay == null) {
            return;
        }
        hideTooltip();
        closeContextMenu();
        filterDialogTemplates = true;
        filterDialogOpen = true;
        Element title = document.getElementById("fe-filter-dialog-title");
        if (title != null) {
            title.setTextContent(t("informationera.fe.filter_template_dialog_title"));
        }
        renderFilterDialog();
        Element dialogBody = document.getElementById("fe-filter-dialog-body");
        if (dialogBody != null) {
            dialogBody.setScrollTop(0);
        }
        Size viewport = document.getViewportSize();
        double width = 560;
        double height = Math.min(620, Math.max(280, viewport.height() - 120));
        double left = Math.max(8, (viewport.width() - width) / 2);
        double top = Math.max(8, (viewport.height() - height) / 2);
        dialog.setInlineStyleProperty("left", px(left));
        dialog.setInlineStyleProperty("top", px(top));
        dialog.setInlineStyleProperty("height", px(height));
        dialog.setInlineStyleProperty("display", "flex");
        overlay.setInlineStyleProperty("display", "block");
        Element search = document.getElementById("fe-filter-dialog-search");
        if (search != null) {
            search.focus();
        }
    }

    /** 模板弹窗：复用五段结构，但“候选全集”是已保存模板，点击即整套应用。 */
    private void renderFilterTemplateDialog(Element body, String typed, double scroll) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-filter-dialog-search-wrap\">");
        sb.append("<input class=\"form-input-2 fe-filter-input\" id=\"fe-filter-dialog-search\" type=\"text\" placeholder=\"")
          .append(escape(t("informationera.fe.filter_template_search_placeholder"))).append("\">");
        sb.append("<div class=\"fe-filter-suggest fe-filter-dialog-suggest\" id=\"fe-filter-dialog-suggest\" style=\"display:none;\"></div>");
        sb.append("</div>");

        sb.append("<div class=\"fe-filter-dialog-section\">");
        sb.append("<div class=\"fe-filter-dialog-head\">")
          .append(escape(t("informationera.fe.filter_template_selected"))).append("</div>");
        sb.append("<div class=\"fe-filter-chips\">");
        appendFilterTemplateChipContent(sb);
        sb.append("</div></div>");

        sb.append(filterTemplateHistoryRow(t("informationera.fe.filter_frequent"),
                existingTemplateHistory(true, 8)));
        sb.append(filterTemplateHistoryRow(t("informationera.fe.filter_recent"),
                existingTemplateHistory(false, 8)));

        List<String> names = ExplorerPreferences.filterTemplateNames();
        sb.append("<div class=\"fe-filter-dialog-section fe-filter-dialog-candidates\">");
        sb.append("<div class=\"fe-filter-dialog-head\">")
          .append(escape(t("informationera.fe.filter_template_candidates")))
          .append("<span class=\"fe-filter-count\">").append(names.size()).append("</span></div>");
        sb.append("<div class=\"fe-filter-value-list\">");
        if (names.isEmpty()) {
            sb.append("<span class=\"fe-filter-none\">")
              .append(escape(t("informationera.fe.filter_template_no_candidates"))).append("</span>");
        } else {
            for (String name : names) {
                boolean applied = appliedFilterTemplate != null && name.equalsIgnoreCase(appliedFilterTemplate);
                sb.append("<button class=\"fe-filter-value\" type=\"button\" data-filter-template-value=\"")
                  .append(escape(name)).append("\" title=\"").append(escape(name)).append("\">")
                  .append(escape(name))
                  .append(applied ? "<span class=\"fe-filter-check\">✓</span>" : "")
                  .append("</button>");
            }
        }
        sb.append("</div></div>");
        body.setInnerHTML(sb.toString());
        body.setScrollTop(scroll);

        Element search = document.getElementById("fe-filter-dialog-search");
        if (search != null) {
            search.setValue(typed);
            search.addEventListener("input", e -> renderFilterTemplateDialogSuggestions(search.getValue()));
            search.addEventListener("keyup", e -> {
                if (e instanceof KeyEvent ke && "Escape".equals(ke.key)) {
                    return;
                }
                renderFilterTemplateDialogSuggestions(search.getValue());
            });
            search.addEventListener("keydown", e -> {
                e.stopPropagation();
                if (!(e instanceof KeyEvent ke)) {
                    return;
                }
                if ("Enter".equals(ke.key)) {
                    ExplorerFilterTemplate template = resolveFilterTemplate(search.getValue());
                    if (template != null) {
                        search.setValue("");
                        applyFilterTemplate(template);
                    } else {
                        setStatus(t("informationera.fe.filter_template_missing"));
                    }
                } else if ("Escape".equals(ke.key)) {
                    Element suggest = document.getElementById("fe-filter-dialog-suggest");
                    boolean showing = suggest != null && suggest.querySelector(".fe-filter-suggestion") != null;
                    if (showing) {
                        renderFilterTemplateDialogSuggestions("");
                    } else {
                        closeFilterDialog();
                    }
                }
            });
            if (!typed.isEmpty() && filterDialogOpen) {
                renderFilterTemplateDialogSuggestions(typed);
            }
            if (filterDialogOpen) {
                search.focus();
            }
        }
        for (Element chip : body.querySelectorAll(".fe-filter-template-chip")) {
            chip.addEventListener("click", e -> {
                e.stopPropagation();
                clearAppliedFilterTemplate();
                renderFilterDialog();
            });
        }
        for (Element button : body.querySelectorAll(".fe-filter-history-value[data-filter-template-history]")) {
            button.addEventListener("click", e -> {
                e.stopPropagation();
                String name = button.getAttribute("data-filter-template-history");
                if (name != null) {
                    applyFilterTemplateByName(name);
                }
            });
        }
        for (Element button : body.querySelectorAll(".fe-filter-value[data-filter-template-value]")) {
            button.addEventListener("click", e -> {
                e.stopPropagation();
                String name = button.getAttribute("data-filter-template-value");
                if (name != null) {
                    applyFilterTemplateByName(name);
                }
            });
        }
    }

    private void renderFilterTemplateDialogSuggestions(String rawQuery) {
        Element box = document.getElementById("fe-filter-dialog-suggest");
        if (box == null) {
            return;
        }
        List<String> names = filterTemplateSuggestions(rawQuery, 10);
        if (names.isEmpty()) {
            box.setInnerHTML("");
            box.setInlineStyleProperty("display", "none");
            return;
        }
        box.setInlineStyleProperty("display", "flex");
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            sb.append("<button class=\"fe-filter-suggestion\" type=\"button\" data-template-suggest=\"")
              .append(escape(name)).append("\" title=\"").append(escape(name)).append("\">")
              .append(escape(name)).append("</button>");
        }
        box.setInnerHTML(sb.toString());
        for (Element button : box.querySelectorAll(".fe-filter-suggestion")) {
            button.addEventListener("mousedown", e -> e.preventDefault());
            button.addEventListener("click", e -> {
                e.stopPropagation();
                String name = button.getAttribute("data-template-suggest");
                if (name != null) {
                    Element search = document.getElementById("fe-filter-dialog-search");
                    if (search != null) {
                        search.setValue("");
                    }
                    applyFilterTemplateByName(name);
                }
            });
        }
    }

    private void renderFilterDialog() {
        Element body = document.getElementById("fe-filter-dialog-body");
        if (body == null || !filterDialogOpen) {
            return;
        }
        Element dialogTitle = document.getElementById("fe-filter-dialog-title");
        if (dialogTitle != null) {
            dialogTitle.setTextContent(filterDialogTemplates
                    ? t("informationera.fe.filter_template_dialog_title")
                    : t("informationera.fe.filter_dialog_title", t(filterDialogKind.labelKey())));
        }
        ExplorerFilterSelection.Kind kind = filterDialogKind;
        String typed = "";
        Element oldSearch = document.getElementById("fe-filter-dialog-search");
        if (oldSearch != null) {
            typed = oldSearch.getValue();
        }
        double scroll = body.getScrollTop();
        if (filterDialogTemplates) {
            renderFilterTemplateDialog(body, typed, scroll);
            return;
        }
        StringBuilder sb = new StringBuilder();

        sb.append("<div class=\"fe-filter-dialog-search-wrap\">");
        sb.append("<input class=\"form-input-2 fe-filter-input\" id=\"fe-filter-dialog-search\" type=\"text\" placeholder=\"")
          .append(escape(t("informationera.fe.filter_search_placeholder", t(kind.labelKey())))).append("\">");
        sb.append("<div class=\"fe-filter-suggest fe-filter-dialog-suggest\" id=\"fe-filter-dialog-suggest\" style=\"display:none;\"></div>");
        sb.append("</div>");

        sb.append("<div class=\"fe-filter-dialog-section\">");
        sb.append("<div class=\"fe-filter-dialog-head\">").append(escape(t("informationera.fe.filter_selected")))
          .append("</div>");
        java.util.Set<String> selected = filterSelection.values(kind);
        sb.append("<div class=\"fe-filter-chips\">");
        if (selected.isEmpty()) {
            sb.append("<span class=\"fe-filter-none\">").append(escape(t("informationera.fe.filter_none")))
              .append("</span>");
        } else {
            for (String value : selected) {
                sb.append(filterChipHtml(kind, value, true));
            }
        }
        sb.append("</div></div>");

        sb.append(filterHistoryRow(t("informationera.fe.filter_frequent"),
                ExplorerPreferences.frequentFilterValues(kind.key(), 8)));
        sb.append(filterHistoryRow(t("informationera.fe.filter_recent"),
                ExplorerPreferences.recentFilterValues(kind.key(), 8)));

        List<String> candidates = filterCandidates(kind);
        sb.append("<div class=\"fe-filter-dialog-section fe-filter-dialog-candidates\">");
        sb.append("<div class=\"fe-filter-dialog-head\">").append(escape(t("informationera.fe.filter_candidates")))
          .append("<span class=\"fe-filter-count\">").append(candidates.size()).append("</span></div>");
        sb.append("<div class=\"fe-filter-value-list\">");
        if (candidates.isEmpty()) {
            sb.append("<span class=\"fe-filter-none\">").append(escape(t("informationera.fe.filter_no_candidates")))
              .append("</span>");
        } else {
            for (String value : candidates) {
                sb.append("<button class=\"fe-filter-value\" type=\"button\" data-filter-value=\"")
                  .append(escape(value)).append("\" title=\"").append(escape(value)).append("\">")
                  .append(escape(ExplorerFilterCatalog.label(kind, value)))
                  .append(selected.contains(value) ? "<span class=\"fe-filter-check\">✓</span>" : "")
                  .append("</button>");
            }
        }
        sb.append("</div></div>");
        body.setInnerHTML(sb.toString());
        body.setScrollTop(scroll);

        Element search = document.getElementById("fe-filter-dialog-search");
        if (search != null) {
            search.setValue(typed);
            search.addEventListener("input", e -> renderFilterDialogSuggestions(search.getValue()));
            search.addEventListener("keyup", e -> {
                if (e instanceof KeyEvent ke && "Escape".equals(ke.key)) {
                    return;
                }
                renderFilterDialogSuggestions(search.getValue());
            });
            search.addEventListener("keydown", e -> {
                e.stopPropagation();
                if (!(e instanceof KeyEvent ke)) {
                    return;
                }
                if ("Enter".equals(ke.key)) {
                    List<String> values = resolveFilterValues(kind, search.getValue());
                    if (!values.isEmpty()) {
                        // 先清空再重绘，添加后可以继续输入下一个条件。
                        search.setValue("");
                        addFilterValues(kind, values);
                    }
                } else if ("Escape".equals(ke.key)) {
                    Element suggest = document.getElementById("fe-filter-dialog-suggest");
                    boolean showing = suggest != null && suggest.querySelector(".fe-filter-suggestion") != null;
                    if (showing) {
                        renderFilterDialogSuggestions("");
                    } else {
                        closeFilterDialog();
                    }
                }
            });
            if (!typed.isEmpty() && filterDialogOpen) {
                renderFilterDialogSuggestions(typed);
            }
            if (filterDialogOpen) {
                // 添加/移除条件会整体重绘弹窗，焦点必须回到搜索框，才能连续输入多个条件。
                search.focus();
            }
        }
        for (Element chip : body.querySelectorAll(".fe-filter-chip")) {
            chip.addEventListener("click", e -> {
                e.stopPropagation();
                String value = chip.getAttribute("data-chip-value");
                if (value != null) {
                    removeFilterValue(kind, value);
                }
            });
        }
        for (Element valueButton : body.querySelectorAll(".fe-filter-value")) {
            valueButton.addEventListener("click", e -> {
                e.stopPropagation();
                String value = valueButton.getAttribute("data-filter-value");
                if (value != null) {
                    addFilterValue(kind, value);
                }
            });
        }
        for (Element historyButton : body.querySelectorAll(".fe-filter-history-value")) {
            historyButton.addEventListener("click", e -> {
                e.stopPropagation();
                String value = historyButton.getAttribute("data-filter-history-value");
                if (value != null) {
                    addFilterValue(kind, value);
                }
            });
        }
    }

    private String filterHistoryRow(String title, List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-filter-dialog-section\">");
        sb.append("<div class=\"fe-filter-dialog-head\">").append(escape(title)).append("</div>");
        sb.append("<div class=\"fe-filter-history-row\">");
        if (values.isEmpty()) {
            sb.append("<span class=\"fe-filter-none\">").append(escape(t("informationera.fe.filter_none")))
              .append("</span>");
        } else {
            ExplorerFilterSelection.Kind kind = filterDialogKind;
            for (String value : values) {
                String label = ExplorerFilterCatalog.label(kind, value);
                sb.append("<button class=\"fe-filter-history-value\" type=\"button\" data-filter-history-value=\"")
                  .append(escape(value)).append("\" title=\"").append(escape(value)).append("\">")
                  .append(escape(label)).append("</button>");
            }
        }
        sb.append("</div></div>");
        return sb.toString();
    }

    private void renderFilterDialogSuggestions(String rawQuery) {
        Element box = document.getElementById("fe-filter-dialog-suggest");
        if (box == null) {
            return;
        }
        List<String> suggestions = filterSuggestions(filterDialogKind, rawQuery, 10);
        if (suggestions.isEmpty()) {
            box.setInnerHTML("");
            box.setInlineStyleProperty("display", "none");
            return;
        }
        box.setInlineStyleProperty("display", "flex");
        StringBuilder sb = new StringBuilder();
        for (String value : suggestions) {
            sb.append("<button class=\"fe-filter-suggestion\" type=\"button\" data-suggest-value=\"")
              .append(escape(value)).append("\" title=\"").append(escape(value)).append("\">")
              .append(escape(ExplorerFilterCatalog.label(filterDialogKind, value))).append("</button>");
        }
        box.setInnerHTML(sb.toString());
        for (Element button : box.querySelectorAll(".fe-filter-suggestion")) {
            button.addEventListener("mousedown", e -> e.preventDefault());
            button.addEventListener("click", e -> {
                e.stopPropagation();
                String value = button.getAttribute("data-suggest-value");
                if (value != null) {
                    Element search = document.getElementById("fe-filter-dialog-search");
                    if (search != null) {
                        search.setValue("");
                    }
                    addFilterValue(filterDialogKind, value);
                }
            });
        }
    }

    private void closeFilterDialog() {
        filterDialogOpen = false;
        filterDialogTemplates = false;
        if (document == null) {
            return;
        }
        Element dialog = document.getElementById("fe-filter-dialog");
        Element overlay = document.getElementById("fe-filter-dialog-overlay");
        if (dialog != null) {
            dialog.setInlineStyleProperty("display", "none");
        }
        if (overlay != null) {
            overlay.setInlineStyleProperty("display", "none");
        }
        Element search = document.getElementById("fe-filter-dialog-search");
        if (search != null) {
            search.setValue("");
        }
    }

    private void applyFilterVisibilityState() {
        Element button = document.getElementById("fe-filter");
        if (button != null) {
            boolean active = filterOpen;
            button.setAttribute("aria-pressed", active ? "true" : "false");
            if (active) {
                button.getClassList().add("active");
            } else {
                button.getClassList().remove("active");
            }
        }
    }

    private void updatePreview() {
        Element pane = document.getElementById("fe-preview-pane");
        if (pane == null) {
            return;
        }
        // 右上角两个放大镜按钮：加号放大、减号缩小（不再用「小 / 中 / 大」三档）。
        StringBuilder toolbar = new StringBuilder();
        toolbar.append("<div class=\"fe-preview-head\">");
        toolbar.append(zoomButton("out", previewSize <= PREVIEW_SIZE_MIN));
        toolbar.append(zoomButton("in", previewSize >= PREVIEW_SIZE_MAX));
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
            if (!entry.folder) {
                // 物品再多两段：物品自己的提示框，以及可展开折叠的 NBT 查看器。
                ItemStack stack = stackForEntry(entry);
                if (!stack.isEmpty()) {
                    sb.append(tooltipHtml(stack));
                    sb.append(nbtHtml(stack));
                }
            }
            pane.setInnerHTML(sb.toString());
        } else if (selected.size() > 1) {
            pane.setInnerHTML(toolbar.toString() + "<div class=\"fe-preview-empty\">" + t("informationera.fe.detail_selected", selected.size()) + "</div>");
        } else {
            pane.setInnerHTML(toolbar.toString() + "<div class=\"fe-preview-empty\">" + t("informationera.fe.detail_none") + "</div>");
        }
        bindPreviewZoomButtons();
        bindPreviewNbt();
        // 预览窗格里的图标是这里新建的元素，必须再喂一次驱动栈——否则那个 <item> 没有栈，
        // 什么也画不出来（内容区的图标是 renderContent 那边喂的，管不到这里）。
        driveItemIcons();
    }

    /**
     * 右上角的放大镜按钮。
     *
     * @param kind    {@code in} 放大 / {@code out} 缩小
     * @param atLimit 已经到上下限：按钮变暗，点了也不动
     */
    private String zoomButton(String kind, boolean atLimit) {
        boolean zoomIn = "in".equals(kind);
        return "<button class=\"fe-preview-zoom" + (atLimit ? " disabled" : "")
                + "\" data-zoom=\"" + kind + "\" aria-label=\""
                + (zoomIn ? t("informationera.fe.zoom_in") : t("informationera.fe.zoom_out")) + "\">"
                + zoomIcon(zoomIn) + "</button>";
    }

    /** 放大镜图标：镜筒 + 中间的加号或减号。 */
    private static String zoomIcon(boolean zoomIn) {
        return "<svg viewBox=\"0 0 24 24\">"
                + "<circle cx=\"10\" cy=\"10\" r=\"6\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"/>"
                + "<line x1=\"14.5\" y1=\"14.5\" x2=\"20\" y2=\"20\" stroke=\"currentColor\" stroke-width=\"2\"/>"
                + "<path d=\"" + (zoomIn ? "M10 7v6M7 10h6" : "M7 10h6")
                + "\" stroke=\"currentColor\" stroke-width=\"2\"/>"
                + "</svg>";
    }

    /** 当前选中条目对应的真实物品栈；文件夹或取不到时返回空。 */
    private ItemStack stackForEntry(VirtualFileSystem.Entry entry) {
        if (fs == null || entry == null || entry.folder || entry.itemKey == null) {
            return ItemStack.EMPTY;
        }
        return fs.iconStackOf(entry, DiskClient.registryProvider());
    }

    /**
     * 预览里的物品提示框。
     *
     * <p>行用原版的 {@code getTooltipLines} 取（附魔、耐久、属性、模组自己加的 tooltip 都在里面），
     * 每行再用 {@code toFlatList} 拆成「样式已解析」的片段——这样颜色能跟游戏里对上，
     * 而不是整行一个色。
     */
    private String tooltipHtml(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        List<Component> lines = stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.of(mc.level),
                mc.player, TooltipFlag.Default.NORMAL);
        StringBuilder sb = new StringBuilder("<div class=\"fe-preview-tips\">");
        for (Component line : lines) {
            sb.append("<div class=\"fe-preview-tip\">");
            for (Component part : line.toFlatList()) {
                Style style = part.getStyle();
                TextColor color = style.getColor();
                sb.append("<span");
                if (color != null || style.isItalic() || style.isBold()) {
                    sb.append(" style=\"");
                    if (color != null) {
                        sb.append("color:#")
                          .append(String.format(Locale.ROOT, "%06X", color.getValue() & 0xFFFFFF))
                          .append(';');
                    }
                    if (style.isItalic()) {
                        sb.append("font-style:italic;");
                    }
                    if (style.isBold()) {
                        sb.append("font-weight:bold;");
                    }
                    sb.append('"');
                }
                sb.append('>').append(escape(part.getString())).append("</span>");
            }
            sb.append("</div>");
        }
        return sb.append("</div>").toString();
    }

    /**
     * 可展开折叠的 NBT 查看器：物品组件的 SNBT 原样列出来，没有组件就明说。
     *
     * <p>折叠状态记在 {@link #nbtExpanded} 上，所以换选中项再回来仍然是展开的。
     */
    private String nbtHtml(ItemStack stack) {
        String snbt = ItemStacks.nbtOf(stack, DiskClient.registryProvider());
        boolean empty = snbt == null || snbt.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-nbt\">");
        sb.append("<div class=\"fe-nbt-head\" data-action=\"nbt\">")
          .append("<span class=\"fe-nbt-chevron\">").append(nbtExpanded ? "▾" : "▸").append("</span>")
          .append("<span>").append(t("informationera.fe.nbt")).append("</span>")
          .append("</div>");
        if (nbtExpanded) {
            sb.append("<div class=\"fe-nbt-body\">")
              .append(escape(empty ? t("informationera.fe.nbt_empty") : snbt))
              .append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** NBT 查看器的展开 / 折叠。 */
    private void bindPreviewNbt() {
        for (Element head : document.querySelectorAll(".fe-nbt-head")) {
            head.addEventListener("click", e -> {
                nbtExpanded = !nbtExpanded;
                updatePreview();
            });
        }
    }

    /** 放大镜按钮：每次按步长加减，并夹在上下限之间。 */
    private void bindPreviewZoomButtons() {
        for (Element btn : document.querySelectorAll(".fe-preview-zoom")) {
            String kind = btn.getAttribute("data-zoom");
            btn.addEventListener("click", e -> {
                if ("in".equals(kind)) {
                    previewSize = Math.min(PREVIEW_SIZE_MAX, previewSize + PREVIEW_SIZE_STEP);
                } else if ("out".equals(kind)) {
                    previewSize = Math.max(PREVIEW_SIZE_MIN, previewSize - PREVIEW_SIZE_STEP);
                }
                updatePreview();
            });
        }
    }

    // ---------- 导航 ----------

    private void navigateTo(String key) {
        if (fs.folder(key) == null) {
            setStatus(t("informationera.fe.status_path_not_found", key));
            return;
        }
        ExplorerSession.globalBackStack.push(currentKey);
        ExplorerSession.globalForwardStack.clear();
        currentKey = key;
        afterNavigate();
    }

    private void navigateBack() {
        if (ExplorerSession.globalBackStack.isEmpty()) {
            return;
        }
        ExplorerSession.globalForwardStack.push(currentKey);
        currentKey = ExplorerSession.globalBackStack.pop();
        afterNavigate();
    }

    private void navigateForward() {
        if (ExplorerSession.globalForwardStack.isEmpty()) {
            return;
        }
        ExplorerSession.globalBackStack.push(currentKey);
        currentKey = ExplorerSession.globalForwardStack.pop();
        afterNavigate();
    }

    private void navigateUp() {
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        if (folder != null && folder.parentKey != null) {
            navigateTo(folder.parentKey);
        }
    }

    private void afterNavigate() {
        closeColumnManager();
        selected.clear();
        lastClickedIndex = -1;
        checkedSet.clear();
        lastCheckedIndex = -1;
        clearAppliedFilterTemplate();
        searchQuery = "";
        Element search = document.getElementById("fe-search");
        if (search != null) {
            search.setValue("");
        }
        updateFilterClearButton();
        // 记录本窗口导航历史（去重，最近在前）。
        navHistory.remove(currentKey);
        navHistory.add(0, currentKey);
        if (navHistory.size() > 20) {
            navHistory.remove(navHistory.size() - 1);
        }
        // 记一笔访问：快速访问的「常用文件夹」按使用次数和最后访问时间排序。
        ExplorerPreferences.record(currentKey);
        // 每个文件夹有自己的布局/排序/分组/列设置；进入时先恢复它。
        applyViewState(ExplorerPreferences.viewState(currentKey), false);
        updateSearchPlaceholder();
        renderContent();
        // 导航树必须整体重绘：侧边栏的「常用文件夹」刚刚多了这一条，
        // 只更新选中态不会把新条目画出来。
        renderNavigation();
        // 重绘后节点是新的，选中态要再刷一次。
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
        // 导航树分成三段：快速访问（固定项 + 常用文件夹）、主目录树、回收站。
        // 段与段之间插分割线，避免不同用途的路径挤在一起。
        boolean sectionWritten = false;

        // 第一段：快速访问。固定项与「常用文件夹」是两组不同的东西，中间要有分割线。
        // 「常用文件夹」按 Windows 跳转列表的机制排：使用次数按闲置时间衰减后降序；
        // 条数上限已经在 ExplorerPreferences 里裁好，这里不再自己截断。
        List<String> pinnedKeys = ExplorerPreferences.pinned();
        List<String> frequentKeys = ExplorerPreferences.frequent();
        if (!pinnedKeys.isEmpty() || !frequentKeys.isEmpty()) {
            sb.append("<div class=\"fe-nav-title\">").append(t("informationera.fe.quick_access")).append("</div>");
            for (String key : pinnedKeys) {
                renderPinnedNode(sb, key);
            }
            if (!frequentKeys.isEmpty()) {
                sb.append("<div class=\"fe-nav-sep\"></div>");
                sb.append("<div class=\"fe-nav-title\">")
                  .append(t("informationera.fe.frequent")).append("</div>");
                for (String key : frequentKeys) {
                    renderPinnedNode(sb, key);
                }
            }
            sectionWritten = true;
        }

        // 第二段：主目录。最上层是玩家名，玩家名下面是磁盘，磁盘里面才是磁盘内容——
        // 逐层点展开箭头，不要把子文件夹一股脑列出来。
        VirtualFileSystem.Folder host = fs.folder(DiskFolder.HOST_ID);
        if (host != null) {
            if (sectionWritten) {
                sb.append("<div class=\"fe-nav-sep\"></div>");
            }
            sb.append("<div class=\"fe-nav-title\">").append(t("informationera.fe.main_directory")).append("</div>");
            renderSidebarNode(sb, DiskFolder.HOST_ID, 0);
            sectionWritten = true;
        }

        // 第三段：回收站。单独成段，不与普通目录混在一起。
        VirtualFileSystem.Folder recycle = fs.folder(DiskFolder.RECYCLE_ID);
        if (recycle != null) {
            if (sectionWritten) {
                sb.append("<div class=\"fe-nav-sep\"></div>");
            }
            renderSidebarNode(sb, DiskFolder.RECYCLE_ID, 0);
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
        sb.append("<div class=\"fe-nav-item\" data-path=\"").append(escape(key)).append("\">");
        sb.append("<span class=\"fe-chevron fe-chevron-empty\"></span>");
        // 固定项也可能就是磁盘本身，同样用储存卡图标。
        sb.append(DiskFolder.ROOT_ID.equals(key) ? diskIcon(16) : folderIcon(16));
        sb.append("<span class=\"fe-nav-label\">").append(escape(folder.name)).append("</span>");
        sb.append("</div>");
    }

    /** 导航树每一层的缩进量（px）。层级靠它表达，箭头只负责展开/收起。 */
    private static final int NAV_INDENT = 14;
    /** 导航项的基础左内边距，与 CSS 里 .fe-nav-item 的 padding 保持一致。 */
    private static final int NAV_INDENT_BASE = 8;

    /**
     * 渲染一个导航项。
     *
     * <p>层级用**缩进**表达（每层 {@link #NAV_INDENT} 像素）：玩家名下面是磁盘，磁盘下面是
     * 分类文件夹，一眼能看出谁在谁里面。缩进只加在这一处，名称照旧走省略号，所以窄导航栏
     * 不会因此冒出横向滚动条。
     */
    private void renderSidebarNode(StringBuilder sb, String key, int depth) {
        VirtualFileSystem.Folder folder = fs.folder(key);
        if (folder == null) {
            return;
        }
        boolean expandable = fs.hasChildFolders(key);
        boolean expanded = expandable && expandedSidebar.contains(key);
        sb.append("<div class=\"fe-nav-item\" data-path=\"").append(escape(key)).append("\"");
        if (depth > 0) {
            sb.append(" style=\"padding-left:").append(NAV_INDENT_BASE + NAV_INDENT * depth).append("px\"");
        }
        sb.append(">");
        if (expandable) {
            sb.append("<span class=\"fe-chevron\" data-toggle=\"").append(escape(key)).append("\">")
              .append(expanded ? chevronDown() : chevronRight()).append("</span>");
        } else {
            sb.append("<span class=\"fe-chevron fe-chevron-empty\"></span>");
        }
        // 磁盘用储存卡那张贴图：它本来就是一张卡，画成文件夹认不出来。
        sb.append(DiskFolder.ROOT_ID.equals(key) ? diskIcon(16) : folderIcon(16));
        sb.append("<span class=\"fe-nav-label\">").append(escape(folder.name)).append("</span>");
        sb.append("</div>");

        if (expanded) {
            for (VirtualFileSystem.Entry child : fs.childFolders(key)) {
                if (DiskFolder.RECYCLE_ID.equals(child.childKey)) {
                    continue;
                }
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
        // 状态直接写进客户端配置，重启后仍然保留。
        ExplorerPreferences.togglePin(key);
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
        // 条目马上要被整体替换，旧的 mouseout 不会再触发，这里必须主动收起提示，
        // 否则鼠标划过时留在屏幕上的提示框会永久卡住。
        hideTooltip();
        // 视图模式类：控制图标网格的列宽与图标尺寸，切换布局而不是单纯缩放。
        content.getClassList().remove("fe-view-slots", "fe-view-small", "fe-view-medium", "fe-view-large", "fe-view-xlarge");
        if ("slots".equals(viewMode) || "small".equals(viewMode) || "medium".equals(viewMode) || "large".equals(viewMode) || "xlarge".equals(viewMode)) {
            content.getClassList().add("fe-view-" + viewMode);
        }
        List<VirtualFileSystem.Entry> entries = topLevelEntries();
        renderedEntryList.clear();
        renderedEntryList.addAll(entries);
        invalidateFilterCandidates();
        lastClickedIndex = -1;

        if (entries.isEmpty()) {
            boolean filtering = filterOpen && !filterSelection.isEmpty();
            if (!searchQuery.trim().isEmpty()) {
                content.setInnerHTML("<div class=\"fe-empty\">" + t("informationera.fe.no_match")
                        + "<button class=\"fe-empty-search-btn\" id=\"fe-subfolder-search\">" + t("informationera.fe.search_subfolders") + "</button></div>");
                Element btn = content.querySelector("#fe-subfolder-search");
                if (btn != null) {
                    btn.addEventListener("click", e -> searchSubfolders());
                }
            } else if (filtering) {
                content.setInnerHTML("<div class=\"fe-empty\">" + t("informationera.fe.no_filter_match")
                        + "<button class=\"fe-empty-search-btn\" id=\"fe-filter-empty-clear\">"
                        + t("informationera.fe.filter_clear") + "</button></div>");
                Element btn = content.querySelector("#fe-filter-empty-clear");
                if (btn != null) {
                    btn.addEventListener("click", e -> resetFilter());
                }
            } else {
                content.setInnerHTML("<div class=\"fe-empty\">" + t("informationera.fe.empty_folder") + "</div>");
            }
        } else if (isLinearView() && entries.size() > 200) {
            renderContentVirtual(entries);
        } else if (!isLinearView() && entries.size() > 200) {
            renderContentVirtualGrid(entries);
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
        driveItemIcons();
        bindTableHeaders();
        updateSelectionVisuals();
        updateStatusBar();
        applyCompact();
        updateStatusViewButtons();
    }

    private boolean isLinearView() {
        return "details".equals(viewMode) || "list".equals(viewMode) || "content".equals(viewMode);
    }

    /** 线性视图窗口化渲染：支持分组，只渲染可视窗口内的行，上下用 spacer 占位。 */
    private void renderContentVirtual(List<VirtualFileSystem.Entry> entries) {
        Element content = document.getElementById("fe-content");
        if (content == null) {
            return;
        }
        // 构建虚拟行：分组头行 + 条目行。折叠组跳过其条目行但不破坏总高度。
        record VRow(String label, VirtualFileSystem.Entry entry, int entryIndex, double height) {
        }
        List<VRow> rows = new ArrayList<>();
        boolean grouped = groupBy != null && !groupBy.isEmpty();
        if (grouped) {
            String currentGroup = null;
            boolean collapsed = false;
            for (int i = 0; i < entries.size(); i++) {
                VirtualFileSystem.Entry entry = entries.get(i);
                String label = groupLabel(entry);
                if (!label.equals(currentGroup)) {
                    currentGroup = label;
                    collapsed = collapsedGroups.contains(label);
                    rows.add(new VRow(label, null, -1, GROUP_HEADER_HEIGHT));
                }
                if (!collapsed) {
                    rows.add(new VRow(null, entry, i, ROW_HEIGHT));
                }
            }
        } else {
            for (int i = 0; i < entries.size(); i++) {
                rows.add(new VRow(null, entries.get(i), i, ROW_HEIGHT));
            }
        }

        // 计算虚拟行累计高度，用于窗口化渲染。
        double totalHeight = 0;
        double[] rowTop = new double[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            rowTop[r] = totalHeight;
            totalHeight += rows.get(r).height();
        }

        double scrollTop = content.getScrollTop();
        Element.DOMRect rect = content.getBoundingClientRect();
        double viewH = rect != null && rect.height > 0 ? rect.height : 600;
        int start = 0;
        int end = rows.size();
        // 找到起始虚拟行（含缓冲）。
        for (int r = 0; r < rows.size(); r++) {
            if (rowTop[r] + rows.get(r).height() >= scrollTop - VIRTUAL_BUFFER * ROW_HEIGHT) {
                start = r;
                break;
            }
        }
        // 找到结束虚拟行。
        for (int r = start; r < rows.size(); r++) {
            if (rowTop[r] >= scrollTop + viewH + VIRTUAL_BUFFER * ROW_HEIGHT) {
                end = r;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        boolean details = "details".equals(viewMode) || "content".equals(viewMode);
        if (details) {
            sb.append("<div class=\"fe-table fe-vtable\" style=\"").append(tableWidthStyle()).append("\">");
            sb.append("<div class=\"fe-vthead\" style=\"").append(gridTemplateStyle()).append("\">");
            appendHeaderCells(sb);
            sb.append("</div>");
        }
        sb.append("<div class=\"fe-vbody\" style=\"height:").append(Math.round(totalHeight))
          .append("px;position:relative;\">");
        double offset = rowTop[start];
        if (offset > 0) {
            sb.append("<div style=\"height:").append(Math.round(offset)).append("px;\"></div>");
        }
        for (int r = start; r < end; r++) {
            VRow row = rows.get(r);
            if (row.entry() == null) {
                // 分组头行。
                sb.append(groupHeaderDiv(row.label(), collapsedGroups.contains(row.label())));
            } else {
                VirtualFileSystem.Entry entry = row.entry();
                int i = row.entryIndex();
                if (details) {
                    sb.append("<div class=\"fe-item fe-vrow\" data-id=\"e").append(i).append("\"")
                      .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
                      .append(folderPathAttr(entry))
                      .append(" style=\"").append(gridTemplateStyle()).append("\">");
                    appendRowCells(sb, entry, i, true);
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
        }
        sb.append("</div>");
        if (details) {
            sb.append("</div>");
        }
        content.setInnerHTML(sb.toString());
    }

    /** 虚拟滚动模式下的单元格（div 形式，对应 cellFor）。 */
    private String cellDiv(ExplorerColumn column, VirtualFileSystem.Entry entry, int index,
                           ExplorerColumnContext context) {
        String id = escape(column.id());
        if (ExplorerViewState.NAME_COLUMN_ID.equals(column.id())) {
            return "<div class=\"fe-cell\" data-col=\"" + id + "\">"
                    + nameCellInner(entry, index, context) + "</div>";
        }
        return "<div class=\"fe-vcell\" data-col=\"" + id + "\">"
                + escape(ExplorerColumnService.value(column, context)) + "</div>";
    }

    /** 图标/平铺视图的窗口化渲染：按列数估算总行数，只渲染可视行 ± 缓冲。 */
    private void renderContentVirtualGrid(List<VirtualFileSystem.Entry> entries) {
        Element content = document.getElementById("fe-content");
        if (content == null) {
            return;
        }
        boolean tiles = "tiles".equals(viewMode);
        double rowHeight = tiles ? tileRowHeight() : gridRowHeight();
        int cols = Math.max(1, gridColumns(tiles));

        // 分组头行高度按一个行高近似，避免滚动条大幅跳动。
        record VRow(String label, VirtualFileSystem.Entry entry, int entryIndex) {
        }
        List<VRow> rows = new ArrayList<>();
        boolean grouped = groupBy != null && !groupBy.isEmpty();
        if (grouped) {
            String currentGroup = null;
            boolean collapsed = false;
            int sinceGroupStart = 0;
            for (int i = 0; i < entries.size(); i++) {
                VirtualFileSystem.Entry entry = entries.get(i);
                String label = groupLabel(entry);
                if (!label.equals(currentGroup)) {
                    // 上一组末尾补满行。
                    currentGroup = label;
                    collapsed = collapsedGroups.contains(label);
                    rows.add(new VRow(label, null, -1));
                    sinceGroupStart = 0;
                }
                if (!collapsed) {
                    rows.add(new VRow(null, entry, i));
                    sinceGroupStart++;
                }
            }
        } else {
            for (int i = 0; i < entries.size(); i++) {
                rows.add(new VRow(null, entries.get(i), i));
            }
        }

        int totalRows = (int) Math.ceil(rows.size() / (double) cols);
        double totalHeight = totalRows * rowHeight;
        double scrollTop = content.getScrollTop();
        Element.DOMRect rect = content.getBoundingClientRect();
        double viewH = rect != null && rect.height > 0 ? rect.height : 600;

        int startRow = Math.max(0, (int) Math.floor(scrollTop / rowHeight) - VIRTUAL_BUFFER);
        int endRow = Math.min(totalRows, (int) Math.ceil((scrollTop + viewH) / rowHeight) + VIRTUAL_BUFFER);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"").append(tiles ? "fe-tiles fe-vgrid" : "fe-grid fe-vgrid")
          .append("\" style=\"height:").append(Math.round(totalHeight)).append("px;position:relative;display:block;\">");
        int startIdx = startRow * cols;
        int endIdx = Math.min(rows.size(), endRow * cols);
        // 顶部 spacer。
        double topOffset = startRow * rowHeight;
        if (topOffset > 0) {
            sb.append("<div style=\"height:").append(Math.round(topOffset)).append("px;\"></div>");
        }
        // 可视范围内的条目。
        sb.append("<div class=\"fe-vgrid-row\" style=\"display:grid;grid-template-columns:repeat(")
          .append(cols).append("slots".equals(viewMode) ? ",44px);gap:0;\">" : ",1fr);gap:4px;\">");
        for (int i = startIdx; i < endIdx; i++) {
            VRow row = rows.get(i);
            if (row.entry() == null) {
                sb.append(groupHeaderDiv(row.label(), collapsedGroups.contains(row.label())));
            } else if (tiles) {
                sb.append(tileItemHtml(row.entry()));
            } else {
                sb.append(gridItemHtml(row.entry()));
            }
        }
        sb.append("</div>");
        sb.append("</div>");
        content.setInnerHTML(sb.toString());
    }

    private int gridColumns(boolean tiles) {
        Element content = document.getElementById("fe-content");
        if (content == null) {
            return tiles ? 1 : 4;
        }
        Element.DOMRect rect = content.getBoundingClientRect();
        double width = rect != null && rect.width > 0 ? rect.width : 980;
        if ("slots".equals(viewMode)) {
            return Math.max(1, (int) Math.floor(content.getClientWidth() / 44.0));
        }
        if (tiles) {
            // 与 CSS 里 .fe-view-* .fe-tiles 的 minmax 保持一致，否则虚拟滚动的列数会算错。
            double tileColWidth = switch (viewMode) {
                case "small" -> 180;
                case "large" -> 300;
                case "xlarge" -> 360;
                default -> 240;
            };
            return Math.max(1, (int) Math.floor(width / tileColWidth));
        }
        // 图标视图列宽按 viewMode 近似，与 CSS minmax 对齐。
        double colWidth = switch (viewMode) {
            case "small" -> 72;
            case "medium" -> 100;
            case "large" -> 140;
            case "xlarge" -> 180;
            default -> 96;
        };
        return Math.max(1, (int) Math.floor(width / colWidth));
    }

    private double gridRowHeight() {
        return switch (viewMode) {
            case "slots" -> 44;
            case "small" -> 84;
            case "medium" -> 112;
            case "large" -> 148;
            case "xlarge" -> 188;
            default -> 104;
        };
    }

    /**
     * 平铺视图的行高。
     *
     * <p>必须跟 {@link #tileIconSize()} 一起变：图标变大而这里不变的话，条目超过 200 条
     * 走虚拟滚动时行高估算会偏小，滚动条位置与内容就会错位。
     */
    private double tileRowHeight() {
        return tileIconSize() + 48.0;
    }

    private void onContentScroll() {
        Element content = document.getElementById("fe-content");
        if (content == null) {
            return;
        }
        double newTop = content.getScrollTop();
        if (Math.abs(newTop - scrollTop) > 1.0) {
            scrollTop = newTop;
            // 用当前渲染结果，不再重新扫描子树/重算筛选；否则滚动时既卡又可能和
            // 筛选条件、搜索词产生不一致的列表。
            List<VirtualFileSystem.Entry> entries = renderedEntryList;
            if (entries.size() > 200) {
                if (isLinearView()) {
                    renderContentVirtual(entries);
                } else {
                    renderContentVirtualGrid(entries);
                }
                bindItemListeners();
                driveItemIcons();
                bindTableHeaders();
                updateSelectionVisuals();
            }
        }
    }

    /** 递归收集当前文件夹子树内匹配 query 的文件（含所在文件夹 key）。 */
    private void collectRecursiveMatches(String folderKey, String query, int depth,
                                         List<VirtualFileSystem.Entry> files, List<String> fileFolders,
                                         Set<String> visited) {
        if (depth > MAX_FILTER_DEPTH || folderKey == null || !visited.add(folderKey)) {
            return;
        }
        VirtualFileSystem.Folder folder = fs.folder(folderKey);
        if (folder == null) {
            return;
        }
        for (VirtualFileSystem.Entry entry : folder.entries) {
            if (entry.folder && entry.childKey != null) {
                if (DiskFolder.RECYCLE_ID.equals(entry.childKey) && !DiskFolder.RECYCLE_ID.equals(currentKey)) {
                    continue;
                }
                collectRecursiveMatches(entry.childKey, query, depth + 1, files, fileFolders, visited);
            } else if (ExplorerSearch.matches(entry.name, query)) {
                files.add(entry);
                fileFolders.add(folderKey);
            }
        }
    }

    /** 在子文件夹中搜索：若有匹配则导航到第一个匹配项所在文件夹并选中它。 */
    private void searchSubfolders() {
        String query = searchQuery == null ? "" : searchQuery.trim();
        if (query.isEmpty()) {
            return;
        }
        List<VirtualFileSystem.Entry> files = new ArrayList<>();
        List<String> fileFolders = new ArrayList<>();
        collectRecursiveMatches(currentKey, query, 0, files, fileFolders, new HashSet<>());
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
        // 筛选关闭时，隐藏的条件不参与显示：includeSubfolders 也不展开。
        boolean expand = filterOpen && filterSelection.includeSubfolders;
        List<VirtualFileSystem.Entry> entries = filterScopeEntries(expand);
        String query = searchQuery == null ? "" : searchQuery.trim();
        if (!query.isEmpty()) {
            entries.removeIf(entry -> !ExplorerSearch.matches(entry.name, query));
        }
        if (filterOpen && !filterSelection.isEmpty()) {
            // 有任意条件时只保留满足条件的物品；文件夹没有标签/模组/创造标签页/关键词，自然被排除。
            entries.removeIf(entry -> entry.folder || !matchesFilter(entry));
        }
        // 有分组时先确定组顺序，再让每组复用当前列排序（包括第三方列），
        // 不能再用名称排序覆盖表头的排序选择。
        if (groupBy != null && !groupBy.isEmpty()) {
            java.util.Map<String, List<VirtualFileSystem.Entry>> groups = new java.util.LinkedHashMap<>();
            for (VirtualFileSystem.Entry entry : entries) {
                groups.computeIfAbsent(groupLabel(entry), key -> new ArrayList<>()).add(entry);
            }
            List<String> labels = new ArrayList<>(groups.keySet());
            labels.sort(String.CASE_INSENSITIVE_ORDER);
            entries.clear();
            for (String label : labels) {
                List<VirtualFileSystem.Entry> group = groups.get(label);
                sortEntries(group);
                entries.addAll(group);
            }
        } else {
            sortEntries(entries);
        }
        if (filterOpen && filterSelection.dedupe) {
            // 仅视觉去重：在搜索/筛选/排序之后按真实 ItemKey 保留一个代表。
            ExplorerItemDedupe.retainFirstByItemKey(entries,
                    entry -> entry.folder || entry.itemKey == null,
                    entry -> entry.itemKey);
        }
        return entries;
    }

    private boolean matchesFilter(VirtualFileSystem.Entry entry) {
        ExplorerFilterCatalog.Facts facts = ExplorerFilterCatalog.facts(entry);
        if (!filterSelection.matches(facts.modId, facts.tags, facts.tabs)) {
            return false;
        }
        java.util.Set<String> keywords = filterSelection.values(ExplorerFilterSelection.Kind.KEYWORD);
        if (keywords.isEmpty()) {
            return true;
        }
        String name = ExplorerKeywordIndex.localizedName(entry.itemId);
        return ExplorerKeywordIndex.matchesAny(keywords, name);
    }

    /**
     * 当前目录的范围：普通模式下只有当前文件夹的直接子项；“展开所有子文件夹”时收集
     * 当前目录及整个子树的文件（物品）。候选全集和实际显示都从这里取，保证已选条件
     * 不会反过来缩小候选范围。
     */
    private List<VirtualFileSystem.Entry> filterScopeEntries(boolean includeSubfolders) {
        List<VirtualFileSystem.Entry> entries = new ArrayList<>();
        VirtualFileSystem.Folder folder = fs.folder(currentKey);
        if (folder == null) {
            return entries;
        }
        if (!includeSubfolders) {
            entries.addAll(folder.entries);
            return entries;
        }
        collectFilterSubtree(currentKey, entries, new HashSet<>(), 0);
        return entries;
    }

    /**
     * 递归收集子树文件。
     *
     * <p>visited + 深度上限保证异常/循环的父链不会把界面拖死；回收站子树默认跳过，
     * 只有当前目录本身就是回收站时才遍历它——否则回收站里的物品会从普通目录的筛选里冒出来。
     */
    private void collectFilterSubtree(String folderKey, List<VirtualFileSystem.Entry> out,
                                      Set<String> visited, int depth) {
        if (depth > MAX_FILTER_DEPTH || folderKey == null || !visited.add(folderKey)) {
            return;
        }
        VirtualFileSystem.Folder folder = fs.folder(folderKey);
        if (folder == null) {
            return;
        }
        boolean inRecycle = DiskFolder.RECYCLE_ID.equals(currentKey);
        for (VirtualFileSystem.Entry entry : folder.entries) {
            if (entry.folder) {
                String child = entry.childKey != null ? entry.childKey : entry.id;
                if (DiskFolder.RECYCLE_ID.equals(child) && !inRecycle) {
                    continue;
                }
                collectFilterSubtree(child, out, visited, depth + 1);
            } else {
                out.add(entry);
            }
        }
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
            case "author" -> entry.author == null ? t("informationera.fe.unknown") : entry.author;
            case "size" -> entry.folder ? t("informationera.fe.folder") : sizeGroup(entry.size);
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
            return t("informationera.fe.unknown");
        }
        int firstSlash = modified.indexOf('/');
        int secondSlash = modified.indexOf('/', firstSlash + 1);
        if (firstSlash < 0 || secondSlash < 0) {
            return modified;
        }
        try {
            int y = Integer.parseInt(modified.substring(0, firstSlash).trim());
            int m = Integer.parseInt(modified.substring(firstSlash + 1, secondSlash).trim());
            return t("informationera.fe.year_month", y, m);
        } catch (NumberFormatException e) {
            return modified;
        }
    }

    private static String sizeGroup(long size) {
        if (size < 1024L) {
            return t("informationera.fe.size_small");
        }
        if (size < 1024L * 1024L) {
            return t("informationera.fe.size_medium");
        }
        if (size < 1024L * 1024L * 1024L) {
            return t("informationera.fe.size_large");
        }
        return t("informationera.fe.size_huge");
    }

    private void sortEntries(List<VirtualFileSystem.Entry> entries) {
        ExplorerColumn sortColumn = ExplorerColumnService.column(sortField);
        if (sortColumn == null) {
            sortColumn = ExplorerColumnService.column(ExplorerViewState.BUILT_IN_SORT_FIELD);
        }
        java.util.Map<VirtualFileSystem.Entry, ExplorerColumnContext> contexts = new java.util.IdentityHashMap<>();
        for (VirtualFileSystem.Entry entry : entries) {
            contexts.put(entry, contextFor(entry));
        }
        final ExplorerColumn column = sortColumn;
        entries.sort((a, b) -> {
            int cmp;
            if (foldersFirst && a.folder != b.folder) {
                cmp = a.folder ? -1 : 1;
            } else if (a.folder && b.folder) {
                cmp = VirtualFileSystem.naturalCompare(a.name, b.name);
            } else {
                cmp = ExplorerColumnService.compare(column, contexts.get(a), contexts.get(b));
            }
            if (cmp == 0) {
                cmp = VirtualFileSystem.naturalCompare(a.name, b.name);
            }
            return sortAsc ? cmp : -cmp;
        });
    }

    /** 条目的公开列上下文；物品栈延迟构造。 */
    private ExplorerColumnContext contextFor(VirtualFileSystem.Entry entry) {
        return ExplorerColumnService.context(entry, displayName(entry), () -> stackForEntry(entry));
    }

    private String buildTable(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"table fe-table\" style=\"").append(tableWidthStyle()).append("\">");
        sb.append("<thead><tr style=\"").append(gridTemplateStyle()).append("\">");
        appendHeaderCells(sb);
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

    /** 分组头（表格行形式，供 details / content 视图使用）。 */
    private String groupHeaderHtml(String label, boolean collapsed) {
        int visibleColumns = visibleColumns().size();
        // 表格行必须用 colspan 跨过所有可见列：td 改成 table-cell 后不再像原来的
        // flex 单元格那样撑满整行，行高也才由这个单元格决定，避免与第一条目重叠。
        return "<tr class=\"fe-group-row\" data-group=\"" + escape(label)
                + "\"><td class=\"fe-group-header\" colspan=\"" + Math.max(1, visibleColumns) + "\">"
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

    private String headerCell(ExplorerColumn column) {
        String id = column.id();
        boolean active = sortField.equals(id);
        return "<th class=\"sortable" + (active ? " sort-active" : "") + "\" data-sort=\"" + escape(id)
                + "\" data-col=\"" + escape(id) + "\">"
                + escape(ExplorerColumnService.label(column).getString())
                + "<span class=\"fe-col-resizer\" data-col=\"" + escape(id) + "\"></span></th>";
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
                    showHeaderContextMenu(th.getAttribute("data-col"), me.clientX, me.clientY);
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
            String columnId = handle.getAttribute("data-col");
            handle.addEventListener("mousedown", e -> {
                if (e instanceof MouseEvent me && me.button == 0) {
                    e.stopPropagation();
                    beginColumnResize(columnId, me.clientX);
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
        appendRowCells(sb, entry, index, false);
        sb.append("</tr>");
    }

    private String cellFor(ExplorerColumn column, VirtualFileSystem.Entry entry, int index,
                           ExplorerColumnContext context) {
        String id = escape(column.id());
        if (ExplorerViewState.NAME_COLUMN_ID.equals(column.id())) {
            return "<td data-col=\"" + id + "\"><div class=\"fe-cell\">"
                    + nameCellInner(entry, index, context) + "</div></td>";
        }
        return "<td data-col=\"" + id + "\">"
                + escape(ExplorerColumnService.value(column, context)) + "</td>";
    }

    /** 名称列共享片段：普通 td 与虚拟 div 都走这里。 */
    private String nameCellInner(VirtualFileSystem.Entry entry, int index, ExplorerColumnContext context) {
        StringBuilder sb = new StringBuilder();
        if (checkboxes) {
            sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
        }
        sb.append(iconFor(entry, 16))
          .append("<span class=\"fe-name-text\" data-name=\"1\">").append(escape(context.name())).append("</span>");
        return sb.toString();
    }

    /** 当前注册列按本窗口列序排列；未出现在列序里的新列追加在末尾。 */
    private List<ExplorerColumn> orderedColumns() {
        List<ExplorerColumn> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : columnOrder) {
            ExplorerColumn column = ExplorerColumnService.column(id);
            if (column != null && seen.add(id)) {
                result.add(column);
            }
        }
        for (ExplorerColumn column : ExplorerColumnService.columns()) {
            if (seen.add(column.id())) {
                result.add(column);
            }
        }
        return result;
    }

    private List<ExplorerColumn> visibleColumns() {
        List<ExplorerColumn> result = new ArrayList<>();
        for (ExplorerColumn column : orderedColumns()) {
            if (columnVisible(column.id())) {
                result.add(column);
            }
        }
        return result;
    }

    private double columnWidth(String id) {
        ExplorerViewState.ColumnSetting setting = columnSettings.get(id);
        if (setting != null) {
            return setting.width;
        }
        ExplorerColumn column = ExplorerColumnService.column(id);
        return column == null ? 120.0 : column.defaultWidth();
    }

    private boolean columnVisible(String id) {
        ExplorerViewState.ColumnSetting setting = columnSettings.get(id);
        if (setting != null) {
            return setting.visible;
        }
        ExplorerColumn column = ExplorerColumnService.column(id);
        return column != null && column.defaultVisible();
    }

    private void appendHeaderCells(StringBuilder sb) {
        for (ExplorerColumn column : visibleColumns()) {
            sb.append(headerCell(column));
        }
    }

    private void appendRowCells(StringBuilder sb, VirtualFileSystem.Entry entry, int index, boolean virtual) {
        ExplorerColumnContext context = contextFor(entry);
        for (ExplorerColumn column : visibleColumns()) {
            sb.append(virtual
                    ? cellDiv(column, entry, index, context)
                    : cellFor(column, entry, index, context));
        }
    }

    private String gridTemplateStyle() {
        StringBuilder sb = new StringBuilder("grid-template-columns:");
        boolean first = true;
        for (ExplorerColumn column : visibleColumns()) {
            if (!first) {
                sb.append(' ');
            }
            first = false;
            sb.append(Math.round(columnWidth(column.id()))).append("px");
        }
        sb.append(';');
        return sb.toString();
    }

    private String tableWidthStyle() {
        double sum = 0;
        for (ExplorerColumn column : visibleColumns()) {
            sum += columnWidth(column.id());
        }
        return "width:" + Math.round(sum) + "px;";
    }

    private void toggleColumn(String id) {
        if (ExplorerViewState.NAME_COLUMN_ID.equals(id)) {
            setStatus(t("informationera.fe.column_manager_name_required"));
            return;
        }
        boolean currently = columnVisible(id);
        changeView(state -> {
            ExplorerColumnService.ensureStateColumns(state);
            state.setVisible(id, !currently);
            state.normalize();
        });
    }

    private void resetColumnWidths() {
        changeView(state -> {
            ExplorerColumnService.ensureStateColumns(state);
            for (ExplorerColumn column : ExplorerColumnService.columns()) {
                state.setWidth(column.id(), column.defaultWidth());
            }
            state.normalize();
        });
    }

    private void showHeaderContextMenu(String targetId, double x, double y) {
        String target = ExplorerColumnService.column(targetId) == null
                ? ExplorerViewState.NAME_COLUMN_ID : targetId;
        List<CtxEntry> entries = new ArrayList<>();
        entries.add(CtxEntry.action(t("informationera.fe.fit_this_column"), () -> autoFitColumn(target)));
        entries.add(CtxEntry.action(t("informationera.fe.fit_all_columns"), this::autoFitAllColumns));
        entries.add(CtxEntry.separator());
        for (String id : ExplorerColumnService.commonColumnIds()) {
            ExplorerColumn column = ExplorerColumnService.column(id);
            if (column == null) {
                continue;
            }
            entries.add(CtxEntry.actionChecked(
                    ExplorerColumnService.label(column).getString(),
                    columnVisible(id),
                    () -> toggleColumn(id)));
        }
        entries.add(CtxEntry.action(t("informationera.fe.other_columns"), this::showColumnManager));
        entries.add(CtxEntry.separator());
        entries.add(CtxEntry.action(t("informationera.fe.show_all_columns"), this::showAllColumns));
        entries.add(CtxEntry.action(t("informationera.fe.reset_widths"), this::resetColumnWidths));
        openContextMenu(x, y, entries);
    }

    private void autoFitColumn(String id) {
        if (id == null) {
            return;
        }
        double width = measureColumnWidth(id);
        changeView(state -> {
            ExplorerColumnService.ensureStateColumns(state);
            state.setWidth(id, width);
            state.normalize();
        });
        setStatus(t("informationera.fe.status_column_fitted"));
    }

    private void autoFitAllColumns() {
        List<ExplorerColumn> columns = visibleColumns();
        java.util.Map<String, Double> widths = new java.util.LinkedHashMap<>();
        for (ExplorerColumn column : columns) {
            widths.put(column.id(), measureColumnWidth(column.id()));
        }
        changeView(state -> {
            ExplorerColumnService.ensureStateColumns(state);
            for (java.util.Map.Entry<String, Double> entry : widths.entrySet()) {
                state.setWidth(entry.getKey(), entry.getValue());
            }
            state.normalize();
        });
        setStatus(t("informationera.fe.status_columns_fitted"));
    }

    private double measureColumnWidth(String id) {
        ExplorerColumn column = ExplorerColumnService.column(id);
        if (column == null) {
            return 120.0;
        }
        double measured = 0;
        for (Element cell : document.querySelectorAll(".fe-table [data-col]")) {
            if (id.equals(cell.getAttribute("data-col"))) {
                measured = Math.max(measured, cell.scrollWidth);
            }
        }
        Element measurer = document.getElementById("fe-measure");
        if (measurer != null) {
            measured = Math.max(measured,
                    measureText(measurer, ExplorerColumnService.label(column).getString()) + 28);
            for (VirtualFileSystem.Entry entry : renderedEntryList) {
                ExplorerColumnContext context = contextFor(entry);
                double width = measureText(measurer, ExplorerColumnService.value(column, context)) + 28;
                if (ExplorerViewState.NAME_COLUMN_ID.equals(id)) {
                    width += 16 + 6 + (checkboxes ? 16 + 6 : 0);
                }
                measured = Math.max(measured, width);
            }
        }
        if (measured <= 0) {
            return columnWidth(id);
        }
        return Math.max(64, Math.min(4000, Math.ceil(measured)));
    }

    private static double measureText(Element measurer, String text) {
        measurer.setTextContent(text == null ? "" : text);
        return measurer.scrollWidth;
    }

    private void showAllColumns() {
        changeView(state -> {
            ExplorerColumnService.ensureStateColumns(state);
            for (ExplorerColumn column : ExplorerColumnService.columns()) {
                state.setVisible(column.id(), true);
            }
            state.normalize();
        });
    }

    /** 打开原生样式的列选择对话框；草稿在确定前不影响当前视图。 */
    private void showColumnManager() {
        hideTooltip();
        closeContextMenu();
        Element dialog = document.getElementById("fe-columns-dialog");
        Element overlay = document.getElementById("fe-columns-overlay");
        Element title = document.getElementById("fe-columns-title");
        Element hint = document.getElementById("fe-columns-hint");
        if (dialog == null || overlay == null) {
            return;
        }
        ExplorerViewState state = ExplorerPreferences.viewState(currentKey);
        ExplorerColumnService.ensureStateColumns(state);
        columnManagerBase = state.copy();
        columnDrafts.clear();
        for (ExplorerColumn column : orderedColumns()) {
            columnDrafts.add(new ColumnDraft(
                    column.id(),
                    state.visible(column.id(), column.defaultVisible()),
                    state.width(column.id(), column.defaultWidth())));
        }
        if (title != null) {
            title.setTextContent(t("informationera.fe.column_manager_title"));
        }
        if (hint != null) {
            hint.setTextContent(t("informationera.fe.column_manager_hint"));
        }
        Element ok = document.getElementById("fe-columns-ok");
        if (ok != null) {
            ok.setTextContent(t("informationera.fe.ok"));
        }
        Element cancel = document.getElementById("fe-columns-cancel");
        if (cancel != null) {
            cancel.setTextContent(t("informationera.fe.cancel"));
        }
        Element close = document.getElementById("fe-columns-close");
        if (close != null) {
            close.setAttribute("aria-label", t("informationera.fe.close"));
        }
        renderColumnManager();
        Size viewport = document.getViewportSize();
        double width = 640;
        double height = Math.min(560, Math.max(180, viewport.height() - 180));
        double left = Math.max(8, (viewport.width() - width) / 2);
        double top = Math.max(8, (viewport.height() - height) / 2);
        dialog.setInlineStyleProperty("left", px(left));
        dialog.setInlineStyleProperty("top", px(top));
        dialog.setInlineStyleProperty("height", px(height));
        dialog.setInlineStyleProperty("display", "flex");
        overlay.setInlineStyleProperty("display", "block");
        columnManagerOpen = true;
    }

    private void renderColumnManager() {
        Element body = document.getElementById("fe-columns-body");
        if (body == null) {
            return;
        }
        double scrollTop = body.getScrollTop();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-columns-head\">")
          .append("<span>").append(escape(t("informationera.fe.column_manager_show")))
          .append("</span><span>").append(escape(t("informationera.fe.column_manager_column")))
          .append("</span><span>").append(escape(t("informationera.fe.column_manager_width")))
          .append("</span><span>").append(escape(t("informationera.fe.column_manager_order")))
          .append("</span></div>");
        for (int i = 0; i < columnDrafts.size(); i++) {
            ColumnDraft draft = columnDrafts.get(i);
            ExplorerColumn column = ExplorerColumnService.column(draft.id);
            if (column == null) {
                continue;
            }
            boolean name = ExplorerViewState.NAME_COLUMN_ID.equals(draft.id);
            String id = escape(draft.id);
            sb.append("<div class=\"fe-columns-row\" data-column-row=\"").append(i).append("\">");
            sb.append("<span class=\"fe-columns-check")
              .append(draft.visible ? " checked" : "")
              .append(name ? " disabled" : "")
              .append("\" data-column-check=\"").append(id).append("\" role=\"checkbox\" aria-checked=\"")
              .append(draft.visible ? "true" : "false")
              .append("\" title=\"").append(escape(name
                      ? t("informationera.fe.column_manager_name_required")
                      : t("informationera.fe.column_manager_show"))).append("\">")
              .append(draft.visible ? "✓" : "").append("</span>");
            sb.append("<span class=\"fe-columns-label\">")
              .append(escape(ExplorerColumnService.label(column).getString())).append("</span>");
            sb.append("<input class=\"form-input-2 fe-columns-width\" data-column-width=\"")
              .append(id).append("\" type=\"text\" value=\"")
              .append(formatColumnWidth(draft.width)).append("\">");
            sb.append("<span class=\"fe-columns-move-group\">");
            sb.append("<button class=\"fe-columns-move").append(i == 0 ? " disabled" : "")
              .append("\" type=\"button\" data-column-move=\"up\" data-column-row=\"")
              .append(i).append("\" aria-label=\"").append(escape(t("informationera.fe.column_manager_up")))
              .append("\"").append(i == 0 ? " disabled" : "").append(">↑</button>");
            sb.append("<button class=\"fe-columns-move").append(i == columnDrafts.size() - 1 ? " disabled" : "")
              .append("\" type=\"button\" data-column-move=\"down\" data-column-row=\"")
              .append(i).append("\" aria-label=\"").append(escape(t("informationera.fe.column_manager_down")))
              .append("\"").append(i == columnDrafts.size() - 1 ? " disabled" : "").append(">↓</button>");
            sb.append("</span></div>");
        }
        body.setInnerHTML(sb.toString());
        body.setScrollTop(scrollTop);

        for (Element check : body.querySelectorAll(".fe-columns-check[data-column-check]")) {
            String id = check.getAttribute("data-column-check");
            check.addEventListener("click", e -> {
                e.stopPropagation();
                if (ExplorerViewState.NAME_COLUMN_ID.equals(id)) {
                    setStatus(t("informationera.fe.column_manager_name_required"));
                    return;
                }
                for (ColumnDraft draft : columnDrafts) {
                    if (draft.id.equals(id)) {
                        draft.visible = !draft.visible;
                        break;
                    }
                }
                renderColumnManager();
            });
        }
        for (Element input : body.querySelectorAll(".fe-columns-width[data-column-width]")) {
            String id = input.getAttribute("data-column-width");
            input.addEventListener("input", e -> {
                for (ColumnDraft draft : columnDrafts) {
                    if (draft.id.equals(id)) {
                        draft.width = parseColumnWidth(input.getValue(), draft.width);
                        break;
                    }
                }
            });
            input.addEventListener("keydown", e -> {
                e.stopPropagation();
                if (e instanceof KeyEvent ke) {
                    if ("Enter".equals(ke.key)) {
                        applyColumnManager();
                    } else if ("Escape".equals(ke.key)) {
                        closeColumnManager();
                    }
                }
            });
        }
        for (Element move : body.querySelectorAll(".fe-columns-move[data-column-move]")) {
            String direction = move.getAttribute("data-column-move");
            int row = parseColumnRow(move.getAttribute("data-column-row"));
            move.addEventListener("click", e -> {
                e.stopPropagation();
                if (row < 0 || row >= columnDrafts.size()) {
                    return;
                }
                int target = "up".equals(direction) ? row - 1 : row + 1;
                if (target < 0 || target >= columnDrafts.size()) {
                    return;
                }
                ColumnDraft moved = columnDrafts.remove(row);
                columnDrafts.add(target, moved);
                renderColumnManager();
            });
        }
    }

    private static int parseColumnRow(String raw) {
        try {
            return raw == null ? -1 : Integer.parseInt(raw);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }

    private static String formatColumnWidth(double width) {
        return String.format(Locale.ROOT, "%.0f", width);
    }

    private static double parseColumnWidth(String raw, double fallback) {
        try {
            double width = Double.parseDouble(raw == null ? "" : raw.trim());
            if (!Double.isFinite(width)) {
                return fallback;
            }
            return Math.max(48.0, Math.min(4000.0, width));
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    private void applyColumnManager() {
        if (!columnManagerOpen) {
            return;
        }
        List<ColumnDraft> drafts = new ArrayList<>(columnDrafts);
        ExplorerViewState base = columnManagerBase;
        List<String> newOrder = new ArrayList<>();
        for (ColumnDraft draft : drafts) {
            newOrder.add(draft.id);
        }
        List<String> baseRegistered = new ArrayList<>();
        if (base != null) {
            for (String id : base.columnOrder) {
                if (ExplorerColumnService.column(id) != null) {
                    baseRegistered.add(id);
                }
            }
        }
        boolean orderChanged = base == null || !newOrder.equals(baseRegistered);
        changeView(state -> {
            ExplorerColumnService.ensureStateColumns(state);
            if (orderChanged) {
                state.setColumnOrder(newOrder);
            }
            for (ColumnDraft draft : drafts) {
                ExplorerColumn column = ExplorerColumnService.column(draft.id);
                if (column == null) {
                    continue;
                }
                boolean visibleChanged = base == null
                        || base.visible(draft.id, column.defaultVisible()) != draft.visible;
                boolean widthChanged = base == null
                        || base.width(draft.id, column.defaultWidth()) != draft.width;
                if (visibleChanged) {
                    state.setVisible(draft.id, draft.visible);
                }
                if (widthChanged) {
                    state.setWidth(draft.id, draft.width);
                }
            }
            state.normalize();
        });
        closeColumnManager();
    }

    private void closeColumnManager() {
        if (document == null) {
            columnManagerOpen = false;
            columnDrafts.clear();
            columnManagerBase = null;
            return;
        }
        Element dialog = document.getElementById("fe-columns-dialog");
        Element overlay = document.getElementById("fe-columns-overlay");
        if (dialog != null) {
            dialog.setInlineStyleProperty("display", "none");
        }
        if (overlay != null) {
            overlay.setInlineStyleProperty("display", "none");
        }
        columnManagerOpen = false;
        columnDrafts.clear();
        columnManagerBase = null;
    }

    // ---------- 命名冲突对话框 ----------

    /** 显示冲突对话框，列出冲突项，按钮：替换 / 跳过 / 全部替换 / 全部跳过 / 取消。 */
    private void showConflictDialog(List<VirtualFileSystem.Entry> conflicts) {
        Element dialog = document.getElementById("fe-conflict-dialog");
        if (dialog == null) {
            return;
        }
        Element title = document.getElementById("fe-conflict-title");
        Element list = document.getElementById("fe-conflict-list");
        Element actions = document.getElementById("fe-conflict-actions");
        if (title != null) {
            title.setTextContent(t("informationera.fe.conflict_title"));
        }
        if (list != null) {
            StringBuilder sb = new StringBuilder();
            for (VirtualFileSystem.Entry entry : conflicts) {
                sb.append("<div class=\"fe-conflict-item\">")
                  .append(escape(t("informationera.fe.conflict_item", entry.name)))
                  .append("</div>");
            }
            list.setInnerHTML(sb.toString());
        }
        if (actions != null) {
            actions.setInnerHTML("");
            appendConflictButton(actions, "replace", t("informationera.fe.replace"), false, () -> resolveConflictReplace());
            appendConflictButton(actions, "skip", t("informationera.fe.skip"), false, () -> resolveConflictSkip());
            appendConflictButton(actions, "replace-all", t("informationera.fe.replace_all"), true, () -> resolveConflictReplaceAll());
            appendConflictButton(actions, "skip-all", t("informationera.fe.skip_all"), false, () -> resolveConflictSkipAll());
            appendConflictButton(actions, "cancel", t("informationera.fe.cancel"), true, () -> resolveConflictCancel());
        }
        Size viewport = document.getViewportSize();
        double left = Math.max(8, (viewport.width() - 420) / 2);
        double top = Math.max(8, (viewport.height() - 300) / 2);
        dialog.setInlineStyleProperty("left", px(left));
        dialog.setInlineStyleProperty("top", px(top));
        dialog.setInlineStyleProperty("display", "flex");
    }

    private void appendConflictButton(Element actions, String id, String label, boolean danger, Runnable action) {
        Element btn = document.createElement("button");
        btn.setAttribute("class", "fe-conflict-btn" + (danger ? " danger" : ""));
        btn.setTextContent(label);
        btn.addEventListener("click", e -> action.run());
        actions.appendChild(btn);
    }

    private void hideConflictDialog() {
        Element dialog = document.getElementById("fe-conflict-dialog");
        if (dialog != null) {
            dialog.setInlineStyleProperty("display", "none");
        }
    }

    /** 替换：只替换当前列出的冲突项，其余无冲突项照常执行。 */
    private void resolveConflictReplace() {
        List<String> replaceNames = new ArrayList<>(conflictNames);
        hideConflictDialog();
        performMoveOrCopy(conflictSourceKey, conflictEntries, conflictTargetKey, conflictCopy,
                replaceNames, new ArrayList<>());
    }

    /** 跳过：跳过当前列出的冲突项。 */
    private void resolveConflictSkip() {
        List<String> skipNames = new ArrayList<>(conflictNames);
        hideConflictDialog();
        performMoveOrCopy(conflictSourceKey, conflictEntries, conflictTargetKey, conflictCopy,
                new ArrayList<>(), skipNames);
    }

    /** 全部替换：所有冲突项替换。 */
    private void resolveConflictReplaceAll() {
        List<String> replaceNames = new ArrayList<>(conflictNames);
        hideConflictDialog();
        performMoveOrCopy(conflictSourceKey, conflictEntries, conflictTargetKey, conflictCopy,
                replaceNames, new ArrayList<>());
    }

    /** 全部跳过：所有冲突项跳过。 */
    private void resolveConflictSkipAll() {
        List<String> skipNames = new ArrayList<>(conflictNames);
        hideConflictDialog();
        performMoveOrCopy(conflictSourceKey, conflictEntries, conflictTargetKey, conflictCopy,
                new ArrayList<>(), skipNames);
    }

    /** 取消：中断整个操作。 */
    private void resolveConflictCancel() {
        hideConflictDialog();
        conflictEntries.clear();
        conflictNames.clear();
        setStatus(t("informationera.fe.cancel"));
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

    /** 图标视图单条目片段（供普通渲染与虚拟滚动复用）。 */
    private String gridItemHtml(VirtualFileSystem.Entry entry) {
        int index = renderedEntryList.indexOf(entry);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-item ").append("slots".equals(viewMode) ? "slot" : "fe-grid-item")
          .append("\" data-id=\"e").append(index).append("\"")
          .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
          .append(folderPathAttr(entry)).append(">");
        if (checkboxes) {
            sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
        }
        sb.append("<div class=\"fe-icon\">").append(iconFor(entry, iconSize)).append("</div>");
        sb.append("<div class=\"fe-name\" data-name=\"1\">").append(escape(displayName(entry))).append("</div>");

        sb.append("</div>");
        return sb.toString();
    }

    /** 平铺视图单条目片段（供普通渲染与虚拟滚动复用）。 */
    private String tileItemHtml(VirtualFileSystem.Entry entry) {
        int index = renderedEntryList.indexOf(entry);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-item fe-tile-item\" data-id=\"e").append(index).append("\"")
          .append(" data-folder=\"").append(entry.folder ? "1" : "0").append("\"")
          .append(folderPathAttr(entry)).append(">");
        if (checkboxes) {
            sb.append("<span class=\"fe-check\" data-id=\"e").append(index).append("\"></span>");
        }
        sb.append("<div class=\"fe-icon\">").append(iconFor(entry, tileIconSize())).append("</div>");
        sb.append("<div class=\"fe-tile-meta\">");
        sb.append("<div class=\"fe-tile-name\" data-name=\"1\">").append(escape(displayName(entry))).append("</div>");
        sb.append("<div class=\"fe-tile-line\">").append(escape(entry.type)).append("</div>");
        sb.append("<div class=\"fe-tile-line\">").append(entry.folder ? t("informationera.fe.folder") : formatSize(entry.size)).append("</div>");
        sb.append("<div class=\"fe-tile-line\">").append(escape(entry.modified)).append("</div>");
        sb.append("</div></div>");
        return sb.toString();
    }

    private String buildGrid(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        appendEntriesWithGroups(sb, "fe-grid", entries, entry -> sb.append(gridItemHtml(entry)));
        return sb.toString();
    }

    private String buildTiles(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        appendEntriesWithGroups(sb, "fe-tiles", entries, entry -> sb.append(tileItemHtml(entry)));
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

    /** 内容视图：带表头的紧凑多列表格，复用 details 的分组/列渲染，仅行高更矮。 */
    private String buildContent(List<VirtualFileSystem.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"table fe-table fe-content-table\" style=\"").append(tableWidthStyle()).append("\">");
        sb.append("<thead><tr style=\"").append(gridTemplateStyle()).append("\">");
        appendHeaderCells(sb);
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
                    // Alt + 左键：把物品取出到玩家物品栏。这是一次动作，不进入拖拽、
                    // 也不改变选中集，避免顺手把选中状态搅乱。
                    if (me.button == 0 && ContainerSlotInterop.isAltDown()) {
                        withdrawToInventory(entry);
                        return;
                    }
                    // 中键点文件夹：在新标签页中打开（与资源管理器一致）。
                    // 中键不参与拖拽，处理完就返回。
                    if (me.button == 2) {
                        if (entry.folder && entry.childKey != null) {
                            openInNewTab(entry.childKey);
                        }
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
                    showTooltip(item, entryFor(item), me.clientX, me.clientY);
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
                int index = indexOf(check);
                boolean ctrl = e instanceof MouseEvent me && me.controlKey;
                boolean shift = e instanceof MouseEvent me && me.shiftKey;
                if (shift && lastCheckedIndex >= 0) {
                    // Shift+点击：范围勾选。
                    int lo = Math.min(lastCheckedIndex, index);
                    int hi = Math.max(lastCheckedIndex, index);
                    boolean targetChecked = checkedSet.contains(entry);
                    for (int i = lo; i <= hi && i < renderedEntryList.size(); i++) {
                        if (targetChecked) {
                            checkedSet.remove(renderedEntryList.get(i));
                        } else {
                            checkedSet.add(renderedEntryList.get(i));
                        }
                    }
                } else if (ctrl) {
                    // Ctrl+点击：增量勾选。
                    if (!checkedSet.remove(entry)) {
                        checkedSet.add(entry);
                    }
                } else {
                    // 普通点击：单独勾选。
                    if (checkedSet.contains(entry)) {
                        checkedSet.remove(entry);
                    } else {
                        checkedSet.clear();
                        checkedSet.add(entry);
                    }
                }
                lastCheckedIndex = index;
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

        // Ctrl + 双击文件夹：在新窗口中打开（Windows 行为）。
        // 只对文件夹生效——文件没有「在新窗口打开」的语义，落到下面的选中逻辑即可。
        if (ctrl && !shift && clickCount >= 2 && entry.folder && entry.childKey != null) {
            openInNewWindow(entry.childKey);
            return;
        }

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
            if (entry != null && checkedSet.contains(entry)) {
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

    /**
     * 条目悬停提示。
     *
     * <p>物品文件显示原版物品提示框（由 {@link ExplorerItemTooltip} 在渲染阶段绘制），
     * 这样物品组件、模组追加行、耐久等信息与物品栏里完全一致；文件夹、取不到物品栈的
     * 条目（例如物品所属模组已被卸载）仍回退到窗口内的文本提示。
     */
    private void showTooltip(Element row, VirtualFileSystem.Entry entry, double x, double y) {
        if (entry == null || tooltip == null) {
            return;
        }
        boolean elsewhere = entry.parentFolderKey != null && !entry.parentFolderKey.equals(currentKey);
        boolean flattened = filterOpen && filterSelection.includeSubfolders;
        String location = !entry.folder && (elsewhere || flattened || !searchQuery.isBlank())
                ? t("informationera.fe.detail_location",
                pathText(entry.parentFolderKey != null ? entry.parentFolderKey : currentKey) + " > " + entry.name)
                : null;
        if (!entry.folder) {
            ItemStack stack = stackForEntry(entry);
            if (!stack.isEmpty()) {
                hideDomTooltip();
                ExplorerItemTooltip.show(document, row, stack, location);
                return;
            }
        }
        ExplorerItemTooltip.hide(document);
        tooltip.setTextContent(location == null ? metadataText(entry) : metadataText(entry) + '\n' + location);
        moveTooltip(x, y);
        tooltip.setInlineStyleProperty("display", "block");
        tooltipVisible = true;
    }

    private void moveTooltip(double x, double y) {
        if (tooltip == null) {
            return;
        }
        tooltip.setInlineStyleProperty("left", px(x + 14));
        tooltip.setInlineStyleProperty("top", px(y + 18));
    }

    private String metadataText(VirtualFileSystem.Entry entry) {
        StringBuilder sb = new StringBuilder(entry.name);
        sb.append('\n').append(t("informationera.fe.detail_type", entry.type));
        sb.append('\n').append(entry.folder
                ? t("informationera.fe.folder")
                : t("informationera.fe.detail_size", formatSize(entry.size)));
        sb.append('\n').append(t("informationera.fe.detail_modified_short", entry.modified));
        return sb.toString();
    }

    /**
     * 把资源管理器里的一个物品条目取出到玩家物品栏。
     *
     * <p>数量交给服务端按该物品的一个堆叠上限决定（可堆叠即 64，不可堆叠即 1），
     * 合并由原版的物品栏插入逻辑完成。客户端只发意图。
     */
    private void withdrawToInventory(VirtualFileSystem.Entry entry) {
        if (entry == null || entry.folder || entry.itemKey == null) {
            return;
        }
        if (entry.count <= 0L) {
            // 库存已经空了（文件还在，但数量为 0），没什么可取的。
            setStatus(t("informationera.msg.not_enough", entry.name));
            return;
        }
        DiskClient.withdrawToInventory(entry.itemKey, 0L);
    }

    private void hideTooltip() {
        ExplorerItemTooltip.hide(document);
        hideDomTooltip();
    }

    /** 只收起窗口内的文本提示；物品的原版提示框由 {@link #hideTooltip()} 一并收起。 */
    private void hideDomTooltip() {
        tooltipVisible = false;
        if (tooltip != null) {
            tooltip.setInlineStyleProperty("display", "none");
        }
    }

    /**
     * 每 tick 校验提示框是否还该显示。
     *
     * <p>只靠条目的 {@code mouseout} 是不够的：鼠标划过时条目会被整体重渲染、鼠标也可能
     * 直接移到窗口外或界面上没有条目的区域，这些情况都不会触发 {@code mouseout}，
     * 提示框就会永久留在屏幕上。这里按「鼠标下是否真的还有一个条目」来兜底。
     *
     * <p>物品的原版提示框不在 DOM 里，单独走一遍校验。
     */
    void tickTooltip() {
        ExplorerPreferences.tick();
        checkFilterLanguage();
        ExplorerItemTooltip.tick(document);
        if (!tooltipVisible || tooltip == null || document == null || !document.isActive()) {
            return;
        }
        Position doc = document.getMouseDocumentPosition();
        if (doc == null) {
            hideTooltip();
            return;
        }
        Element hit = document.hitTest(doc);
        if (hit == null || hit.closest(".fe-item") == null) {
            hideTooltip();
        }
    }

    /**
     * 语种切换/资源重载后，关键词候选和本地化名称都变了。
     *
     * <p>筛选窗格或弹窗开着时立即重绘；内容区也要重算，因为已选关键词按本地化名称匹配。
     */
    private void checkFilterLanguage() {
        int version = ExplorerKeywordIndex.languageVersion();
        if (version == filterLanguageVersion) {
            return;
        }
        filterLanguageVersion = version;
        filterCandidateLanguageVersion = version;
        filterCandidateCache.clear();
        if (!filterOpen || document == null) {
            return;
        }
        renderFilterPane();
        if (filterDialogOpen) {
            renderFilterDialog();
        }
        renderContent();
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
        bindButtonHint(id, () -> text);
    }

    /** 提示文案按当前窗口状态实时取值（最大化/还原按钮共用）。 */
    private void bindButtonHint(String id, java.util.function.Supplier<String> text) {
        Element button = document.getElementById(id);
        if (button == null) {
            return;
        }
        button.addEventListener("mouseover", e -> showButtonHint(button, text.get()));
        button.addEventListener("mouseout", e -> hideTooltip());
    }

    private String maximizeHint() {
        return t(mode.isMaximized() ? "informationera.fe.restore" : "informationera.fe.max");
    }

    /** 同步标题栏最大化按钮的图标、无障碍标签与提示：最大化后显示“还原”。 */
    private void updateMaximizeControl() {
        Element max = document == null ? null : document.getElementById("fe-max");
        if (max == null) {
            return;
        }
        max.setAttribute("aria-label", maximizeHint());
        max.setInnerHTML(mode.isMaximized()
                ? "<svg viewBox=\"0 0 24 24\"><rect x=\"5\" y=\"8\" width=\"11\" height=\"11\" fill=\"none\""
                + " stroke=\"currentColor\" stroke-width=\"2\"/><polyline points=\"9,8 9,5 19,5 19,15 16,15\""
                + " fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"/></svg>"
                : "<svg viewBox=\"0 0 24 24\"><rect x=\"5\" y=\"5\" width=\"14\" height=\"14\" fill=\"none\""
                + " stroke=\"currentColor\" stroke-width=\"2\"/></svg>");
    }

    private void showButtonHint(Element button, String text) {
        if (tooltip == null) {
            return;
        }
        ExplorerItemTooltip.hide(document);
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
        setButtonEnabled("fe-back", !ExplorerSession.globalBackStack.isEmpty());
        setButtonEnabled("fe-forward", !ExplorerSession.globalForwardStack.isEmpty());
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
        Set<String> visited = new HashSet<>();
        VirtualFileSystem.Folder folder = fs.folder(key);
        while (folder != null && visited.add(folder.key)) {
            names.addFirst(folder.name);
            folder = folder.parentKey == null ? null : fs.folder(folder.parentKey);
        }
        return String.join(" > ", names);
    }

    private void createShortcut(VirtualFileSystem.Entry entry) {
        // 快捷方式概念已取消：文件的「复制」本身就是增加一个指向同一份库存的引用。
        DiskClient.copy(List.of(entry.id), currentKey);
        setStatus(t("informationera.fe.status_shortcut_created", entry.name));
    }

    private void copyPath(VirtualFileSystem.Entry entry) {
        String parent = entry.parentFolderKey != null ? entry.parentFolderKey : currentKey;
        String path = pathText(parent) + " > " + entry.name;
        Minecraft.getInstance().keyboardHandler.setClipboard(path);
        setStatus(t("informationera.fe.status_path_copied", path));
    }

    private void sendTo(String targetKey, VirtualFileSystem.Entry entry) {
        VirtualFileSystem.Folder target = fs.folder(targetKey);
        if (target == null) {
            setStatus(t("informationera.fe.status_path_not_found", targetKey));
            return;
        }
        DiskClient.copy(java.util.List.of(entry.id), targetKey);
        setStatus(t("informationera.fe.status_sent_to", target.name));
    }

    // ---------- 工具方法 ----------

    /**
     * 条目图标。
     *
     * <p>物品文件渲染成 AUI 的 {@code <item>} 元素——它走真实物品的图标绘制链路
     * （贴图、耐久条、数量角标都包含在内），具体栈由 Java 侧 {@link #driveItemIcons}
     * 用 {@code setDrivenState} 喂入。这里只输出一个带定位用 data 属性的空壳，
     * 不在 HTML 里写物品表达式，避免「文本表达式」与「驱动栈」两套来源互相覆盖。
     *
     * <p>{@code interactive="0"} 关掉该元素自身的交互能力：资源管理器里整行的
     * 点击/拖拽由行容器统一处理，图标不应该独立参与命中。
     */
    /**
     * 磁盘的图标：直接用储存卡那张贴图。
     *
     * <p>用 AUI 的 {@code <texture>} 元素按资源路径取图，不需要像物品图标那样事后喂
     * ItemStack，所以导航树、内容区、固定项都能直接内联。
     */
    private static String diskIcon(int size) {
        return "<texture class=\"fe-disk-icon\" src=\"informationera:textures/item/storage_card.png\""
                + " style=\"width:" + size + "px;height:" + size + "px\"></texture>";
    }

    static String iconFor(VirtualFileSystem.Entry entry, int size) {
        if (entry.folder) {
            // 磁盘（磁盘根）也是一张卡，不该画成文件夹。
            return DiskFolder.ROOT_ID.equals(entry.childKey) ? diskIcon(size) : folderIcon(size);
        }
        // <item> 自己永远只按 16px 画（内建的 iconScale 只认真槽，这里不用槽），所以直接对元素做
        // CSS 缩放：AUI 支持 transform / transform-origin，以盒子中心为原点放大，图标正好居中。
        String label = entry.count > 1L ? ItemStacks.overlayText(entry.count) : null;
        StringBuilder sb = new StringBuilder();
        sb.append("<span class=\"fe-item-icon\" data-icon-for=\"").append(escapeAttr(entry.id))
          .append("\" style=\"width:").append(size).append("px;height:").append(size).append("px\">");
        sb.append("<item interactive=\"0\" style=\"width:").append(size).append("px;height:").append(size).append("px");
        if (size > 16) {
            // 只有真要放大时才上 transform：它会把物品推进 AUI 的「带变换」绘制路径，绝对 Z 会
            // 和同文档的面板（右键菜单等）撞在一起。16px 的平铺视图没必要付这个代价。
            sb.append(";transform:scale(").append(iconScaleFor(size))
              .append(");transform-origin:50% 50%");
        }
        sb.append("\"></item>");
        // 大图标的数量角标自己画：AUI 自带角标画在物品的 pose 里，会跟着图标一起放大，糊一大片。
        if (size > 16 && label != null) {
            sb.append("<span class=\"fe-item-count\">").append(escape(label)).append("</span>");
        }
        sb.append("</span>");
        return sb.toString();
    }

    /** 物品图标的缩放系数：<item> 的基础尺寸是 16px，要画成 size 像素就得乘这个数。 */
    static String iconScaleFor(int size) {
        return String.valueOf(size / 16.0);
    }

    /**
     * 渲染完成后，把每个物品文件对应的真实 ItemStack 喂给它的 {@code <item>} 元素。
     *
     * <p>这一步必须在 DOM 建好之后做：{@code setDrivenState} 需要拿到已经存在的元素实例。
     */
    private void driveItemIcons() {
        if (fs == null) {
            return;
        }
        List<Element> icons = document.querySelectorAll(".fe-item-icon");
        if (icons == null || icons.isEmpty()) {
            return;
        }
        HolderLookup.Provider registries = DiskClient.registryProvider();
        for (Element icon : icons) {
            String nodeId = icon.getAttribute("data-icon-for");
            if (nodeId == null || nodeId.isEmpty()) {
                continue;
            }
            VirtualFileSystem.Entry entry = entryById(nodeId);
            if (entry == null || entry.folder || entry.itemKey == null) {
                continue;
            }
            Element child = icon.querySelector("item");
            if (!(child instanceof Item itemElement)) {
                continue;
            }
            ItemStack stack = fs.iconStackOf(entry, registries);
            if (stack.isEmpty()) {
                continue;
            }
            // 大图标时角标是我们自己画的（.fe-item-count），这时别再让 AUI 画一份——
            // 它的角标画在物品的 pose 里，会跟着图标一起放大。
            String overlay = icon.querySelector(".fe-item-count") != null
                    ? null : ItemStacks.overlayText(entry.count);
            itemElement.setDrivenState(stack, overlay, false, false, Item.Source.INGREDIENT);
        }
    }

    /** 按节点 id 找到当前渲染列表里的条目。 */
    private VirtualFileSystem.Entry entryById(String nodeId) {
        for (VirtualFileSystem.Entry entry : renderedEntryList) {
            if (entry.id.equals(nodeId)) {
                return entry;
            }
        }
        return null;
    }

    private static String escapeAttr(String value) {
        return escape(value);
    }

    private static String folderIcon(int size) {
        return "<svg viewBox=\"0 0 24 24\" style=\"width:" + size + "px;height:" + size + "px;color:var(--ore-gold)\"><path d=\"M3 7h6l2 2h10v11H3z\" fill=\"currentColor\"/></svg>";
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

    /**
     * 数量格式化。
     *
     * <p>这个位置原先显示「文件大小（字节）」，在物品磁盘里对应的语义就是「数量」。
     * 超过一万用「万」缩写，避免数量列被长数字撑开。
     */
    private static String formatSize(long count) {
        if (count < 0L) {
            return "";
        }
        if (count < 10_000L) {
            return Long.toString(count);
        }
        if (count < 100_000_000L) {
            return String.format(Locale.ROOT, "%.1f万", count / 10_000.0);
        }
        return String.format(Locale.ROOT, "%.2f亿", count / 100_000_000.0);
    }
}
