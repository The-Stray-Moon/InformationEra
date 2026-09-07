package cc.sighs.informationera.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.Minecraft;

/**
 * 左上角的文件夹图标按钮，点击打开/关闭文件资源管理器窗口。
 * 只在“游戏内有 Screen 打开”（Minecraft.screen 非空）时显示。
 */
public final class FileExplorerTrigger {

    private static final String TEMPLATE = "overlays/file-explorer-trigger.html";
    private static Document document;
    private static Element button;
    private static Element globalGhost;
    private static boolean created;

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
            globalGhost = document.getElementById("fe-global-drag-ghost");
            if (button != null) {
                for (String type : new String[]{"mousedown", "mouseup", "click"}) {
                    button.addEventListener(type, e -> {
                        if (e instanceof MouseEvent) {
                            ((MouseEvent) e).consumeNative();
                        }
                    });
                }
                button.addEventListener("click", e -> FileExplorerWindow.open());
            }
            created = true;
        }
        updateVisibility();
    }

    public static Document getDocument() {
        return document;
    }

    public static Element getGlobalGhost() {
        return globalGhost;
    }

    private static void updateVisibility() {
        if (button == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 主菜单和游戏世界内都显示入口按钮，保证随时能点开窗口。
        boolean show = mc.level != null || mc.screen != null;
        button.setInlineStyleProperty("display", show ? "block" : "none");
    }
}
