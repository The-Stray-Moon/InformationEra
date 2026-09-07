package cc.sighs.informationera.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.render.DocumentLayerOrder;
import com.sighs.apricityui.render.Operation;

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
    private static VirtualFileSystem fs;

    /** 跨窗口拖拽的全局状态（坐标均使用 AUI 屏幕坐标）。 */
    private static FileExplorerController dragSource;
    private static VirtualFileSystem.Entry dragEntry;
    private static String dragSourceKey;
    private static boolean dragRight;
    private static double dragStartX;
    private static double dragStartY;
    private static boolean dragActive;
    private static boolean suppressNextContextMenu;

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
    }

    public static void unregister(FileExplorerController controller) {
        windows.remove(controller);
        if (dragSource == controller) {
            cancelDrag();
        }
        if (!windows.isEmpty()) {
            FileExplorerController.setActive(windows.get(windows.size() - 1));
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
    public static void beginFileDrag(FileExplorerController source, VirtualFileSystem.Entry entry,
                                     String sourceKey, boolean right) {
        Position mouse = Operation.getMousePositionDirectly();
        if (mouse == null) {
            return;
        }
        dragSource = source;
        dragEntry = entry;
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
            double dx = mouse.x - dragStartX;
            double dy = mouse.y - dragStartY;
            if (dx * dx + dy * dy <= 25) {
                return;
            }
            dragActive = true;
            if (dragRight) {
                dragSource.closeContextMenu();
            }
            String html = dragSource.ghostHtmlFor(dragEntry);
            showGlobalGhost(html);
        }

        moveGlobalGhost();

        // 右键拖拽的释放由 document mouseup 同步处理，避免 contextmenu 与 tick 竞争；
        // 左键跨窗口拖拽仍由这里轮询释放。
        if (!dragRight) {
            boolean released = (Operation.getMouseButtons() & 1) == 0;
            if (released) {
                finishDrag(mouse.x, mouse.y);
            }
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
                target.controller.showExternalDropMenu(dragEntry, dragSourceKey, target.key,
                        new Position(screenX, screenY), dragSource);
                suppressNextContextMenu = true;
            }
            // target == null：不抑制，让 contextmenu 正常弹普通右键菜单。
        } else if (target != null) {
            boolean copy = Operation.isKeyPressed("key.keyboard.left.control")
                    || Operation.isKeyPressed("key.keyboard.right.control");
            boolean moved = dragSource.moveOrCopy(dragSourceKey, dragEntry, target.key, copy);
            if (moved && target.controller != dragSource) {
                target.controller.refreshAfterExternal();
            }
        }
        cancelDrag();
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
        dragEntry = null;
        dragSourceKey = null;
        dragRight = false;
        dragActive = false;
    }

    /** 一次拖拽的落点：目标窗口 + 目标文件夹 key。 */
    record DropTarget(FileExplorerController controller, String key) {
    }
}
