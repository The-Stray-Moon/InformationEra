package cc.sighs.informationera.client;

import com.sighs.apricityui.layout.Size;

/**
 * 窗口最大化 / 最小化 / 还原的状态机（纯逻辑，不依赖界面）。
 *
 * <p>三条不变量，和 Windows 资源管理器保持一致：
 * <ul>
 *   <li>最小化不改变最大化状态：最大化窗口最小化后再从任务栏恢复，仍然是最大化；</li>
 *   <li>最小化不覆盖普通还原矩形：反复最小化/最大化不会把玩家调好的普通尺寸弄丢；</li>
 *   <li>最大化期间视口变化，还原/恢复都按当前视口重新裁剪。</li>
 * </ul>
 *
 * <p>这里只保存“普通矩形 + 两个标志”，可见矩形仍由控制器持有；所有方法都接收
 * 当前可见矩形和视口，返回操作后的可见矩形，方便独立测试。
 */
final class ExplorerWindowMode {

    /** 最大化之前（或上次取消最大化后）的普通矩形；只在最大化期间有意义。 */
    private ExplorerWindowGeometry.Frame normalFrame;
    private boolean maximized;
    private boolean minimized;

    boolean isMaximized() {
        return maximized;
    }

    boolean isMinimized() {
        return minimized;
    }

    /** 当前窗口的普通（非最大化）矩形；未最大化时普通矩形就是可见矩形本身。 */
    ExplorerWindowGeometry.Frame normalFrame(ExplorerWindowGeometry.Frame visible) {
        return maximized && normalFrame != null ? normalFrame : visible;
    }

    /** 以“最大化”状态开始（用于恢复上次窗口状态），普通矩形只记录不显示。 */
    void startMaximized(ExplorerWindowGeometry.Frame normal) {
        normalFrame = normal;
        maximized = true;
        minimized = false;
    }

    /** 最大化：记住当前普通矩形，返回铺满视口的矩形。 */
    ExplorerWindowGeometry.Frame maximize(ExplorerWindowGeometry.Frame visible, Size viewport) {
        if (!maximized) {
            normalFrame = visible;
            maximized = true;
        }
        return ExplorerWindowGeometry.maximizedFrame(viewport);
    }

    /**
     * 取消最大化：返回按当前视口裁剪后的普通矩形。
     * 没有普通矩形记录（异常状态）时退回当前可见矩形。
     */
    ExplorerWindowGeometry.Frame unmaximize(ExplorerWindowGeometry.Frame visible, Size viewport) {
        if (!maximized) {
            return ExplorerWindowGeometry.fit(visible, viewport);
        }
        ExplorerWindowGeometry.Frame frame = normalFrame == null ? visible : normalFrame;
        maximized = false;
        normalFrame = null;
        return ExplorerWindowGeometry.fit(frame, viewport);
    }

    /** 视口变化后的可见矩形：最大化铺满，普通窗口只做裁剪（只缩不放）。 */
    ExplorerWindowGeometry.Frame adapt(ExplorerWindowGeometry.Frame visible, Size viewport) {
        return maximized
                ? ExplorerWindowGeometry.maximizedFrame(viewport)
                : ExplorerWindowGeometry.fit(visible, viewport);
    }

    /** 最小化：只改标志，不动最大化状态，也不动普通矩形。 */
    void minimize() {
        minimized = true;
    }

    /**
     * 从最小化恢复：最大化窗口恢复后仍然铺满当前视口；普通窗口回到最小化前的矩形并裁进视口。
     */
    ExplorerWindowGeometry.Frame restoreFromMinimize(ExplorerWindowGeometry.Frame visible, Size viewport) {
        minimized = false;
        return adapt(visible, viewport);
    }
}
