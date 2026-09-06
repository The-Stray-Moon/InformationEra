package cc.sighs.informationera.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;

/** 以 Overlay 形式打开/关闭文件资源管理器窗口。 */
public final class FileExplorerWindow {

    private static final String TEMPLATE = "screens/file-explorer.html";

    private FileExplorerWindow() {
    }

    public static void open() {
        FileExplorerController active = FileExplorerController.getActive();
        if (active != null && active.isOpen()) {
            active.closeWindow();
            return;
        }
        Document document = ApricityUI.createDocument(TEMPLATE);
        if (document == null) {
            return;
        }
        // 常驻窗口：即使底层有原版 Screen 也保持显示。
        document.setReloadPersistent(true);
        new FileExplorerController(VirtualFileSystem.create()).mount(document);
    }
}
