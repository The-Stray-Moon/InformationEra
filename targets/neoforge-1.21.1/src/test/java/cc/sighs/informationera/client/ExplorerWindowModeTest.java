package cc.sighs.informationera.client;

import com.sighs.apricityui.layout.Size;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最大化 / 最小化 / 还原状态机的纯逻辑测试。
 *
 * <p>重点回归两件事：最大化窗口最小化后再恢复仍然最大化；普通还原矩形在
 * 最小化、最大化、视口变化之间不会丢失。
 */
public class ExplorerWindowModeTest {

    private static final Size VIEWPORT = new Size(1463, 822);

    private static final ExplorerWindowGeometry.Frame NORMAL =
            new ExplorerWindowGeometry.Frame(120, 90, 900, 500);

    private static void assertFrame(ExplorerWindowGeometry.Frame frame,
                                    double x, double y, double width, double height) {
        assertEquals(x, frame.x(), 0.001);
        assertEquals(y, frame.y(), 0.001);
        assertEquals(width, frame.width(), 0.001);
        assertEquals(height, frame.height(), 0.001);
    }

    @Test
    public void maximizeSavesNormalRectAndFillsViewport() {
        ExplorerWindowMode mode = new ExplorerWindowMode();
        ExplorerWindowGeometry.Frame maximized = mode.maximize(NORMAL, VIEWPORT);

        assertTrue(mode.isMaximized());
        assertFrame(maximized, 0, 0, 1463, 822);
        assertFrame(mode.normalFrame(maximized), 120, 90, 900, 500);
    }

    @Test
    public void minimizeKeepsMaximizedStateAndNormalRect() {
        ExplorerWindowMode mode = new ExplorerWindowMode();
        ExplorerWindowGeometry.Frame maximized = mode.maximize(NORMAL, VIEWPORT);

        mode.minimize();
        assertTrue(mode.isMinimized());
        assertTrue(mode.isMaximized());
        assertFrame(mode.normalFrame(maximized), 120, 90, 900, 500);

        ExplorerWindowGeometry.Frame restored = mode.restoreFromMinimize(maximized, VIEWPORT);
        assertFalse(mode.isMinimized());
        assertTrue(mode.isMaximized());
        assertFrame(restored, 0, 0, 1463, 822);
        assertFrame(mode.normalFrame(restored), 120, 90, 900, 500);

        ExplorerWindowGeometry.Frame backToNormal = mode.unmaximize(restored, VIEWPORT);
        assertFalse(mode.isMaximized());
        assertFrame(backToNormal, 120, 90, 900, 500);
    }

    @Test
    public void minimizeRestoreOfNormalWindowReturnsTheSameFrame() {
        ExplorerWindowMode mode = new ExplorerWindowMode();

        mode.minimize();
        ExplorerWindowGeometry.Frame restored = mode.restoreFromMinimize(NORMAL, VIEWPORT);

        assertFalse(mode.isMinimized());
        assertFalse(mode.isMaximized());
        assertFrame(restored, 120, 90, 900, 500);
    }

    @Test
    public void restoreMaximizedFillsViewportAfterViewportChange() {
        ExplorerWindowMode mode = new ExplorerWindowMode();
        mode.startMaximized(NORMAL);
        mode.minimize();

        Size shorter = new Size(1200, 600);
        ExplorerWindowGeometry.Frame restored = mode.restoreFromMinimize(
                new ExplorerWindowGeometry.Frame(0, 0, 1463, 822), shorter);

        assertTrue(mode.isMaximized());
        assertFrame(restored, 0, 0, 1200, 600);
        assertFrame(mode.normalFrame(restored), 120, 90, 900, 500);
    }

    @Test
    public void unmaximizeFitsNormalRectIntoShrunkenViewport() {
        ExplorerWindowMode mode = new ExplorerWindowMode();
        ExplorerWindowGeometry.Frame maximized = mode.maximize(
                new ExplorerWindowGeometry.Frame(100, 400, 900, 400), VIEWPORT);

        ExplorerWindowGeometry.Frame restored = mode.unmaximize(maximized, new Size(1463, 600));

        assertFrame(restored, 100, 200, 900, 400);
        assertTrue(mode.normalFrame(restored).y() >= 0);
    }

    @Test
    public void adaptKeepsNormalWindowSizeAndClampsIt() {
        ExplorerWindowMode mode = new ExplorerWindowMode();
        ExplorerWindowGeometry.Frame adapted = mode.adapt(
                new ExplorerWindowGeometry.Frame(300, 700, 900, 400), new Size(1463, 600));

        assertFrame(adapted, 300, 200, 900, 400);
        assertFalse(mode.isMaximized());
    }

    @Test
    public void startMaximizedDoesNotStartMinimized() {
        ExplorerWindowMode mode = new ExplorerWindowMode();
        mode.startMaximized(NORMAL);

        assertTrue(mode.isMaximized());
        assertFalse(mode.isMinimized());
    }

    @Test
    public void repeatedMinimizeAndMaximizeDoNotForgetNormalRect() {
        ExplorerWindowMode mode = new ExplorerWindowMode();
        ExplorerWindowGeometry.Frame visible = mode.maximize(NORMAL, VIEWPORT);
        for (int i = 0; i < 3; i++) {
            mode.minimize();
            visible = mode.restoreFromMinimize(visible, VIEWPORT);
            assertTrue(mode.isMaximized());
        }
        visible = mode.unmaximize(visible, VIEWPORT);
        assertFrame(visible, 120, 90, 900, 500);
    }
}
