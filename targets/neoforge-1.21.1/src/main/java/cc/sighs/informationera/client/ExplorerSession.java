package cc.sighs.informationera.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 文件资源管理器的全局会话状态：剪贴板与撤销/重做栈。
 * 与真实的 Windows 资源管理器一致，这些状态跨窗口共享。
 */
public final class ExplorerSession {

    /** 剪贴板条目：sourceFolderKey 非空表示剪切，null 表示复制。 */
    public record ClipItem(String sourceFolderKey, VirtualFileSystem.Entry entry) {
    }

    public static final List<ClipItem> clipboard = new ArrayList<>();
    public static boolean clipboardCut = false;

    public static final Deque<Runnable> undoStack = new ArrayDeque<>();
    public static final Deque<Runnable> redoStack = new ArrayDeque<>();

    private ExplorerSession() {
    }

    public static void clearClipboard() {
        clipboard.clear();
        clipboardCut = false;
    }
}
