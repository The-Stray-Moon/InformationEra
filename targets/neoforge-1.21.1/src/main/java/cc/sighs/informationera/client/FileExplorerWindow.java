package cc.sighs.informationera.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.render.DocumentLayerOrder;
import com.sighs.apricityui.render.Operation;

import static cc.sighs.informationera.client.FileExplorerText.t;

import java.util.ArrayList;
import java.util.List;

/**
 * 多窗口文件资源管理器。
 * AUI 的 Overlay 架构下每个窗口对应一个 Document（均来自同一个 HTML 模板），
 * 运行时共享同一个虚拟文件系统，以支持跨窗口操作。
 *
 * 文件拖拽（左键移动/复制、右键放置菜单）由这里统一协调：源窗口只在按下时上报一次，
 * 后续的移动、释放、目标解析都在全局 tick 里完成，因此鼠标跨窗口后仍然能正常落下。
 */
public final class FileExplorerWindow {

    private static final String TEMPLATE = "screens/file-explorer.html";

    private static final List<FileExplorerController> windows = new ArrayList<>();
    private static final List<String> recentPaths = new ArrayList<>();
    private static VirtualFileSystem fs;
    private static int zCounter = 10;
    private static FileExplorerController focusedController;

    /** 跨窗口拖拽的全局状态（坐标均使用 AUI 屏幕坐标）。 */
    private static FileExplorerController dragSource;
    private static List<VirtualFileSystem.Entry> dragEntries;
    private static String dragSourceKey;
    private static boolean dragRight;
    private static double dragStartX;
    private static double dragStartY;
    private static boolean dragActive;
    private static boolean suppressNextContextMenu;
    private static String dragCursorKind;
    private static long hoverFolderSince = 0L;
    private static String hoverFolderKey;

    /** 跨窗口标签页拖拽的全局状态（坐标均使用 AUI 屏幕坐标）。 */
    private static FileExplorerController tabDragSource;
    private static int tabDragSourceIndex = -1;
    private static FileExplorerController.Tab tabDragTab;
    private static double tabDragStartX;
    private static double tabDragStartY;
    private static boolean tabDragActive;

    private FileExplorerWindow() {
    }

    /** 每次调用创建一个新窗口。 */
    public static void open() {
        if (fs == null) {
            fs = VirtualFileSystem.create();
        }
        Document document = ApricityUI.createDocument(TEMPLATE);
        if (document == null) {
            return;
        }
        // 常驻窗口：即使底层有原版 Screen 也保持显示。
        document.setReloadPersistent(true);
        FileExplorerController controller = new FileExplorerController(fs);
        controller.mount(document);
        windows.add(controller);
        FileExplorerController.setActive(controller);
        focus(controller);
    }

    /** 打开新窗口并定位到指定路径。 */
    public static void openAt(String pathKey) {
        open();
        if (!windows.isEmpty()) {
            FileExplorerController controller = windows.get(windows.size() - 1);
            controller.navigateToExternal(pathKey);
        }
    }

    /** 聚焦窗口：置前（提高 body translateZ）并设为活跃，不改变窗口列表顺序。 */
    public static void focus(FileExplorerController controller) {
        if (controller == null || !windows.contains(controller)) {
            return;
        }
        setZ(controller, ++zCounter);
        focusedController = controller;
        FileExplorerController.setActive(controller);
    }

    private static void setZ(FileExplorerController controller, int z) {
        Document document = controller.document();
        if (document == null || document.body == null) {
            return;
        }
        document.body.setInlineStyleProperty("transform", "translateZ(" + z + "px)");
    }

    /** 当前聚焦的窗口，无窗口返回 null。 */
    public static FileExplorerController focused() {
        if (focusedController != null && windows.contains(focusedController)) {
            return focusedController;
        }
        return windows.isEmpty() ? null : windows.get(windows.size() - 1);
    }

    /** 记录最近访问路径（去重，最近在前）。 */
    public static void recordRecentPath(String key) {
        if (key == null) {
            return;
        }
        recentPaths.remove(key);
        recentPaths.add(0, key);
        if (recentPaths.size() > 15) {
            recentPaths.remove(recentPaths.size() - 1);
        }
    }

    public static List<String> getRecentPaths() {
        return recentPaths;
    }

    /** 任务标右键的 Jump List：打开新窗口 / 最近路径。 */
    public static void showJumpList(Document triggerDoc, double x, double y) {
        Element menu = triggerDoc.getElementById("fe-jump-list");
        if (menu == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-jump-item\" data-action=\"new\">").append(t("informationera.fe.open_new_window")).append("</div>");
        sb.append("<div class=\"fe-jump-sep\"></div>");
        if (recentPaths.isEmpty()) {
            sb.append("<div class=\"fe-jump-item fe-jump-disabled\">").append(t("informationera.fe.no_recent")).append("</div>");
        } else {
            for (String key : recentPaths) {
                VirtualFileSystem.Folder folder = fs != null ? fs.folder(key) : null;
                String label = folder != null ? folder.name : key;
                sb.append("<div class=\"fe-jump-item\" data-path=\"").append(FileExplorerController.escape(key))
                  .append("\">").append(FileExplorerController.escape(label)).append("</div>");
            }
        }
        menu.setInnerHTML(sb.toString());
        // 菜单向上浮（固定显示在按钮上方），不跟随鼠标向下展开。
        menu.setInlineStyleProperty("left", "8px");
        menu.setInlineStyleProperty("bottom", "40px");
        menu.removeInlineStyleProperty("top");
        menu.setInlineStyleProperty("display", "block");
        // 打开菜单期间让 body 接收全屏点击，用于点空白关闭。
        if (triggerDoc.body != null) {
            triggerDoc.body.setInlineStyleProperty("pointer-events", "auto");
        }

        for (Element item : menu.querySelectorAll(".fe-jump-item")) {
            String path = item.getAttribute("data-path");
            String action = item.getAttribute("data-action");
            item.addEventListener("click", e -> {
                e.stopPropagation();
                // 完整关闭：隐藏菜单、隐藏拦截层、恢复 body 穿透。
                FileExplorerWindow.hideJumpList(triggerDoc);
                FileExplorerTrigger.hidePreview();
                if ("new".equals(action)) {
                    open();
                } else if (path != null) {
                    openAt(path);
                }
            });
        }
    }

    /** 关闭任务栏 Jump List。 */
    public static void hideJumpList(Document triggerDoc) {
        Element menu = triggerDoc.getElementById("fe-jump-list");
        if (menu != null) {
            menu.setInlineStyleProperty("display", "none");
        }
        // 恢复 body 的穿透，避免影响其它点击。
        if (triggerDoc.body != null) {
            triggerDoc.body.removeInlineStyleProperty("pointer-events");
        }
    }

    /** 渲染任务栏缩略图预览到 trigger document 的预览容器。 */
    public static void renderTaskbarPreview(Element container, Document triggerDoc) {
        if (container == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (windows.isEmpty()) {
            container.setInnerHTML("");
            return;
        }
        sb.append("<div class=\"fe-taskbar-preview-list\">");
        // 最近使用在前。
        for (int i = windows.size() - 1; i >= 0; i--) {
            FileExplorerController controller = windows.get(i);
            sb.append("<div class=\"fe-taskbar-preview-item\" data-window=\"").append(i).append("\">");
            sb.append("<div class=\"fe-taskbar-preview-title\">")
              .append(FileExplorerController.escape(controller.currentFolderName())).append("</div>");
            sb.append("<div class=\"fe-taskbar-preview-path\">")
              .append(FileExplorerController.escape(controller.currentKey())).append("</div>");
            sb.append("<span class=\"fe-taskbar-preview-close\">×</span>");
            sb.append("</div>");
        }
        sb.append("</div>");
        container.setInnerHTML(sb.toString());

        for (Element item : container.querySelectorAll(".fe-taskbar-preview-item")) {
            String idxStr = item.getAttribute("data-window");
            int idx = idxStr == null ? -1 : Integer.parseInt(idxStr);
            Element close = item.querySelector(".fe-taskbar-preview-close");
            if (close != null) {
                close.addEventListener("click", e -> {
                    e.stopPropagation();
                    if (idx >= 0 && idx < windows.size()) {
                        windows.get(idx).closeWindow();
                    }
                    renderTaskbarPreview(container, triggerDoc);
                });
            }
            item.addEventListener("click", e -> {
                if (idx >= 0 && idx < windows.size()) {
                    FileExplorerController controller = windows.get(idx);
                    if (controller.isMinimized()) {
                        controller.toggleMinimizeForTaskbar();
                    }
                    focus(controller);
                    FileExplorerTrigger.hidePreview();
                }
            });
        }
    }

    public static void unregister(FileExplorerController controller) {
        windows.remove(controller);
        if (dragSource == controller) {
            cancelDrag();
        }
        if (!windows.isEmpty()) {
            focus(windows.get(windows.size() - 1));
        }
    }

    /** 刷新所有窗口的显示（供跨窗口撤销/重做等全局变更后调用）。 */
    public static void refreshAll() {
        for (FileExplorerController controller : new ArrayList<>(windows)) {
            controller.refreshAfterExternal();
        }
    }

    public static int windowCount() {
        return windows.size();
    }

    public static List<FileExplorerController> getWindows() {
        return windows;
    }

    public static VirtualFileSystem getFs() {
        return fs;
    }

    /** 右键拖拽是否正在进行（用于抑制拖拽期间的右键菜单）。 */
    public static boolean isRightDragActive() {
        return dragSource != null && dragRight && dragActive;
    }

    /** 右键拖拽释放后短暂抑制一次 contextmenu，避免普通右键菜单覆盖 drop 菜单。 */
    public static boolean consumeContextMenuSuppression() {
        boolean value = suppressNextContextMenu;
        suppressNextContextMenu = false;
        return value;
    }

    /** 源窗口在文件项按下左/右键时调用一次，登记一次潜在拖拽。 */
    public static void beginFileDrag(FileExplorerController source, List<VirtualFileSystem.Entry> entries,
                                     String sourceKey, boolean right) {
        Position mouse = Operation.getMousePositionDirectly();
        if (mouse == null || entries.isEmpty()) {
            return;
        }
        dragSource = source;
        dragEntries = new ArrayList<>(entries);
        dragSourceKey = sourceKey;
        dragRight = right;
        dragStartX = mouse.x;
        dragStartY = mouse.y;
        dragActive = false;
    }

    /** 每客户端 tick 调用一次，推进跨窗口拖拽。 */
    public static void dragTick() {
        if (dragSource == null) {
            return;
        }
        Position mouse = Operation.getMousePositionDirectly();
        if (mouse == null) {
            return;
        }

        if (!dragActive) {
            // 左键未激活且已释放：只是普通点击，取消潜在拖拽。
            if (!dragRight && (Operation.getMouseButtons() & 1) == 0) {
                cancelDrag();
                return;
            }
            double dx = mouse.x - dragStartX;
            double dy = mouse.y - dragStartY;
            if (dx * dx + dy * dy <= 25) {
                return;
            }
            dragActive = true;
            if (dragRight) {
                dragSource.closeContextMenu();
            }
            String html = dragSource.ghostHtmlFor(dragEntries);
            showGlobalGhost(html);
        }

        moveGlobalGhost();
        updateDragCursorAndHover(mouse);

        // 右键拖拽的释放由 document mouseup 同步处理，避免 contextmenu 与 tick 竞争；
        // 左键跨窗口拖拽仍由这里轮询释放。
        if (!dragRight) {
            boolean released = (Operation.getMouseButtons() & 1) == 0;
            if (released) {
                finishDrag(mouse.x, mouse.y);
            }
        }
    }

    // ---------- 跨窗口标签页拖拽 ----------

    /** 源窗口标签页按下时上报一次（多窗口场景）。 */
    public static void beginTabDrag(FileExplorerController source, int tabIndex, double screenX, double screenY) {
        tabDragSource = source;
        tabDragSourceIndex = tabIndex;
        tabDragTab = null;
        tabDragStartX = screenX;
        tabDragStartY = screenY;
        tabDragActive = false;
    }

    public static boolean isTabDragActive() {
        return tabDragSource != null;
    }

    /** 每 tick 推进标签页拖拽（与文件拖拽共用 tick 入口）。 */
    public static void tabDragTick() {
        if (tabDragSource == null) {
            return;
        }
        Position mouse = Operation.getMousePositionDirectly();
        if (mouse == null) {
            return;
        }
        if (!tabDragActive) {
            double dx = mouse.x - tabDragStartX;
            double dy = mouse.y - tabDragStartY;
            if (dx * dx + dy * dy <= 25) {
                return;
            }
            tabDragActive = true;
            // 快照标签状态（在拖拽开始时保存一次）。
            tabDragSource.saveActiveTabState();
            FileExplorerController.Tab tab = tabDragSource.tabs.get(tabDragSourceIndex);
            tabDragTab = cloneTab(tab);
            showGlobalGhost("<div class=\"fe-ghost-row\">" + folderIcon(16)
                    + "<span>" + FileExplorerController.escape(tab.title) + "</span></div>");
        }
        moveGlobalGhost();
        boolean released = (Operation.getMouseButtons() & 1) == 0;
        if (released) {
            finishTabDrag(mouse.x, mouse.y);
        }
    }

    private static FileExplorerController.Tab cloneTab(FileExplorerController.Tab src) {
        FileExplorerController.Tab dst = new FileExplorerController.Tab();
        dst.title = src.title;
        dst.currentKey = src.currentKey;
        dst.backStack.addAll(src.backStack);
        dst.forwardStack.addAll(src.forwardStack);
        dst.selected.addAll(src.selected);
        dst.expandedSidebar.addAll(src.expandedSidebar);
        dst.viewMode = src.viewMode;
        dst.iconSize = src.iconSize;
        dst.sortField = src.sortField;
        dst.sortAsc = src.sortAsc;
        dst.foldersFirst = src.foldersFirst;
        dst.searchQuery = src.searchQuery;
        dst.lastClickedIndex = src.lastClickedIndex;
        return dst;
    }

    private static void finishTabDrag(double screenX, double screenY) {
        Position screenPos = new Position(screenX, screenY);
        FileExplorerController target = hitTabStripController(screenPos);
        if (target == tabDragSource) {
            // 同窗口释放：保持原标签页重排逻辑。
            tabDragSource.reorderTabToScreen(screenPos, tabDragSourceIndex);
        } else if (target != null && target != tabDragSource) {
            // 合并到目标窗口标签栏。
            FileExplorerController.Tab tab = tabDragSource.detachTab(tabDragSourceIndex);
            if (tab == null && tabDragTab != null) {
                tab = cloneTab(tabDragTab);
                tabDragSource.tabs.remove(tabDragSourceIndex);
                if (tabDragSource.tabs.isEmpty()) {
                    tabDragSource.closeWindow();
                }
            }
            if (tab != null) {
                target.adoptTab(tab);
            }
        } else {
            // 拖到窗口外空白：形成新窗口。
            FileExplorerController.Tab tab = tabDragSource.detachTab(tabDragSourceIndex);
            if (tab == null && tabDragTab != null) {
                tab = cloneTab(tabDragTab);
                tabDragSource.tabs.remove(tabDragSourceIndex);
                if (tabDragSource.tabs.isEmpty()) {
                    tabDragSource.closeWindow();
                }
            }
            if (tab != null) {
                openWithTab(tab);
            }
        }
        cancelTabDrag();
    }

    private static FileExplorerController hitTabStripController(Position screenPos) {
        List<Document> layered = DocumentLayerOrder.frontToBack(Document.getAll());
        if (layered == null) {
            return null;
        }
        for (Document document : layered) {
            FileExplorerController controller = controllerFor(document);
            if (controller != null && controller.hitTabStrip(screenPos)) {
                return controller;
            }
        }
        return null;
    }

    private static void openWithTab(FileExplorerController.Tab tab) {
        if (fs == null) {
            fs = VirtualFileSystem.create();
        }
        Document document = ApricityUI.createDocument(TEMPLATE);
        if (document == null) {
            return;
        }
        document.setReloadPersistent(true);
        FileExplorerController controller = new FileExplorerController(fs);
        controller.mount(document);
        windows.add(controller);
        FileExplorerController.setActive(controller);
        focus(controller);
        controller.adoptTab(tab);
    }

    private static void cancelTabDrag() {
        hideGlobalGhost();
        tabDragSource = null;
        tabDragSourceIndex = -1;
        tabDragTab = null;
        tabDragActive = false;
    }

    private static String folderIcon(int size) {
        return "<svg viewBox=\"0 0 24 24\" style=\"width:" + size + "px;height:" + size
                + "px;color:var(--ore-gold)\"><path d=\"M3 7h6l2 2h10v11H3z\" fill=\"currentColor\"/></svg>";
    }

    /** 拖拽激活期间：实时解析落点，结合修饰键计算光标类型，并在文件夹上停留超时自动展开。 */
    private static void updateDragCursorAndHover(Position mouse) {
        DropTarget target = resolveDropTarget(mouse.x, mouse.y);
        boolean alt = Operation.isKeyPressed("key.keyboard.left.alt")
                || Operation.isKeyPressed("key.keyboard.right.alt");
        boolean ctrl = Operation.isKeyPressed("key.keyboard.left.control")
                || Operation.isKeyPressed("key.keyboard.right.control");

        if (target == null) {
            dragCursorKind = "notallowed";
            hoverFolderSince = 0L;
            hoverFolderKey = null;
            setGhostBadge("⊘");
            return;
        }

        String folderKey = target.key;
        boolean hitFolderRow = !folderKey.equals(target.controller.currentKey());
        // 计算光标：Alt=快捷方式，Ctrl=复制，同盘默认移动，跨盘复制。
        if (alt) {
            dragCursorKind = "link";
            setGhostBadge("→");
        } else if (ctrl) {
            dragCursorKind = "copy";
            setGhostBadge("+");
        } else if (isCrossDrive(dragSourceKey, folderKey)) {
            dragCursorKind = "copy";
            setGhostBadge("+");
        } else {
            dragCursorKind = "arrow";
            setGhostBadge("");
        }

        // 悬停文件夹自动展开。
        if (hitFolderRow && !folderKey.equals(hoverFolderKey)) {
            hoverFolderKey = folderKey;
            hoverFolderSince = System.currentTimeMillis();
        }
        if (hitFolderRow && hoverFolderKey != null && hoverFolderKey.equals(folderKey)
                && System.currentTimeMillis() - hoverFolderSince > 600L) {
            if (!folderKey.equals(target.controller.currentKey())) {
                target.controller.navigateToExternal(folderKey);
            }
            hoverFolderSince = System.currentTimeMillis();
        }
        if (!hitFolderRow) {
            hoverFolderKey = null;
            hoverFolderSince = 0L;
        }
    }

    /** 由源窗口的 document mouseup 同步调用，解决右键 contextmenu 与 tick 轮询的时序竞争。 */
    public static void dragMouseUp(double screenX, double screenY) {
        if (dragSource == null) {
            return;
        }
        if (!dragRight) {
            // 左键跨窗口可能鼠标已经在其它窗口释放，这里仅作兜底；真正收尾交给 tick。
            return;
        }
        if (!dragActive) {
            // 未越过拖拽阈值，只是普通点击，交给 click/contextmenu。
            cancelDrag();
            return;
        }
        finishDrag(screenX, screenY);
    }

    private static void finishDrag(double screenX, double screenY) {
        DropTarget target = resolveDropTarget(screenX, screenY);
        if (dragRight) {
            if (target != null) {
                // 只有成功打开 drop 菜单才抑制紧随的 contextmenu。
                target.controller.showExternalDropMenu(dragEntries, dragSourceKey, target.key,
                        new Position(screenX, screenY), dragSource);
                suppressNextContextMenu = true;
            }
            // target == null：不抑制，让 contextmenu 正常弹普通右键菜单。
        } else if (target != null) {
            boolean alt = Operation.isKeyPressed("key.keyboard.left.alt")
                    || Operation.isKeyPressed("key.keyboard.right.alt");
            boolean ctrl = Operation.isKeyPressed("key.keyboard.left.control")
                    || Operation.isKeyPressed("key.keyboard.right.control");
            boolean moved;
            if (alt) {
                moved = dragSource.createShortcuts(dragSourceKey, dragEntries, target.key);
            } else if (ctrl) {
                moved = dragSource.moveOrCopy(dragSourceKey, dragEntries, target.key, true);
            } else {
                boolean crossDrive = isCrossDrive(dragSourceKey, target.key);
                moved = dragSource.moveOrCopy(dragSourceKey, dragEntries, target.key, crossDrive);
            }
            if (moved && target.controller != dragSource) {
                target.controller.refreshAfterExternal();
            }
        }
        cancelDrag();
    }

    /** 判断 sourceKey 与 targetKey 是否跨越不同“磁盘”（thispc/C 与 thispc/D/E 互跨）。 */
    private static boolean isCrossDrive(String sourceKey, String targetKey) {
        String s = driveOf(sourceKey);
        String t = driveOf(targetKey);
        return s != null && t != null && !s.equals(t);
    }

    private static String driveOf(String key) {
        if (key == null) {
            return null;
        }
        if (key.equals("thispc/C") || key.startsWith("thispc/C/")) {
            return "C";
        }
        if (key.equals("thispc/D") || key.startsWith("thispc/D/")) {
            return "D";
        }
        if (key.equals("thispc/E") || key.startsWith("thispc/E/")) {
            return "E";
        }
        return null;
    }

    private static DropTarget resolveDropTarget(double screenX, double screenY) {
        Position screenPos = new Position(screenX, screenY);
        // 按 AUI 的真实层叠顺序（前到后）命中，保证落下时选中鼠标下最上层的窗口。
        List<Document> layered = DocumentLayerOrder.frontToBack(Document.getAll());
        if (layered == null) {
            return null;
        }
        for (Document document : layered) {
            FileExplorerController controller = controllerFor(document);
            if (controller == null) {
                continue;
            }
            DropTarget target = controller.hitDropTarget(screenPos);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    private static FileExplorerController controllerFor(Document document) {
        for (FileExplorerController controller : windows) {
            if (controller.document() == document) {
                return controller;
            }
        }
        return null;
    }

    /** 鼠标下最上层窗口的光标类型：ew/ns/nwse/pointer；无匹配返回 null。 */
    static String resolveCursorKind(Position screenPos) {
        List<Document> layered = DocumentLayerOrder.frontToBack(Document.getAll());
        if (layered == null) {
            return null;
        }
        for (Document document : layered) {
            FileExplorerController controller = controllerFor(document);
            if (controller == null) {
                continue;
            }
            String kind = controller.resolveCursorKind(screenPos);
            if (kind != null) {
                return kind;
            }
        }
        return null;
    }

    private static void showGlobalGhost(String html) {
        Element ghost = FileExplorerTrigger.getGlobalGhost();
        if (ghost == null) {
            return;
        }
        ghost.setInnerHTML(html);
        // 虚影必须 fixed 定位、相对屏幕视口，跨窗口拖拽时才不会被任何窗口裁剪。
        ghost.setInlineStyleProperty("position", "fixed");
        ghost.setInlineStyleProperty("display", "flex");
        moveGlobalGhost();
    }

    private static void moveGlobalGhost() {
        Element ghost = FileExplorerTrigger.getGlobalGhost();
        Document document = FileExplorerTrigger.getDocument();
        if (ghost == null || document == null) {
            return;
        }
        // 直接用鼠标在文档里的位置（等价 MouseEvent.clientX/clientY），不再手工换算屏幕坐标。
        Position p = document.getMouseDocumentPosition();
        if (p == null) {
            return;
        }
        ghost.setInlineStyleProperty("left", FileExplorerController.px(p.x));
        ghost.setInlineStyleProperty("top", FileExplorerController.px(p.y));
    }

    private static void hideGlobalGhost() {
        Element ghost = FileExplorerTrigger.getGlobalGhost();
        if (ghost != null) {
            ghost.setInlineStyleProperty("display", "none");
        }
    }

    private static void cancelDrag() {
        hideGlobalGhost();
        dragSource = null;
        dragEntries = null;
        dragSourceKey = null;
        dragRight = false;
        dragActive = false;
        dragCursorKind = null;
        hoverFolderSince = 0L;
        hoverFolderKey = null;
    }

    /** 供 FileExplorerCursor 设置拖拽光标类型。 */
    public static void setDragCursorKind(String kind) {
        dragCursorKind = kind;
    }

    /** 返回拖拽激活期间的光标类型（覆盖 hitTest 结果）。 */
    public static String getDragCursorKind() {
        return dragCursorKind;
    }

    private static void setGhostBadge(String text) {
        Element ghost = FileExplorerTrigger.getGlobalGhost();
        if (ghost == null) {
            return;
        }
        Element badge = ghost.querySelector(".fe-ghost-badge");
        if (badge != null) {
            badge.setTextContent(text);
        }
    }

    /** 一次拖拽的落点：目标窗口 + 目标文件夹 key。 */
    record DropTarget(FileExplorerController controller, String key) {
    }
}
