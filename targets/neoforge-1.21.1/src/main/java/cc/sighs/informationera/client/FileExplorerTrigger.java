package cc.sighs.informationera.client;

import cc.sighs.informationera.net.DiskStateHandler;
import cc.sighs.informationera.util.DiskAccess;
import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 任务栏：左下角的「开始菜单」按钮与「资源管理器」按钮。
 *
 * <p>开始菜单按钮要有量子坍缩反应炉才出现——反应炉是模组的核心，没它就没有这台设备；
 * 资源管理器按钮要有储存卡才出现——卡就是盘，没卡就没盘可看。两个条件都是现算的，
 * 不缓存，装备一摘按钮就消失。
 *
 * <p>原来的「打开资源管理器」入口已经并进这里：左键的开窗/聚焦/缩略图行为挪到了资源管理器
 * 按钮上，开始菜单按钮负责弹出设备面板（{@link StartMenuController}）。
 */
public final class FileExplorerTrigger {

    private static final String TEMPLATE = "overlays/file-explorer-trigger.html";

    private static Document document;
    private static Element startButton;
    private static Element explorerButton;
    private static Element badge;
    private static Element preview;
    private static Element capture;
    private static Element globalGhost;
    private static boolean created;
    private static boolean previewPinned;
    /** 上一 tick 的界面，用来判断「刚打开了容器界面」。 */
    private static Screen lastScreen;

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
            startButton = document.getElementById("fe-start-btn");
            explorerButton = document.getElementById("fe-explorer-btn");
            badge = document.getElementById("fe-taskbar-badge");
            preview = document.getElementById("fe-taskbar-preview");
            capture = document.getElementById("fe-taskbar-capture");
            globalGhost = document.getElementById("fe-global-drag-ghost");
            bind();
            created = true;
        }
        requestStatusOnScreenChange();
        updateTaskbar();
        processPreviewHide();
    }

    /**
     * 进入世界、以及每次切换界面时问一次设备状态。
     *
     * <p>服务端只在被请求时回推，而两个按钮的显示都依赖设备状态，所以必须主动要一次；
     * 这个请求（STATUS）不带磁盘正文，也不会报错，随手调没有副作用。
     */
    private static boolean wasInWorld;

    private static void requestStatusOnScreenChange() {
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.level != null && mc.player != null;
        Screen screen = mc.screen;
        // 刚进世界也要问一次：否则要等玩家开一次界面才会知道设备里有没有卡。
        if (inWorld && (!wasInWorld || screen != lastScreen)) {
            DiskClient.requestStatus();
        }
        wasInWorld = inWorld;
        lastScreen = screen;
    }

    private static void bind() {
        if (startButton != null) {
            consumeNative(startButton);
            startButton.addEventListener("click", e -> {
                StartMenuController.toggle();
                // 面板打开时铺一层全屏拦截层，点空白就能收起。
                if (StartMenuController.isOpen() && capture != null) {
                    capture.setInlineStyleProperty("display", "block");
                }
            });
        }
        if (explorerButton != null) {
            consumeNative(explorerButton);
            // 左键：无窗口开新窗；单窗口聚焦/最小化；多窗口展开预览。
            explorerButton.addEventListener("click", e -> onExplorerClick());
            // 右键：Jump List。
            explorerButton.addEventListener("contextmenu", e -> {
                if (e instanceof MouseEvent me) {
                    showJumpList(me.clientX, me.clientY);
                }
            });
            // 悬停 0.5 秒后才显示缩略图。
            explorerButton.addEventListener("mouseover", e -> schedulePreviewShow());
            explorerButton.addEventListener("mouseout", e -> {
                cancelPreviewShow();
                scheduleHidePreview();
            });
        }
        if (preview != null) {
            preview.addEventListener("mouseover", e -> cancelHidePreview());
            preview.addEventListener("mouseout", e -> scheduleHidePreview());
        }
        if (capture != null) {
            // 拦截层：点面板以外的任何地方都收起来。
            capture.addEventListener("mousedown", e -> {
                hidePreview();
                FileExplorerWindow.hideJumpList(document);
                StartMenuController.close();
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
                    || target.closest(".fe-taskbar-btn") != null
                    || target.closest(".fe-taskbar-preview") != null)) {
                return;
            }
            FileExplorerWindow.hideJumpList(document);
            hidePreview();
        });
    }

    /** 按钮上的点击不该穿透到游戏界面（只在这一小块区域生效）。 */
    private static void consumeNative(Element button) {
        for (String type : new String[]{"mousedown", "mouseup", "click", "contextmenu"}) {
            button.addEventListener(type, e -> {
                if (e instanceof MouseEvent me) {
                    me.consumeNative();
                }
            });
        }
    }

    private static void onExplorerClick() {
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
        if (capture != null && !StartMenuController.isOpen()) {
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
        if (startButton == null || explorerButton == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 只在真正进入世界后显示。不能用 screen != null 判断——标题界面、创建世界界面
        // 本身都是 Screen，那样会在主菜单上就冒出这个按钮。
        boolean inWorld = mc.level != null && mc.player != null;
        boolean hasReactor = inWorld && DiskAccess.canConvert(mc.player);
        boolean hasCard = inWorld && DiskStateHandler.cardInserted();
        startButton.setInlineStyleProperty("display", hasReactor ? "block" : "none");
        explorerButton.setInlineStyleProperty("display", hasCard ? "block" : "none");
        // 反应炉没了这台设备就不存在了，面板要收起来；卡在不在不影响面板本身——
        // 插卡区就在这个面板里，拿没插卡当关闭条件会把面板一开就关掉。
        if (!hasReactor && StartMenuController.isOpen()) {
            StartMenuController.close();
            hidePreview();
        }

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

    /** 服务端状态刷新后重新渲染任务标与开始菜单（窗口数、插卡状态都可能变化）。 */
    public static void refreshTaskbar() {
        updateTaskbar();
        StartMenuController.refresh();
    }

    public static Document getDocument() {
        return document;
    }

    public static Element getGlobalGhost() {
        return globalGhost;
    }
}
