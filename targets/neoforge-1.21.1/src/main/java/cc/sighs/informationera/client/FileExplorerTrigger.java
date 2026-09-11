package cc.sighs.informationera.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.Minecraft;

/**
 * 任务栏上的「文件资源管理器」任务标。
 * 无窗口：点击打开新窗口；单窗口：点击聚焦/最小化 toggle；多窗口：点击或悬停展开缩略图预览。
 * 右键弹出 Jump List（打开新窗口 / 最近路径）。
 */
public final class FileExplorerTrigger {

    private static final String TEMPLATE = "overlays/file-explorer-trigger.html";

    private static Document document;
    private static Element button;
    private static Element badge;
    private static Element preview;
    private static Element capture;
    private static Element globalGhost;
    private static boolean created;
    private static boolean previewPinned;

    private FileExplorerTrigger() {
    }

    /** 每 tick 调用：创建一次按钮，并按其可见条件显示/隐藏。 */
    public static void tick() {
        if (!created) {
            document = ApricityUI.createDocument(TEMPLATE);
            if (document == null) {
                return;
            }
            document.setReloadPersistent(true);
            button = document.getElementById("fe-trigger-btn");
            badge = document.getElementById("fe-taskbar-badge");
            preview = document.getElementById("fe-taskbar-preview");
            capture = document.getElementById("fe-taskbar-capture");
            globalGhost = document.getElementById("fe-global-drag-ghost");
            bind();
            created = true;
        }
        updateTaskbar();
        processPreviewHide();
    }

    private static void bind() {
        if (button == null) {
            return;
        }
        for (String type : new String[]{"mousedown", "mouseup", "click", "contextmenu"}) {
            button.addEventListener(type, e -> {
                if (e instanceof MouseEvent me) {
                    me.consumeNative();
                }
            });
        }
        // 左键：无窗口开新窗；单窗口聚焦/最小化；多窗口展开预览。
        button.addEventListener("click", e -> onLeftClick());
        // 右键：Jump List。
        button.addEventListener("contextmenu", e -> {
            if (e instanceof MouseEvent me) {
                showJumpList(me.clientX, me.clientY);
            }
        });
        // 悬停 0.5 秒后才显示缩略图。
        button.addEventListener("mouseover", e -> schedulePreviewShow());
        button.addEventListener("mouseout", e -> {
            cancelPreviewShow();
            scheduleHidePreview();
        });
        if (preview != null) {
            preview.addEventListener("mouseover", e -> cancelHidePreview());
            preview.addEventListener("mouseout", e -> scheduleHidePreview());
        }
        if (capture != null) {
            capture.addEventListener("mousedown", e -> {
                hidePreview();
                FileExplorerWindow.hideJumpList(document);
            });
        }
        // 全局 mousedown：点击非菜单/按钮/预览区域时关闭 Jump List。
        document.addEventListener("mousedown", e -> {
            Element target = e.target instanceof Element ? (Element) e.target : null;
            Element jumpList = document.getElementById("fe-jump-list");
            if (jumpList == null) {
                return;
            }
            if (target != null && (target.closest(".fe-jump-list") != null
                    || target.closest("#fe-trigger-btn") != null
                    || target.closest(".fe-taskbar-preview") != null)) {
                return;
            }
            FileExplorerWindow.hideJumpList(document);
            hidePreview();
        });
    }

    private static void onLeftClick() {
        int count = FileExplorerWindow.windowCount();
        if (count == 0) {
            FileExplorerWindow.open();
        } else if (count == 1) {
            FileExplorerController controller = FileExplorerWindow.focused();
            if (controller == null) {
                return;
            }
            if (controller.isMinimized()) {
                controller.toggleMinimizeForTaskbar();
                FileExplorerWindow.focus(controller);
            } else {
                controller.toggleMinimizeForTaskbar();
            }
        } else {
            // 点击直接显示并固定预览，不因 hover 取消而消失。
            previewPinned = true;
            // 清掉 hover 遗留的 show/hide 定时器，避免点击唤起后被延迟隐藏。
            cancelPreviewShow();
            cancelHidePreview();
            showPreview();
        }
    }

    private static void showJumpList(double x, double y) {
        FileExplorerWindow.showJumpList(document, x, y);
        if (capture != null) {
            capture.setInlineStyleProperty("display", "block");
        }
    }

    private static void showPreview() {
        if (preview == null) {
            return;
        }
        FileExplorerWindow.renderTaskbarPreview(preview, document);
        preview.setInlineStyleProperty("display", "block");
        if (capture != null) {
            capture.setInlineStyleProperty("display", "block");
        }
    }

    private static long hidePreviewAt = 0L;
    private static long showPreviewAt = 0L;

    /** hover 后 0.5 秒显示。 */
    private static void schedulePreviewShow() {
        if (previewPinned || FileExplorerWindow.windowCount() == 0) {
            return;
        }
        showPreviewAt = System.currentTimeMillis() + 500L;
    }

    private static void cancelPreviewShow() {
        showPreviewAt = 0L;
    }

    private static void scheduleHidePreview() {
        if (previewPinned) {
            return;
        }
        hidePreviewAt = System.currentTimeMillis() + 200L;
    }

    private static void cancelHidePreview() {
        hidePreviewAt = 0L;
    }

    public static void hidePreview() {
        if (preview != null) {
            preview.setInlineStyleProperty("display", "none");
        }
        if (capture != null) {
            capture.setInlineStyleProperty("display", "none");
        }
        hidePreviewAt = 0L;
        showPreviewAt = 0L;
        previewPinned = false;
    }

    /** 在 tick 里调用：处理延迟显示和延迟隐藏。 */
    private static void processPreviewHide() {
        if (showPreviewAt != 0L && System.currentTimeMillis() >= showPreviewAt) {
            showPreviewAt = 0L;
            showPreview();
        }
        if (hidePreviewAt != 0L && System.currentTimeMillis() >= hidePreviewAt) {
            hidePreview();
        }
    }

    private static void updateTaskbar() {
        if (button == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean show = mc.level != null || mc.screen != null;
        button.setInlineStyleProperty("display", show ? "block" : "none");

        if (badge != null) {
            int count = FileExplorerWindow.windowCount();
            if (count > 1) {
                badge.setTextContent(String.valueOf(count));
                badge.setInlineStyleProperty("display", "block");
            } else {
                badge.setInlineStyleProperty("display", "none");
            }
        }
    }

    public static Document getDocument() {
        return document;
    }

    public static Element getGlobalGhost() {
        return globalGhost;
    }
}
