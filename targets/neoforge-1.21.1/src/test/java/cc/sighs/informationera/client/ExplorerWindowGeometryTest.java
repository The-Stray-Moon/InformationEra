package cc.sighs.informationera.client;

import com.sighs.apricityui.layout.Size;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 窗口几何策略的纯逻辑测试：视口裁剪、最小尺寸、层叠与最大化矩形。 */
public class ExplorerWindowGeometryTest {

    private static final Size DESKTOP = new Size(1463, 822);

    private static void assertFrame(ExplorerWindowGeometry.Frame frame,
                                    double x, double y, double width, double height) {
        assertEquals(x, frame.x(), 0.001);
        assertEquals(y, frame.y(), 0.001);
        assertEquals(width, frame.width(), 0.001);
        assertEquals(height, frame.height(), 0.001);
    }

    private static boolean inside(ExplorerWindowGeometry.Frame frame, Size viewport) {
        return frame.x() >= -0.001 && frame.y() >= -0.001
                && frame.x() + frame.width() <= viewport.width() + 0.001
                && frame.y() + frame.height() <= viewport.height() + 0.001;
    }

    @Test
    public void defaultFrameIsCenteredPreferredSize() {
        assertFrame(ExplorerWindowGeometry.initialFrame(DESKTOP, 0), 241.5, 111, 980, 600);
    }

    @Test
    public void defaultFrameShrinksToSmallViewport() {
        Size small = new Size(700, 400);
        ExplorerWindowGeometry.Frame frame = ExplorerWindowGeometry.initialFrame(small, 0);
        assertFrame(frame, 0, 0, 700, 400);
        assertTrue(inside(frame, small));
    }

    @Test
    public void tinyViewportLowersMinimumSize() {
        Size tiny = new Size(400, 200);
        assertEquals(400, ExplorerWindowGeometry.minWidth(tiny), 0.001);
        assertEquals(200, ExplorerWindowGeometry.minHeight(tiny), 0.001);
        assertFrame(ExplorerWindowGeometry.fit(new ExplorerWindowGeometry.Frame(-50, -80, 900, 700), tiny),
                0, 0, 400, 200);
    }

    @Test
    public void fitKeepsFrameInsideAndDoesNotGrowIt() {
        ExplorerWindowGeometry.Frame frame = new ExplorerWindowGeometry.Frame(1200, 700, 500, 400);
        assertFrame(ExplorerWindowGeometry.fit(frame, DESKTOP), 963, 422, 500, 400);
    }

    @Test
    public void fitDoesNotMoveFrameWhenItAlreadyFits() {
        ExplorerWindowGeometry.Frame frame = new ExplorerWindowGeometry.Frame(100, 80, 900, 500);
        assertFrame(ExplorerWindowGeometry.fit(frame, DESKTOP), 100, 80, 900, 500);
    }

    @Test
    public void cascadeOffsetsNewWindowsButKeepsThemInside() {
        ExplorerWindowGeometry.Frame base = new ExplorerWindowGeometry.Frame(100, 100, 900, 500);
        assertFrame(ExplorerWindowGeometry.cascade(base, DESKTOP, 0), 100, 100, 900, 500);
        assertFrame(ExplorerWindowGeometry.cascade(base, DESKTOP, 2), 152, 152, 900, 500);
        ExplorerWindowGeometry.Frame edged = ExplorerWindowGeometry.cascade(
                new ExplorerWindowGeometry.Frame(500, 300, 900, 500), DESKTOP, 3);
        assertTrue(inside(edged, DESKTOP));
    }

    @Test
    public void maximizedFrameFillsViewport() {
        assertFrame(ExplorerWindowGeometry.maximizedFrame(DESKTOP), 0, 0, 1463, 822);
    }

    @Test
    public void unavailableViewportFallsBackToSafeSize() {
        ExplorerWindowGeometry.Frame frame = ExplorerWindowGeometry.initialFrame(new Size(0, 0), 0);
        assertTrue(frame.width() > 0 && frame.height() > 0);
        assertTrue(inside(frame, new Size(1280, 720)));
    }
}
