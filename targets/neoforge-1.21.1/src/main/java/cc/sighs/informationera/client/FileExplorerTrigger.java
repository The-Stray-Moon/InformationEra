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
    private static Element button;
    private static boolean created;

    private FileExplorerTrigger() {
    }

    /** 每 tick 调用：创建一次按钮，并按其可见条件显示/隐藏。 */
    public static void tick() {
        if (!created) {
            Document document = ApricityUI.createDocument(TEMPLATE);
            if (document == null) {
                return;
            }
            document.setReloadPersistent(true);
            button = document.getElementById("fe-trigger-btn");
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

    private static void updateVisibility() {
        if (button == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 1.21.1 的字段名是 screen（不是 layer）：游戏世界内 + 有 Screen 打开。
        boolean show = mc.level != null && mc.screen != null;
        button.setInlineStyleProperty("display", show ? "block" : "none");
    }
}
