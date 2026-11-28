package cc.sighs.informationera.client;

import com.sighs.apricityui.layout.Size;

/**
 * 资源管理器窗口的几何策略：默认尺寸、视口裁剪、最大化与还原。
 *
 * <p>坐标一律是 AUI 的 Document 逻辑像素。资源管理器页面是 {@code mode=browser}
 * 视口：宽度按桌面 CSS 尺寸固定，高度随游戏窗口宽高比变化；GUI 缩放只改变渲染比例，
 * 不改变文档坐标。因此分辨率、GUI 缩放、窗口大小变化在这里统一表现为 {@link Size}
 * 视口变化，窗口只需要保证自己始终落在视口内。
 *
 * <p>所有对外方法都返回新值，不修改调用方状态：窗口状态仍由控制器持有。
 */
final class ExplorerWindowGeometry {

    /** 新窗口的首选尺寸；视口更小时会被裁到视口内。 */
    static final double PREFERRED_WIDTH = 980;
    static final double PREFERRED_HEIGHT = 600;

    /** 正常窗口的最小尺寸；视口比它还小时以视口为准。 */
    static final double MIN_WIDTH = 480;
    static final double MIN_HEIGHT = 320;

    /** 同一会话连续开窗时的层叠位移。 */
    static final double CASCADE_STEP = 26;

    /** 视口不可用（尚未完成布局）时的兜底尺寸。 */
    private static final double FALLBACK_VIEWPORT_WIDTH = 1280;
    private static final double FALLBACK_VIEWPORT_HEIGHT = 720;

    private ExplorerWindowGeometry() {
    }

    /** 一个窗口矩形：左上角 + 尺寸，全部是 Document 逻辑像素。 */
    record Frame(double x, double y, double width, double height) {
    }

    /** 没有保存过位置时的默认窗口：居中。 */
    static Frame initialFrame(Size viewport, int windowIndex) {
        Size usable = usable(viewport);
        Frame base = new Frame((usable.width() - PREFERRED_WIDTH) / 2,
                (usable.height() - PREFERRED_HEIGHT) / 2,
                PREFERRED_WIDTH, PREFERRED_HEIGHT);
        return cascade(fit(base, usable), usable, windowIndex);
    }

    /** 最大化：铺满整个视口（最大化窗口不保留边距）。 */
    static Frame maximizedFrame(Size viewport) {
        Size usable = usable(viewport);
        return new Frame(0, 0, usable.width(), usable.height());
    }

    /**
     * 把窗口裁进视口：尺寸夹在 [最小尺寸, 视口] 之间，位置夹到视口内。
     *
     * <p>视口缩小时窗口会跟着缩小并贴边，保证标题栏和右下角都还能点到；
     * 视口放大时不会替玩家把窗口撑大，只保证它仍然在视口内。
     */
    static Frame fit(Frame frame, Size viewport) {
        Size usable = usable(viewport);
        double width = clamp(frame.width(), minWidth(usable), usable.width());
        double height = clamp(frame.height(), minHeight(usable), usable.height());
        double x = clamp(frame.x(), 0, Math.max(0, usable.width() - width));
        double y = clamp(frame.y(), 0, Math.max(0, usable.height() - height));
        return new Frame(x, y, width, height);
    }

    /**
     * 连续开窗时相对已有窗口错开一点，避免新窗口完全盖住旧窗口。
     * 错开后仍然要裁进视口，所以走到边缘后就停在边上。
     */
    static Frame cascade(Frame frame, Size viewport, int windowIndex) {
        if (windowIndex <= 0) {
            return frame;
        }
        double offset = CASCADE_STEP * windowIndex;
        return fit(new Frame(frame.x() + offset, frame.y() + offset, frame.width(), frame.height()), viewport);
    }

    /** 当前视口允许的最小宽度（视口极小时降到视口宽度，避免永远溢出）。 */
    static double minWidth(Size viewport) {
        return Math.min(MIN_WIDTH, usable(viewport).width());
    }

    /** 当前视口允许的最小高度。 */
    static double minHeight(Size viewport) {
        return Math.min(MIN_HEIGHT, usable(viewport).height());
    }

    /** 视口尺寸不可用（0 或负数）时退回一个安全的默认值。 */
    private static Size usable(Size viewport) {
        double width = viewport == null ? 0 : viewport.width();
        double height = viewport == null ? 0 : viewport.height();
        if (!(width > 1) || !(height > 1)) {
            return new Size(FALLBACK_VIEWPORT_WIDTH, FALLBACK_VIEWPORT_HEIGHT);
        }
        return viewport;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
